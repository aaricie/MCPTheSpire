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

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    private static final String SERVER_VERSION = "1.0.0";

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
        Map<String, Object> result = MCPProtocol.buildInitializeResult(SERVER_NAME, SERVER_VERSION);
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
        JsonObject arguments = params.has("arguments") ? params.get("arguments").getAsJsonObject() : new JsonObject();

        logger.info("Tool call: " + toolName + " with args: " + arguments);

        if ("get_action_status".equals(toolName)) {
            return MCPProtocol.buildResponse(id, getActionStatus(arguments));
        }

        // Execute only tools that are explicitly safe on the HTTP thread.
        // Some read-only tools still access live game/UI state and must run on the game thread.
        if (toolHandler.isHttpThreadSafeTool(toolName)) {
            logger.info("Executing HTTP-thread-safe tool directly on HTTP thread: " + toolName);
            try {
                Map<String, Object> result;
                synchronized (toolExecutionLock) {
                    result = toolHandler.executeTool(toolName, arguments);
                }
                return MCPProtocol.buildResponse(id, result);
            } catch (Exception e) {
                logger.error("Error executing HTTP-thread-safe tool: " + toolName, e);
                return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INTERNAL, e.getMessage());
            }
        }

        // Queue the tool call for execution on the game thread
        PendingToolCall pending = new PendingToolCall(toolName, arguments);
        operations.put(pending.operationId, pending);
        pendingToolCalls.add(pending);
        logger.info("Tool call queued for game thread, pending count: " + pendingToolCalls.size());

        try {
            Map<String, Object> result = pending.result.get(HTTP_WAIT_MS, TimeUnit.MILLISECONDS);
            logger.info("Tool call completed: " + toolName);
            return MCPProtocol.buildResponse(id, result);
        } catch (TimeoutException e) {
            return MCPProtocol.buildResponse(id, MCPProtocol.buildToolCallResultJson(pendingStatus(pending)));
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
            : MCPProtocol.buildToolCallResultJson(pendingStatus(pending));
    }

    private Map<String, Object> pendingStatus(PendingToolCall pending) {
        Map<String, Object> status = new HashMap<>();
        status.put("operation_id", pending.operationId);
        status.put("status", pending.started ? "resolving" : "queued");
        status.put("settled", false);
        status.put("tool", pending.toolName);
        if (pending == activeCall && pendingBatch != null) {
            status.put("actions_completed", pendingBatch.successCount);
            status.put("actions_requested", pendingBatch.actions.size());
        }
        return status;
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
            if ("execute_actions".equals(pending.toolName)) {
                startBatchExecution(pending.arguments);
                return;
            }
            if (!toolHandler.isReadOnlyTool(pending.toolName)) {
                pending.before = mcpthespire.GameStateConverter.getActionSummary();
            }
            Map<String, Object> result;
            synchronized (toolExecutionLock) {
                result = toolHandler.executeTool(pending.toolName, pending.arguments);
            }
            if (toolHandler.isReadOnlyTool(pending.toolName) || Boolean.TRUE.equals(result.get("isError"))) {
                completeCall(pending, result);
            } else {
                pending.acknowledgement = result;
                markDispatched(pending, 0);
            }
        } catch (Exception e) {
            logger.error("Error executing tool: " + pending.toolName, e);
            completeCall(pending, MCPProtocol.buildToolCallResult("Error: " + e.getMessage(), true));
        }
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
            normalized.add(action);
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
            pendingBatch.actionBefore = mcpthespire.GameStateConverter.getActionSummary();
            if ("wait".equals(actionType)) {
                int waitMs = action.has("ms") ? action.get("ms").getAsInt() : 100;
                if (waitMs < 0) throw new IllegalArgumentException("wait ms must be nonnegative");
                markDispatched(activeCall, Math.min(waitMs, 500));
            } else {
                // For play_card with card_index, convert to card_uuid for stable resolution
                JsonObject processedAction = action;
                if ("play_card".equals(actionType) && action.has("card_index") &&
                    !action.get("card_index").isJsonNull() && pendingBatch.initialHandUuids != null) {
                    int originalIndex = action.get("card_index").getAsInt();
                    // Validate index against initial hand size
                    if (originalIndex < 1 || originalIndex > pendingBatch.initialHandUuids.size()) {
                        throw new mcpthespire.InvalidCommandException(
                            "card_index " + originalIndex + " out of bounds. " +
                            "Initial hand had " + pendingBatch.initialHandUuids.size() + " cards.");
                    }
                    java.util.UUID targetUuid = pendingBatch.initialHandUuids.get(originalIndex - 1);
                    // Create modified action with card_uuid instead of card_index
                    processedAction = new JsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : action.entrySet()) {
                        if (!"card_index".equals(entry.getKey())) {
                            processedAction.add(entry.getKey(), entry.getValue());
                        }
                    }
                    processedAction.addProperty("card_uuid", targetUuid.toString());
                    logger.info("Converted card_index " + originalIndex + " to card_uuid " + targetUuid);
                }
                // For choose with choice_index, resolve stable index by finding the original choice in current list
                if ("choose".equals(actionType) && processedAction.has("choice_index") &&
                    !processedAction.get("choice_index").isJsonNull() && pendingBatch.initialChoiceList != null) {
                    int originalIndex = processedAction.get("choice_index").getAsInt();
                    // Validate index against initial choice list size
                    if (originalIndex < 1 || originalIndex > pendingBatch.initialChoiceList.size()) {
                        throw new mcpthespire.InvalidCommandException(
                            "choice_index " + originalIndex + " out of bounds. " +
                            "Initial choices had " + pendingBatch.initialChoiceList.size() + " items: " +
                            pendingBatch.initialChoiceList);
                    }
                    String choiceName = pendingBatch.initialChoiceList.get(originalIndex - 1);

                    // Find this choice in the current list to get the updated index
                    java.util.ArrayList<String> currentChoices = mcpthespire.ChoiceScreenUtils.getCurrentChoiceList();
                    int newIndex = -1;
                    for (int i = 0; i < currentChoices.size(); i++) {
                        if (currentChoices.get(i).equals(choiceName)) {
                            newIndex = i + 1;  // 1-indexed
                            break;
                        }
                    }

                    if (newIndex == -1) {
                        throw new mcpthespire.InvalidCommandException(
                            "Choice no longer available: " + choiceName +
                            ". Current choices: " + currentChoices);
                    }

                    // Update choice_index to the new resolved index
                    JsonObject chooseAction = new JsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : processedAction.entrySet()) {
                        if (!"choice_index".equals(entry.getKey())) {
                            chooseAction.add(entry.getKey(), entry.getValue());
                        }
                    }
                    chooseAction.addProperty("choice_index", newIndex);
                    processedAction = chooseAction;
                    logger.info("Resolved choice_index " + originalIndex + " -> " + newIndex + " (choice: " + choiceName + ")");
                }

                // For select_cards with indices in drop/keep, convert to UUIDs for stable resolution
                if ("select_cards".equals(actionType) && pendingBatch.initialHandUuids != null) {
                    processedAction = convertSelectCardsIndices(processedAction, pendingBatch.initialHandUuids);
                }

                // Execute the single action
                toolHandler.executeSingleBatchAction(actionType, processedAction);
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
                    "Timed out waiting for a command-ready state after this action; do not replay it blindly");
                finishBatchExecution();
            } else {
                Map<String, Object> response = settledResponse(call, safeActionSummary(), false);
                response.put("error", "Timed out waiting for a command-ready state; do not replay this action blindly");
                completeCall(call, MCPProtocol.buildToolCallResultJson(response, true));
            }
            return;
        }
        if (renderFrame <= call.dispatchedFrame || renderFrame == call.lastObservedFrame || now < call.readyAfter) return;
        call.lastObservedFrame = renderFrame;
        boolean ready = mcpthespire.GameStateListener.isCommandReadyNow();
        if (!ready) call.observedBusy = true;
        call.readyFrames = ready ? call.readyFrames + 1 : 0;
        if (call.readyFrames < 2) return;

        Map<String, Object> after = safeActionSummary();
        Map<String, Object> before = pendingBatch == null ? call.before : pendingBatch.actionBefore;
        boolean isWait = pendingBatch != null &&
            "wait".equals(pendingBatch.getCurrentAction().get("action").getAsString());
        if (!isWait && (!call.observedBusy && Objects.equals(before, after) ||
            !destinationReached(call.toolName, after))) return;
        call.awaitingSettle = false;
        if (pendingBatch != null) {
            Map<String, Object> actionResult = new HashMap<>();
            actionResult.put("index", pendingBatch.currentIndex + 1);
            actionResult.put("action", pendingBatch.getCurrentAction());
            actionResult.put("before", pendingBatch.actionBefore);
            actionResult.put("after", after);
            pendingBatch.actionResults.add(actionResult);
            boolean intentChanged = enemyIntentChanged(pendingBatch.actionBefore, after);
            pendingBatch.markSuccess();
            pendingBatch.checkScreenChange();
            if (intentChanged && pendingBatch.hasMoreActions()) {
                pendingBatch.stopReason = "enemy_intent_changed";
            }
            if (!pendingBatch.hasMoreActions() || pendingBatch.stopReason != null) finishBatchExecution();
        } else {
            completeCall(call, MCPProtocol.buildToolCallResultJson(settledResponse(call, after, true)));
        }
    }

    private Map<String, Object> safeActionSummary() {
        try {
            return mcpthespire.GameStateConverter.getActionSummary();
        } catch (Exception e) {
            Map<String, Object> state = new HashMap<>();
            state.put("readiness", mcpthespire.GameStateListener.getReadinessReason());
            state.put("state_error", e.getMessage());
            return state;
        }
    }

    static boolean destinationReached(String tool, Map<String, Object> state) {
        if ("start_game".equals(tool) || "continue_game".equals(tool)) {
            return Boolean.TRUE.equals(state.get("in_game"));
        }
        if ("save_game".equals(tool)) {
            return Boolean.FALSE.equals(state.get("in_game")) &&
                "MAIN_MENU".equals(state.get("screen_type"));
        }
        if ("abandon_run".equals(tool)) {
            return Boolean.FALSE.equals(state.get("in_game")) &&
                Boolean.FALSE.equals(state.get("save_file_exists"));
        }
        return true;
    }

    private Map<String, Object> settledResponse(PendingToolCall call, Map<String, Object> after, boolean settled) {
        Map<String, Object> response = new HashMap<>();
        response.put("operation_id", call.operationId);
        response.put("status", settled ? "ready" : "unsettled");
        response.put("settled", settled);
        response.put("before", call.before);
        response.put("after", after);
        response.put("acknowledgement", call.acknowledgement);
        return response;
    }

    @SuppressWarnings("unchecked")
    private boolean enemyIntentChanged(Map<String, Object> before, Map<String, Object> after) {
        Object oldMonsters = before.get("monsters");
        Object newMonsters = after.get("monsters");
        if (!(oldMonsters instanceof List) || !(newMonsters instanceof List)) return false;
        Map<String, Object> oldIntents = new HashMap<>();
        for (Object value : (List<?>) oldMonsters) {
            Map<String, Object> monster = (Map<String, Object>) value;
            oldIntents.put(monster.get("id").toString(), intentSignature(monster));
        }
        for (Object value : (List<?>) newMonsters) {
            Map<String, Object> monster = (Map<String, Object>) value;
            String id = monster.get("id").toString();
            if (oldIntents.containsKey(id) && !Objects.equals(oldIntents.get(id), intentSignature(monster))) return true;
        }
        return false;
    }

    private String intentSignature(Map<String, Object> monster) {
        return String.valueOf(monster.get("intent")) + ":" + String.valueOf(monster.get("move"));
    }

    /**
     * Finish batch execution and send result.
     */
    private void finishBatchExecution() {
        if (pendingBatch == null) return;
        Map<String, Object> response = new HashMap<>();
        response.put("operation_id", activeCall.operationId);
        response.put("status", pendingBatch.errorMessage == null ? "ready" : "error");
        response.put("settled", pendingBatch.errorMessage == null);
        response.put("actions_requested", pendingBatch.actions.size());
        response.put("actions_completed", pendingBatch.successCount);
        response.put("actions", pendingBatch.actionResults);
        response.put("partial", pendingBatch.successCount < pendingBatch.actions.size());
        if (pendingBatch.screenChanged && pendingBatch.successCount < pendingBatch.actions.size()) {
            response.put("stop_reason", "screen_changed");
        }
        if (pendingBatch.stopReason != null) response.put("stop_reason", pendingBatch.stopReason);
        if (pendingBatch.errorMessage != null) {
            response.put("failed_index", pendingBatch.failedIndex + 1);
            response.put("failed_action", pendingBatch.failedAction);
            response.put("error", pendingBatch.errorMessage);
        }
        response.put("after", safeActionSummary());
        logger.info("Batch execution finished: " + pendingBatch.successCount + "/" + pendingBatch.actions.size());
        completeCall(activeCall, MCPProtocol.buildToolCallResultJson(response, pendingBatch.errorMessage != null));
        pendingBatch = null;
    }

    /**
     * Convert integer indices in select_cards drop/keep arrays to UUIDs for stable resolution.
     */
    private JsonObject convertSelectCardsIndices(JsonObject action, java.util.List<java.util.UUID> initialHandUuids)
            throws mcpthespire.InvalidCommandException {
        JsonObject result = new JsonObject();

        for (Map.Entry<String, com.google.gson.JsonElement> entry : action.entrySet()) {
            String key = entry.getKey();
            if (("drop".equals(key) || "keep".equals(key)) && entry.getValue().isJsonArray()) {
                // Convert indices to UUIDs in the array
                com.google.gson.JsonArray originalArray = entry.getValue().getAsJsonArray();
                com.google.gson.JsonArray convertedArray = new com.google.gson.JsonArray();

                for (com.google.gson.JsonElement elem : originalArray) {
                    if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                        // This is an index - convert to UUID
                        int idx = elem.getAsInt();
                        if (idx < 1 || idx > initialHandUuids.size()) {
                            throw new mcpthespire.InvalidCommandException(
                                "Card index " + idx + " out of bounds. Initial hand had " +
                                initialHandUuids.size() + " cards.");
                        }
                        // Convert to UUID string with special prefix so we can identify it later
                        convertedArray.add("uuid:" + initialHandUuids.get(idx - 1).toString());
                        logger.info("Converted card index " + idx + " to UUID: " + initialHandUuids.get(idx - 1));
                    } else {
                        // Keep strings as-is
                        convertedArray.add(elem);
                    }
                }
                result.add(key, convertedArray);
            } else {
                result.add(key, entry.getValue());
            }
        }

        return result;
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
        final String operationId = UUID.randomUUID().toString();
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
        Map<String, Object> before;
        Map<String, Object> acknowledgement;

        PendingToolCall(String toolName, JsonObject arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }

    /**
     * Represents a pending batch execution that spans multiple frames.
     */
    private static class PendingBatchExecution {
        final com.google.gson.JsonArray actions;
        int currentIndex;
        int successCount;
        int failedIndex;
        String failedAction;
        String errorMessage;
        boolean screenChanged;
        String stopReason;
        Map<String, Object> actionBefore;
        final List<Map<String, Object>> actionResults = new ArrayList<>();
        mcpthespire.ChoiceScreenUtils.ChoiceType initialScreen;
        boolean initialInDungeon;
        // Initial hand UUIDs for stable card_index resolution
        java.util.List<java.util.UUID> initialHandUuids;
        // Initial choice list for stable choice_index resolution
        java.util.List<String> initialChoiceList;

        PendingBatchExecution(com.google.gson.JsonArray actions) {
            this.actions = actions;
            this.currentIndex = 0;
            this.successCount = 0;
            this.failedIndex = -1;
            this.failedAction = null;
            this.errorMessage = null;
            this.screenChanged = false;
            this.initialInDungeon = mcpthespire.CommandExecutor.isInDungeon();
            if (this.initialInDungeon) {
                this.initialScreen = mcpthespire.ChoiceScreenUtils.getCurrentChoiceType();
                // Capture initial hand state for stable index resolution
                this.initialHandUuids = new java.util.ArrayList<>();
                if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.player != null) {
                    for (com.megacrit.cardcrawl.cards.AbstractCard card :
                         com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand.group) {
                        this.initialHandUuids.add(card.uuid);
                    }
                }
                // Capture initial choice list for stable index resolution
                this.initialChoiceList = new java.util.ArrayList<>(
                    mcpthespire.ChoiceScreenUtils.getCurrentChoiceList());
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
            if (initialInDungeon && mcpthespire.CommandExecutor.isInDungeon()) {
                mcpthespire.ChoiceScreenUtils.ChoiceType currentScreen = mcpthespire.ChoiceScreenUtils.getCurrentChoiceType();
                if (initialScreen != currentScreen) {
                    boolean nextIsHandSelection = currentIndex < actions.size() &&
                        currentScreen == mcpthespire.ChoiceScreenUtils.ChoiceType.HAND_SELECT &&
                        "select_cards".equals(actions.get(currentIndex).getAsJsonObject().get("action").getAsString());
                    screenChanged = !nextIsHandSelection;
                }
            }
        }
    }
}
