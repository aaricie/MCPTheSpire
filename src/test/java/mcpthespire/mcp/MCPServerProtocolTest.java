package mcpthespire.mcp;

import com.google.gson.JsonObject;
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

            Response tools = request(port, "POST", TOOLS_LIST, null);
            JsonObject toolsResult = new JsonParser().parse(tools.body).getAsJsonObject()
                .getAsJsonObject("result");
            require(tools.status == 200 && toolsResult.getAsJsonArray("tools").size() == 19,
                "tool discovery should remain available after GET requests");

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
            String status = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
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
