package mcpthespire.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class MCPServerProtocolTest {
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"protocol-test\",\"version\":\"1.0\"}}}";
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }

        MCPServer server = new MCPServer("127.0.0.1", port);
        Thread serverThread = new Thread(server, "mcp-protocol-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
        try {
            awaitServer(port);

            Response initialize = request(port, "POST", INITIALIZE, null);
            require(initialize.status == 200, "initialize should return 200");
            require(initialize.sessionId == null, "stateless server should not issue a session ID");
            JsonObject initResult = new JsonParser().parse(initialize.body).getAsJsonObject()
                .getAsJsonObject("result");
            require("2025-03-26".equals(initResult.get("protocolVersion").getAsString()),
                "server should advertise the Streamable HTTP protocol version");

            Response initialized = request(port, "POST", INITIALIZED, null);
            require(initialized.status == 202 && initialized.body.isEmpty(),
                "notification should return 202 with no body");

            for (int i = 0; i < 8; i++) {
                require(request(port, "GET", null, null).status == 405,
                    "GET should return 405 when no SSE stream is offered");
            }

            require(initResult.has("instructions") &&
                initResult.get("instructions").getAsString().contains("1-based"),
                "server should send usage instructions once at initialize");

            Response tools = request(port, "POST", TOOLS_LIST, null);
            JsonObject toolsResult = new JsonParser().parse(tools.body).getAsJsonObject()
                .getAsJsonObject("result");
            JsonArray toolDefinitions = toolsResult.getAsJsonArray("tools");
            require(tools.status == 200 && toolDefinitions.size() == 9,
                "tool discovery should list the 9 consolidated tools");
            JsonObject actTool = null;
            java.util.Set<String> names = new java.util.HashSet<>();
            for (int i = 0; i < toolDefinitions.size(); i++) {
                JsonObject tool = toolDefinitions.get(i).getAsJsonObject();
                names.add(tool.get("name").getAsString());
                if ("act".equals(tool.get("name").getAsString())) actTool = tool;
                require(tool.has("annotations"), "every tool should carry behavior annotations");
            }
            require(names.containsAll(java.util.Arrays.asList("get_state", "act", "get_info",
                "get_profile_progress", "get_action_status", "start_game", "continue_game", "abandon_run", "save_game")),
                "all consolidated tools should be discoverable");
            require(actTool != null && "object".equals(actTool.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("actions")
                .getAsJsonObject("items").get("type").getAsString()),
                "act should declare its actions as objects");
            require(!tools.body.contains("\\u003d") && !tools.body.contains("\\u0027"),
                "responses should not HTML-escape characters");

            JsonArray encodedActions = new JsonParser().parse(
                "[\"{\\\"action\\\":\\\"play_card\\\",\\\"card_index\\\":1}\",{\"action\":\"end_turn\"}]").getAsJsonArray();
            JsonArray normalizedActions = MCPServer.normalizeBatchActions(encodedActions);
            require(normalizedActions.get(0).isJsonObject() && normalizedActions.get(1).isJsonObject(),
                "legacy JSON-string actions should be normalized to objects");
            JsonObject legacy = normalizedActions.get(0).getAsJsonObject();
            require("play".equals(legacy.get("action").getAsString()) && legacy.get("card").getAsInt() == 1,
                "legacy action and parameter names should map to the new ones");
            try {
                MCPServer.normalizeBatchActions(new JsonParser().parse("[42]").getAsJsonArray());
                throw new AssertionError("non-object actions should be rejected");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains("Action 1"), "validation should identify the bad action");
            }
            try {
                MCPServer.normalizeBatchActions(new JsonParser().parse("[{\"action\":\"fly\"}]").getAsJsonArray());
                throw new AssertionError("unknown actions should be rejected");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains("fly"), "validation should name the unknown action");
            }

            Response unknownOperation = request(port, "POST",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_action_status\",\"arguments\":{\"operation_id\":\"missing\"}}}", null);
            require(new JsonParser().parse(unknownOperation.body).getAsJsonObject()
                .getAsJsonObject("result").get("isError").getAsBoolean(),
                "unknown operation IDs should return a tool error");

            CompletableFuture<Response> stateCall = CompletableFuture.supplyAsync(() -> requestUnchecked(port,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_state\",\"arguments\":{}}}"));
            CompletableFuture<Response> infoCall = CompletableFuture.supplyAsync(() -> requestUnchecked(port,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_info\",\"arguments\":{\"names\":[\"Nonexistent Thing\"]}}}"));
            CompletableFuture<Response> progressCall = CompletableFuture.supplyAsync(() -> requestUnchecked(port,
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"get_profile_progress\",\"arguments\":{}}}"));
            long deadline = System.currentTimeMillis() + 1500;
            while ((!stateCall.isDone() || !infoCall.isDone() || !progressCall.isDone()) &&
                System.currentTimeMillis() < deadline) {
                if (server.hasPendingToolCalls()) server.processPendingToolCalls();
                Thread.sleep(5);
            }
            JsonObject stateResponse = new JsonParser().parse(stateCall.get(2, TimeUnit.SECONDS).body).getAsJsonObject();
            JsonObject infoResponse = new JsonParser().parse(infoCall.get(2, TimeUnit.SECONDS).body).getAsJsonObject();
            JsonObject progressResponse = new JsonParser().parse(progressCall.get(2, TimeUnit.SECONDS).body).getAsJsonObject();
            require(stateResponse.get("id").getAsInt() == 4 && resultText(stateResponse).startsWith("[MAIN_MENU]"),
                "state call should receive its own text result");
            require(infoResponse.get("id").getAsInt() == 5 && resultText(infoResponse).contains("not found"),
                "info call should receive its own result");
            require(progressResponse.get("id").getAsInt() == 6 && resultText(progressResponse).startsWith("Profile"),
                "profile call should receive its own result");

            mcpthespire.Snapshot stillInRun = new mcpthespire.Snapshot();
            stillInRun.inGame = true;
            stillInRun.screen = "COMBAT_REWARD";
            require(!MCPServer.destinationReached("save_game", stillInRun),
                "save must not settle during an intermediate dungeon screen");
            mcpthespire.Snapshot atMenu = new mcpthespire.Snapshot();
            atMenu.inGame = false;
            atMenu.screen = "MAIN_MENU";
            atMenu.saveExists = true;
            require(MCPServer.destinationReached("save_game", atMenu),
                "save should settle when the main menu is reached");
            require(!MCPServer.destinationReached("abandon_run", atMenu),
                "abandon should wait until the save is gone");

            require("Anger".equals(MCPToolHandler.choiceLabel("[Anger] Cost: 0 ATTACK")) &&
                "Shrug It Off".equals(MCPToolHandler.choiceLabel("[Shrug It Off] Cost: 1 SKILL (47 gold)")) &&
                "10 gold".equals(MCPToolHandler.choiceLabel("10(+0 bonus) gold")) &&
                "Blood Potion".equals(MCPToolHandler.choiceLabel("add potion: [Blood Potion] Heal for 20% of your Max HP.")) &&
                "Talk".equals(MCPToolHandler.choiceLabel("[Talk]")) &&
                "[Pray] Remove a card.".equals(MCPToolHandler.choiceLabel("[ Pray ] Remove a card.")) &&
                "(x=0, y=3) Unknown".equals(MCPToolHandler.choiceLabel("(x=0, y=3) Unknown")),
                "action log should label choices by name");

            require(requestWithOrigin(port, "http://example.com") == 403,
                "cross-origin requests should be rejected");
            require(requestWithOrigin(port, "http://127.0.0.1:" + port) == 200,
                "same-origin requests should be accepted");

            System.out.println("MCP Streamable HTTP protocol checks passed");
        } finally {
            server.stop();
            serverThread.interrupt();
            serverThread.join(2000);
        }
    }

    private static void awaitServer(int port) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                request(port, "GET", null, null);
                return;
            } catch (java.net.ConnectException e) {
                Thread.sleep(50);
            }
        }
        throw new AssertionError("MCP server did not start");
    }

    private static Response request(int port, String method, String body, String origin) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if (origin != null) connection.setRequestProperty("Origin", origin);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (input != null) {
            try (InputStream stream = input) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
            }
        }
        Response response = new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8),
            connection.getHeaderField("Mcp-Session-Id"));
        connection.disconnect();
        return response;
    }

    private static Response requestUnchecked(int port, String body) {
        try {
            return request(port, "POST", body, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String resultText(JsonObject response) {
        return response.getAsJsonObject("result").getAsJsonArray("content")
            .get(0).getAsJsonObject().get("text").getAsString();
    }

    private static int requestWithOrigin(int port, String origin) throws Exception {
        byte[] body = INITIALIZE.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            String headers = "POST /mcp HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + port + "\r\n" +
                "Origin: " + origin + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Accept: application/json, text/event-stream\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
            String status = reader.readLine();
            while (reader.readLine() != null) {
                // Consume the response before closing the socket.
            }
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static class Response {
        final int status;
        final String body;
        final String sessionId;

        Response(int status, String body, String sessionId) {
            this.status = status;
            this.body = body;
            this.sessionId = sessionId;
        }
    }
}
