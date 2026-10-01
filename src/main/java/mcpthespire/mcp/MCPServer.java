package mcpthespire.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import mcpthespire.Snapshot;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP Server implementation using Streamable HTTP transport.
 * Implements JSON-RPC 2.0 protocol for MCP communication.
 */
public class MCPServer implements Runnable {

    private static final Logger logger = LogManager.getLogger(MCPServer.class.getName());
    private static final String SERVER_NAME = "MCPTheSpire";
    private static final String SERVER_VERSION = "2.0.0";
    private static final AtomicInteger OPERATION_COUNTER = new AtomicInteger();

    private final String host;
    private final int port;
    private final MCPToolHandler toolHandler;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    private volatile boolean running = true;

    // Queue for pending tool calls that need to be executed on the game thread
    private final BlockingQueue<PendingToolCall> pendingToolCalls;
    private final Map<String, PendingToolCall> operations = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> completedOperationIds = new ConcurrentLinkedQueue<>();
    private static final int MAX_COMPLETED_OPERATIONS = 128;
    private static final long HTTP_WAIT_MS = 20000;
    private static final long MAX_WAIT_FOR_READY_MS = 15000;
    private static final long MAX_SETTLE_MS = 30000;
    private static final long MIN_SETTLE_MS = 100;
    private volatile PendingToolCall activeCall;
    private long renderFrame;

    // Serialize tool handler execution to avoid cross-thread reads during game mutation/rendering
    private final Object toolExecutionLock = new Object();

    // Pending batch execution state (for execute_actions across multiple frames)
    private volatile PendingBatchExecution pendingBatch = null;
    private long lastBatchActionTime = 0;
    private static final long MIN_BATCH_ACTION_INTERVAL_MS = 50;

    public MCPServer() {
        this("127.0.0.1", 8080);
    }

    public MCPServer(int port) {
        this("127.0.0.1", port);
    }

    public MCPServer(String host, int port) {
        this.host = host;
        this.port = port;
        this.toolHandler = new MCPToolHandler();
        this.pendingToolCalls = new LinkedBlockingQueue<>();
    }

    @Override
    public void run() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpExecutor = Executors.newFixedThreadPool(4);
            httpServer.setExecutor(httpExecutor);

            // Main MCP endpoint - Streamable HTTP
            httpServer.createContext("/mcp", new MCPHandler());

            // Health check endpoint
            httpServer.createContext("/health", exchange -> {
                String response = "{\"status\":\"ok\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            });

            httpServer.start();
            logger.info("MCP Server (Streamable HTTP) started on http://" + host + ":" + port);
            logger.info("MCP endpoint: http://" + host + ":" + port + "/mcp");

            // Keep running
            while (running) {
                Thread.sleep(1000);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error starting MCP HTTP server", e);
        }
    }

    /**
     * Streamable HTTP Handler - handles all MCP communication
     */
    private class MCPHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String origin = exchange.getRequestHeaders().getFirst("Origin");

            if (origin != null) {
                try {
                    URI uri = URI.create(origin);
                    if (!"http".equalsIgnoreCase(uri.getScheme()) ||
                        !host.equalsIgnoreCase(uri.getHost()) || uri.getPort() != port ||
                        uri.getRawUserInfo() != null || uri.getRawQuery() != null ||
                        uri.getRawFragment() != null ||
                        (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                        exchange.sendResponseHeaders(403, -1);
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Vary", "Origin");
            }

            // Handle CORS preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                handleCORS(exchange);
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
            } else {
                // This server sends no unsolicited messages, so it offers no GET SSE stream.
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                exchange.sendResponseHeaders(405, -1);
            }
        }

        private void handleCORS(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept, MCP-Protocol-Version");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
            exchange.sendResponseHeaders(204, -1);
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            // Read request body
            String requestBody;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                requestBody = sb.toString();
            }

            logger.info("Received MCP request: " + requestBody);

            try {
                String response = handleMessage(requestBody);
                if (response == null) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                    os.flush();
                }
            } catch (Exception e) {
                logger.error("Error handling MCP request", e);
                String errorResponse = MCPProtocol.buildErrorResponse(null, MCPProtocol.ERROR_INTERNAL, e.getMessage());
                byte[] responseBytes = errorResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        }

    }

    private String handleMessage(String message) {
        JsonObject request;
        try {
            request = MCPProtocol.parseRequest(message);
        } catch (JsonSyntaxException e) {
            return MCPProtocol.buildErrorResponse(null, MCPProtocol.ERROR_PARSE, "Parse error: " + e.getMessage());
        }

        String method = MCPProtocol.getMethod(request);
        JsonElement id = MCPProtocol.getId(request);
        JsonObject params = MCPProtocol.getParams(request);

        logger.info("MCP method: " + method + ", id: " + id);

        // JSON-RPC notifications have no response body.
        if (id == null) {
            if (MCPProtocol.METHOD_INITIALIZED.equals(method)) {
                logger.info("Client initialized notification received");
            }
            return null;
        }

        if (method == null) {
            return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INVALID_REQUEST, "Missing method");
        }

        switch (method) {
            case MCPProtocol.METHOD_INITIALIZE:
                return handleInitialize(id, params);

            case MCPProtocol.METHOD_INITIALIZED:
                return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INVALID_REQUEST,
                    "notifications/initialized must not have an id");

            case MCPProtocol.METHOD_TOOLS_LIST:
                return handleToolsList(id);

            case MCPProtocol.METHOD_TOOLS_CALL:
                return handleToolsCall(id, params);

            case MCPProtocol.METHOD_PING:
                return MCPProtocol.buildResponse(id, new HashMap<>());

            default:
                return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_METHOD_NOT_FOUND, "Unknown method: " + method);
        }
    }

    private String handleInitialize(JsonElement id, JsonObject params) {
        Map<String, Object> result = MCPProtocol.buildInitializeResult(SERVER_NAME, SERVER_VERSION, MCPToolHandler.INSTRUCTIONS);
        logger.info("MCP initialized");
        return MCPProtocol.buildResponse(id, result);
    }

    private String handleToolsList(JsonElement id) {
        Map<String, Object> result = MCPProtocol.buildToolsListResult(toolHandler.getToolDefinitions());
        return MCPProtocol.buildResponse(id, result);
    }

    private String handleToolsCall(JsonElement id, JsonObject params) {
        if (params == null || !params.has("name")) {
            return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INVALID_PARAMS, "Missing tool name");
        }

        String toolName = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
            ? params.get("arguments").getAsJsonObject() : new JsonObject();

        logger.info("Tool call: " + toolName + " with args: " + arguments);

        if ("get_action_status".equals(toolName)) {
            return MCPProtocol.buildResponse(id, getActionStatus(arguments));
        }

        // Every other tool touches live game objects, so it runs on the game thread.
        PendingToolCall pending = new PendingToolCall(toolName, arguments);
        operations.put(pending.operationId, pending);
        pendingToolCalls.add(pending);

        try {
            Map<String, Object> result = pending.result.get(HTTP_WAIT_MS, TimeUnit.MILLISECONDS);
            logger.info("Tool call completed: " + toolName);
            return MCPProtocol.buildResponse(id, result);
        } catch (TimeoutException e) {
            return MCPProtocol.buildResponse(id, MCPProtocol.buildToolCallResult(pendingStatus(pending), false));
        } catch (ExecutionException e) {
            return MCPProtocol.buildResponse(id, MCPProtocol.buildToolCallResult("Error: " + e.getCause(), true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INTERNAL, "Tool execution interrupted");
        }
    }

    private Map<String, Object> getActionStatus(JsonObject arguments) {
        if (arguments == null || !arguments.has("operation_id") || arguments.get("operation_id").isJsonNull()) {
            return MCPProtocol.buildToolCallResult("operation_id is required", true);
        }
        PendingToolCall pending = operations.get(arguments.get("operation_id").getAsString());
        if (pending == null) {
            return MCPProtocol.buildToolCallResult("Unknown or expired operation_id", true);
        }
        return pending.result.isDone()
            ? pending.result.getNow(MCPProtocol.buildToolCallResult("Result unavailable", true))
            : MCPProtocol.buildToolCallResult(pendingStatus(pending), false);
    }

    private String pendingStatus(PendingToolCall pending) {
        StringBuilder s = new StringBuilder("Still ").append(pending.started ? "resolving" : "queued")
            .append(": ").append(pending.toolName);
        if (pending == activeCall && pendingBatch != null) {
            s.append(" (").append(pendingBatch.successCount).append('/').append(pendingBatch.actions.size())
                .append(" actions done)");
        }
        return s.append(". Poll get_action_status with operation_id \"").append(pending.operationId)
            .append("\"; do not resend.").toString();
    }

    private void completeCall(PendingToolCall pending, Map<String, Object> result) {
        pending.result.complete(result);
        completedOperationIds.add(pending.operationId);
        while (completedOperationIds.size() > MAX_COMPLETED_OPERATIONS) {
            String expired = completedOperationIds.poll();
            if (expired != null) operations.remove(expired);
        }
        if (activeCall == pending) activeCall = null;
    }

    public void onPostRender() {
        renderFrame++;
        if (hasPendingToolCalls()) processPendingToolCalls();
    }

    /**
     * Process pending tool calls on the game thread.
     * This should be called from the game's update loop.
     */
    public void processPendingToolCalls() {
        if (activeCall != null) {
            if (activeCall.awaitingSettle) {
                processSettlement();
            } else if (pendingBatch != null) {
                processBatchAction();
            }
            return;
        }

        PendingToolCall pending = pendingToolCalls.peek();
        if (pending == null) return;
        if (!toolHandler.isReadOnlyTool(pending.toolName) &&
            !mcpthespire.GameStateListener.isCommandReadyNow()) {
            if (System.currentTimeMillis() - pending.queuedAt > MAX_WAIT_FOR_READY_MS) {
                pendingToolCalls.poll();
                completeCall(pending, MCPProtocol.buildToolCallResult(
                    "Game not ready; command was not executed (" +
                    mcpthespire.GameStateListener.getReadinessReason() + ")", true));
            }
            return;
        }
        pendingToolCalls.poll();
        pending.started = true;
        activeCall = pending;
        logger.info("Game thread processing tool: " + pending.toolName);
        try {
            if (MCPToolHandler.ACT.equals(pending.toolName)) {
                startBatchExecution(pending.arguments);
                return;
            }
            if (!toolHandler.isReadOnlyTool(pending.toolName)) {
                pending.before = Snapshot.capture();
            }
            Map<String, Object> result;
            synchronized (toolExecutionLock) {
                result = toolHandler.executeTool(pending.toolName, pending.arguments);
            }
            if (toolHandler.isReadOnlyTool(pending.toolName) || Boolean.TRUE.equals(result.get("isError"))) {
                completeCall(pending, result);
            } else {
                pending.acknowledgement = resultText(result);
                markDispatched(pending, 0);
            }
        } catch (Exception e) {
            logger.error("Error executing tool: " + pending.toolName, e);
            completeCall(pending, MCPProtocol.buildToolCallResult("Error: " + e.getMessage(), true));
        }
    }

    @SuppressWarnings("unchecked")
    private static String resultText(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return content == null || content.isEmpty() ? "" : String.valueOf(content.get(0).get("text"));
    }

    /**
     * Start a new batch execution.
     */
    private void startBatchExecution(JsonObject params) {
        if (params == null || !params.has("actions") || !params.get("actions").isJsonArray()) {
            completeCall(activeCall, MCPProtocol.buildToolCallResult("Error: 'actions' array is required", true));
            return;
        }

        com.google.gson.JsonArray actions;
        try {
            actions = normalizeBatchActions(params.getAsJsonArray("actions"));
        } catch (IllegalArgumentException e) {
            completeCall(activeCall, MCPProtocol.buildToolCallResult("Error: " + e.getMessage(), true));
            return;
        }
        if (actions.size() == 0) {
            completeCall(activeCall, MCPProtocol.buildToolCallResult("Error: 'actions' array is empty", true));
            return;
        }

        logger.info("Starting batch execution with " + actions.size() + " actions");
        pendingBatch = new PendingBatchExecution(actions);
        processBatchAction();
    }

    static com.google.gson.JsonArray normalizeBatchActions(com.google.gson.JsonArray actions) {
        com.google.gson.JsonArray normalized = new com.google.gson.JsonArray();
        for (int i = 0; i < actions.size(); i++) {
            JsonElement value = actions.get(i);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                try {
                    value = new JsonParser().parse(value.getAsString());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Action " + (i + 1) + " is not a JSON object");
                }
            }
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Action " + (i + 1) + " must be an object");
            }
            JsonObject action = value.getAsJsonObject();
            if (!action.has("action") || !action.get("action").isJsonPrimitive() ||
                !action.get("action").getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Action " + (i + 1) + " needs a string 'action' field");
            }
            normalized.add(MCPToolHandler.normalizeAction(action, i + 1));
        }
        return normalized;
    }

    /**
     * Process one action from the pending batch.
     * Called once per game frame, with rate limiting to ensure game state updates between actions.
     */
    private void processBatchAction() {
        if (pendingBatch == null) return;

        if (!pendingBatch.hasMoreActions()) {
            finishBatchExecution();
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingBatch.currentIndex > 0 && (now - lastBatchActionTime) < MIN_BATCH_ACTION_INTERVAL_MS) {
            return;
        }

        String actionType = null;
        try {
            JsonObject action = pendingBatch.getCurrentAction();
            actionType = action.get("action").getAsString();
            logger.info("Batch executing action " + (pendingBatch.currentIndex + 1) + "/" + pendingBatch.actions.size() + ": " + actionType);
            pendingBatch.actionBefore = Snapshot.capture();
            if ("wait".equals(actionType)) {
                int waitMs = action.has("ms") ? action.get("ms").getAsInt() : 100;
                if (waitMs < 0) throw new IllegalArgumentException("wait ms must be nonnegative");
                pendingBatch.actionDescription = "wait " + Math.min(waitMs, 500) + "ms";
                markDispatched(activeCall, Math.min(waitMs, 500));
            } else {
                JsonObject resolved = pendingBatch.resolveStableIndices(action);
                synchronized (toolExecutionLock) {
                    pendingBatch.actionDescription = toolHandler.executeAction(actionType, resolved);
                }
                markDispatched(activeCall, 0);
            }
            lastBatchActionTime = System.currentTimeMillis();
        } catch (Exception e) {
            pendingBatch.markError(actionType, e.getMessage());
            finishBatchExecution();
        }
    }

    private void markDispatched(PendingToolCall call, int extraWaitMs) {
        call.awaitingSettle = true;
        call.dispatchedAt = System.currentTimeMillis();
        call.readyAfter = call.dispatchedAt + Math.max(MIN_SETTLE_MS, extraWaitMs);
        call.dispatchedFrame = renderFrame;
        call.lastObservedFrame = -1;
        call.readyFrames = 0;
        call.observedBusy = !mcpthespire.GameStateListener.isCommandReadyNow();
    }

    private void processSettlement() {
        PendingToolCall call = activeCall;
        long now = System.currentTimeMillis();
        if (now - call.dispatchedAt > MAX_SETTLE_MS) {
            if (pendingBatch != null) {
                pendingBatch.markError(pendingBatch.getCurrentAction().get("action").getAsString(),
                    "timed out waiting for the game to settle after this action; check the state before replaying it");
                finishBatchExecution();
            } else {
                completeCall(call, MCPProtocol.buildToolCallResult(call.acknowledgement +
                    "\nWarning: timed out waiting for the game to settle; do not replay blindly.\n" + renderState(), true));
            }
            return;
        }
        if (renderFrame <= call.dispatchedFrame || renderFrame == call.lastObservedFrame || now < call.readyAfter) return;
        call.lastObservedFrame = renderFrame;
        boolean ready = mcpthespire.GameStateListener.isCommandReadyNow();
        if (!ready) call.observedBusy = true;
        call.readyFrames = ready ? call.readyFrames + 1 : 0;
        if (call.readyFrames < 2) return;

        Snapshot after = safeSnapshot();
        Snapshot before = pendingBatch == null ? call.before : pendingBatch.actionBefore;
        boolean isWait = pendingBatch != null &&
            "wait".equals(pendingBatch.getCurrentAction().get("action").getAsString());
        if (!isWait && (!call.observedBusy && after.sameAs(before) ||
            !destinationReached(call.toolName, after))) return;
        call.awaitingSettle = false;
        if (pendingBatch != null) {
            String actionType = pendingBatch.getCurrentAction().get("action").getAsString();
            pendingBatch.log.append(pendingBatch.currentIndex + 1).append(". ")
                .append(pendingBatch.actionDescription).append(": ")
                .append(before == null ? "done" : Snapshot.diff(before, after)).append('\n');
            boolean intentChanged = before != null && !"end_turn".equals(actionType) &&
                Snapshot.intentChanged(before, after);
            pendingBatch.markSuccess();
            pendingBatch.checkScreenChange();
            if (intentChanged && pendingBatch.hasMoreActions()) {
                pendingBatch.stopReason = "an enemy intent changed";
            }
            if (!pendingBatch.hasMoreActions() || pendingBatch.stopReason != null) finishBatchExecution();
        } else {
            completeCall(call, MCPProtocol.buildToolCallResult(call.acknowledgement + "\n" + renderState(), false));
        }
    }

    private Snapshot safeSnapshot() {
        try {
            return Snapshot.capture();
        } catch (Exception e) {
            Snapshot s = new Snapshot();
            s.readiness = "state_error: " + e.getMessage();
            return s;
        }
    }

    private String renderState() {
        try {
            return mcpthespire.StateRenderer.render(java.util.Collections.<String>emptySet());
        } catch (Exception e) {
            logger.error("Error rendering state", e);
            return "(state unavailable: " + e.getMessage() + "; call get_state)";
        }
    }

    static boolean destinationReached(String tool, Snapshot state) {
        if ("start_game".equals(tool) || "continue_game".equals(tool)) {
            return state.inGame;
        }
        if ("save_game".equals(tool)) {
            return !state.inGame && state.screen.startsWith("MAIN_MENU");
        }
        if ("abandon_run".equals(tool)) {
            return !state.inGame && !state.saveExists;
        }
        return true;
    }

    /**
     * Finish batch execution and send result.
     */
    private void finishBatchExecution() {
        if (pendingBatch == null) return;
        PendingBatchExecution batch = pendingBatch;
        int total = batch.actions.size();
        StringBuilder text = new StringBuilder();
        if (batch.errorMessage != null) {
            text.append("Action ").append(batch.failedIndex + 1).append(" (").append(batch.failedAction)
                .append(") failed: ").append(batch.errorMessage).append('\n');
            if (batch.successCount > 0) text.append("Applied before the failure:\n");
        } else if (batch.successCount < total) {
            String reason = batch.stopReason != null ? batch.stopReason : "the screen changed";
            text.append("Stopped after ").append(batch.successCount).append('/').append(total).append(": ")
                .append(reason).append("; remaining actions were not run.\n");
        }
        text.append(batch.log).append('\n').append(renderState());
        logger.info("Batch execution finished: " + batch.successCount + "/" + total);
        pendingBatch = null;
        completeCall(activeCall, MCPProtocol.buildToolCallResult(text.toString().trim(), batch.errorMessage != null));
    }

    /**
     * Check if there are pending tool calls or batch actions to process.
     */
    public boolean hasPendingToolCalls() {
        return !pendingToolCalls.isEmpty() || activeCall != null;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        running = false;
        for (PendingToolCall call : operations.values()) {
            if (!call.result.isDone()) {
                completeCall(call, MCPProtocol.buildToolCallResult("Server stopped before operation completed", true));
            }
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    /**
     * Represents a pending tool call waiting to be executed on the game thread.
     */
    private static class PendingToolCall {
        final String operationId = "op" + OPERATION_COUNTER.incrementAndGet();
        final String toolName;
        final JsonObject arguments;
        final long queuedAt = System.currentTimeMillis();
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        volatile boolean started;
        boolean awaitingSettle;
        long dispatchedAt;
        long readyAfter;
        long dispatchedFrame;
        long lastObservedFrame;
        int readyFrames;
        boolean observedBusy;
        Snapshot before;
        String acknowledgement = "";

        PendingToolCall(String toolName, JsonObject arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }

    /**
     * Represents a pending batch execution that spans multiple frames.
     *
     * Card, target, and choice numbers refer to the state when the batch started; they are
     * translated to the live state before each action runs.
     */
    private static class PendingBatchExecution {
        final com.google.gson.JsonArray actions;
        int currentIndex;
        int successCount;
        int failedIndex = -1;
        String failedAction;
        String errorMessage;
        boolean screenChanged;
        String stopReason;
        Snapshot actionBefore;
        String actionDescription;
        final StringBuilder log = new StringBuilder();
        mcpthespire.ChoiceScreenUtils.ChoiceType initialScreen;
        final boolean initialInDungeon;
        final List<java.util.UUID> initialHandUuids = new ArrayList<>();
        final List<String> initialChoiceList = new ArrayList<>();
        final List<AbstractMonster> initialMonsters = new ArrayList<>();

        PendingBatchExecution(com.google.gson.JsonArray actions) {
            this.actions = actions;
            this.initialInDungeon = mcpthespire.CommandExecutor.isInDungeon();
            if (this.initialInDungeon) {
                this.initialScreen = mcpthespire.ChoiceScreenUtils.getCurrentChoiceType();
                if (AbstractDungeon.player != null) {
                    for (com.megacrit.cardcrawl.cards.AbstractCard card : AbstractDungeon.player.hand.group) {
                        initialHandUuids.add(card.uuid);
                    }
                }
                initialChoiceList.addAll(mcpthespire.ChoiceScreenUtils.getCurrentChoiceList());
                initialMonsters.addAll(mcpthespire.StateRenderer.aliveMonsters());
            }
        }

        boolean hasMoreActions() {
            return currentIndex < actions.size() && errorMessage == null && !screenChanged;
        }

        JsonObject getCurrentAction() {
            return actions.get(currentIndex).getAsJsonObject();
        }

        void markSuccess() {
            successCount++;
            currentIndex++;
        }

        void markError(String action, String error) {
            failedIndex = currentIndex;
            failedAction = action;
            errorMessage = error;
        }

        void checkScreenChange() {
            boolean inDungeon = mcpthespire.CommandExecutor.isInDungeon();
            if (initialInDungeon && inDungeon) {
                mcpthespire.ChoiceScreenUtils.ChoiceType currentScreen = mcpthespire.ChoiceScreenUtils.getCurrentChoiceType();
                if (initialScreen != currentScreen && currentIndex < actions.size()) {
                    String next = actions.get(currentIndex).getAsJsonObject().get("action").getAsString();
                    boolean nextIsHandSelection = currentScreen == mcpthespire.ChoiceScreenUtils.ChoiceType.HAND_SELECT &&
                        "select".equals(next);
                    screenChanged = !nextIsHandSelection && !navigationAvailable(next);
                    if (!screenChanged) {
                        // Choices captured at call time no longer describe this screen.
                        initialScreen = currentScreen;
                        initialChoiceList.clear();
                        initialChoiceList.addAll(mcpthespire.ChoiceScreenUtils.getCurrentChoiceList());
                    }
                }
            } else if (initialInDungeon != inDungeon) {
                screenChanged = true;
            }
        }

        /** Index-free buttons are safe to press on a new screen when that screen offers them. */
        private static boolean navigationAvailable(String action) {
            switch (action) {
                case "proceed":
                case "confirm":
                    return mcpthespire.CommandExecutor.isConfirmCommandAvailable();
                case "skip":
                case "cancel":
                    return mcpthespire.CommandExecutor.isCancelCommandAvailable();
                default:
                    return false;
            }
        }

        /** Translate call-time card/target/choice numbers into the live state's numbers. */
        JsonObject resolveStableIndices(JsonObject action) throws mcpthespire.InvalidCommandException {
            JsonObject resolved = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : action.entrySet()) resolved.add(entry.getKey(), entry.getValue());
            String type = action.get("action").getAsString();
            if (resolved.has("card") && isNumber(resolved.get("card"))) {
                resolved.addProperty("card", stableCard(resolved.get("card").getAsInt()));
            }
            for (String key : new String[]{"drop", "keep"}) {
                if (resolved.has(key) && resolved.get(key).isJsonArray()) {
                    com.google.gson.JsonArray converted = new com.google.gson.JsonArray();
                    for (JsonElement e : resolved.getAsJsonArray(key)) {
                        if (isNumber(e)) converted.add(stableCard(e.getAsInt()));
                        else converted.add(e);
                    }
                    resolved.add(key, converted);
                }
            }
            if (resolved.has("target") && isNumber(resolved.get("target")) && !initialMonsters.isEmpty()) {
                int target = resolved.get("target").getAsInt();
                if (target < 1 || target > initialMonsters.size()) {
                    throw new mcpthespire.InvalidCommandException(
                        "target " + target + " out of range 1-" + initialMonsters.size());
                }
                AbstractMonster monster = initialMonsters.get(target - 1);
                int live = mcpthespire.StateRenderer.aliveMonsters().indexOf(monster);
                if (live < 0) {
                    throw new mcpthespire.InvalidCommandException(monster.name + " (target " + target + ") is already dead");
                }
                resolved.addProperty("target", live + 1);
            }
            if ("choose".equals(type) && resolved.has("choice") && isNumber(resolved.get("choice")) &&
                !initialChoiceList.isEmpty() && mcpthespire.CommandExecutor.isInDungeon() &&
                mcpthespire.ChoiceScreenUtils.getCurrentChoiceType() == initialScreen) {
                int original = resolved.get("choice").getAsInt();
                if (original < 1 || original > initialChoiceList.size()) {
                    throw new mcpthespire.InvalidCommandException(
                        "choice " + original + " out of range 1-" + initialChoiceList.size());
                }
                String name = initialChoiceList.get(original - 1);
                int live = mcpthespire.ChoiceScreenUtils.getCurrentChoiceList().indexOf(name);
                if (live < 0) {
                    throw new mcpthespire.InvalidCommandException("choice " + original + " (" + name + ") is no longer available");
                }
                resolved.addProperty("choice", live + 1);
            }
            return resolved;
        }

        private String stableCard(int index) throws mcpthespire.InvalidCommandException {
            if (index < 1 || index > initialHandUuids.size()) {
                throw new mcpthespire.InvalidCommandException(
                    "card " + index + " out of range 1-" + initialHandUuids.size());
            }
            return "uuid:" + initialHandUuids.get(index - 1);
        }

        private static boolean isNumber(JsonElement e) {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
        }
    }
}
