package com.oncall.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.stream.Collectors;

public class McpServer {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String API_KEY = System.getenv("EXECUTION_API_KEY") != null ? System.getenv("EXECUTION_API_KEY") : "test-api-key";

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            try {
                JsonNode request = mapper.readTree(line);
                handleRequest(request);
            } catch (Exception e) {
                System.err.println("Failed to parse or handle request: " + e.getMessage());
            }
        }
    }

    private static void handleRequest(JsonNode request) throws Exception {
        String method = request.has("method") ? request.get("method").asText() : null;
        JsonNode id = request.get("id");

        if (method == null) return;

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }

        switch (method) {
            case "initialize":
                ObjectNode result = mapper.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                
                ObjectNode capabilities = mapper.createObjectNode();
                capabilities.set("tools", mapper.createObjectNode());
                result.set("capabilities", capabilities);

                ObjectNode serverInfo = mapper.createObjectNode();
                serverInfo.put("name", "oncall-orchestrator");
                serverInfo.put("version", "1.0.0");
                result.set("serverInfo", serverInfo);
                
                response.set("result", result);
                send(response);
                break;
            
            case "notifications/initialized":
                break;
                
            case "tools/list":
                ObjectNode toolsResult = mapper.createObjectNode();
                ArrayNode toolsList = mapper.createArrayNode();
                
                // Tool 1: Remote Command
                ObjectNode remoteCmd = mapper.createObjectNode();
                remoteCmd.put("name", "run_remote_command");
                remoteCmd.put("description", "Execute a bash command on a remote server via the deployed Spring Boot script execution service.");
                ObjectNode remoteCmdSchema = mapper.createObjectNode();
                remoteCmdSchema.put("type", "object");
                ObjectNode remoteCmdProps = mapper.createObjectNode();
                remoteCmdProps.set("server_url", createStringSchema("The base URL of the remote Spring Boot execution service"));
                remoteCmdProps.set("command", createStringSchema("The bash command to run (e.g. 'tail -n 100 app.log')"));
                remoteCmdSchema.set("properties", remoteCmdProps);
                remoteCmdSchema.set("required", mapper.createArrayNode().add("server_url").add("command"));
                remoteCmd.set("inputSchema", remoteCmdSchema);
                toolsList.add(remoteCmd);

                // Tool 2: Kafka
                ObjectNode kafka = mapper.createObjectNode();
                kafka.put("name", "interact_with_kafka");
                kafka.put("description", "Send or consume messages from Kafka using the local Spring Boot utility service.");
                ObjectNode kafkaSchema = mapper.createObjectNode();
                kafkaSchema.put("type", "object");
                ObjectNode kafkaProps = mapper.createObjectNode();
                kafkaProps.set("utility_url", createStringSchema("The local URL of the Kafka utility service"));
                kafkaProps.set("action", createStringSchema("Action to perform: 'consume' or 'produce'"));
                kafkaProps.set("topic", createStringSchema("The Kafka topic name"));
                kafkaProps.set("payload", createStringSchema("The JSON payload to send (if producing)"));
                kafkaSchema.set("properties", kafkaProps);
                kafkaSchema.set("required", mapper.createArrayNode().add("utility_url").add("action").add("topic"));
                kafka.set("inputSchema", kafkaSchema);
                toolsList.add(kafka);

                // Tool 3: API Health
                ObjectNode apiHealth = mapper.createObjectNode();
                apiHealth.put("name", "generate_api_health_report");
                apiHealth.put("description", "Runs the local bash script that executes CURLs against all service APIs and returns the report.");
                ObjectNode apiHealthSchema = mapper.createObjectNode();
                apiHealthSchema.put("type", "object");
                ObjectNode apiHealthProps = mapper.createObjectNode();
                apiHealthProps.set("script_path", createStringSchema("The absolute path to the bash script to execute"));
                apiHealthSchema.set("properties", apiHealthProps);
                apiHealthSchema.set("required", mapper.createArrayNode().add("script_path"));
                apiHealth.set("inputSchema", apiHealthSchema);
                toolsList.add(apiHealth);

                // Tool 4: Elasticsearch
                ObjectNode elastic = mapper.createObjectNode();
                elastic.put("name", "query_elasticsearch");
                elastic.put("description", "Query the Elasticsearch cluster to search for logs.");
                ObjectNode elasticSchema = mapper.createObjectNode();
                elasticSchema.put("type", "object");
                ObjectNode elasticProps = mapper.createObjectNode();
                elasticProps.set("es_url", createStringSchema("The URL of the Elasticsearch _search endpoint"));
                elasticProps.set("query_json", createStringSchema("The Elasticsearch query DSL in JSON string format"));
                elasticSchema.set("properties", elasticProps);
                elasticSchema.set("required", mapper.createArrayNode().add("es_url").add("query_json"));
                elastic.set("inputSchema", elasticSchema);
                toolsList.add(elastic);
                
                toolsResult.set("tools", toolsList);
                response.set("result", toolsResult);
                send(response);
                break;

            case "tools/call":
                JsonNode params = request.get("params");
                String toolName = params.get("name").asText();
                JsonNode args = params.get("arguments");

                String toolOutput = "";
                try {
                    switch (toolName) {
                        case "run_remote_command":
                            toolOutput = handleRemoteCommand(args);
                            break;
                        case "interact_with_kafka":
                            toolOutput = handleKafka(args);
                            break;
                        case "generate_api_health_report":
                            toolOutput = handleApiHealth(args);
                            break;
                        case "query_elasticsearch":
                            toolOutput = handleElasticsearch(args);
                            break;
                        default:
                            throw new Exception("Unknown tool: " + toolName);
                    }
                } catch (Exception e) {
                    toolOutput = "Error executing tool: " + e.getMessage();
                }

                ObjectNode callResult = mapper.createObjectNode();
                ArrayNode contentArray = mapper.createArrayNode();
                ObjectNode contentObj = mapper.createObjectNode();
                contentObj.put("type", "text");
                contentObj.put("text", toolOutput);
                contentArray.add(contentObj);
                
                callResult.set("content", contentArray);
                response.set("result", callResult);
                send(response);
                break;

            default:
                if (id != null) {
                    ObjectNode error = mapper.createObjectNode();
                    error.put("code", -32601);
                    error.put("message", "Method not found: " + method);
                    response.set("error", error);
                    send(response);
                }
                break;
        }
    }

    private static ObjectNode createStringSchema(String description) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private static void send(JsonNode response) throws Exception {
        String json = mapper.writeValueAsString(response);
        System.out.println(json);
        System.out.flush();
    }

    // Tool Implementations

    private static String handleRemoteCommand(JsonNode args) throws Exception {
        String url = args.get("server_url").asText();
        String command = args.get("command").asText();
        
        if (!isCommandSafe(command)) {
            return "Error: Command validation failed. Command rejected locally by MCP server sanitization rules. Only tail, grep, cat, ls, df, free, dir, type, findstr, ipconfig, echo are allowed without control characters.";
        }
        
        // Assuming the remote service expects the command as plain text in the body
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "text/plain")
                .header("X-API-KEY", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(command))
                .build();
                
        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return "Status: " + httpResponse.statusCode() + "\nOutput:\n" + httpResponse.body();
    }

    private static boolean isCommandSafe(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        String[] forbiddenPatterns = { ";", "&", "|", ">", "<", "`", "$", "\n", "\r" };
        for (String pattern : forbiddenPatterns) {
            if (trimmed.contains(pattern)) {
                return false;
            }
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        String binary = parts[0].toLowerCase();
        java.util.Set<String> allowed = java.util.Set.of("tail", "grep", "cat", "ls", "df", "free", "dir", "type", "findstr", "ipconfig", "echo");
        return allowed.contains(binary);
    }

    private static String handleKafka(JsonNode args) throws Exception {
        String url = args.get("utility_url").asText();
        String action = args.get("action").asText();
        String topic = args.get("topic").asText();
        String payload = args.has("payload") ? args.get("payload").asText() : "{}";
        
        ObjectNode reqBody = mapper.createObjectNode();
        reqBody.put("action", action);
        reqBody.put("topic", topic);
        reqBody.put("payload", payload);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
                .build();
                
        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return "Status: " + httpResponse.statusCode() + "\nOutput:\n" + httpResponse.body();
    }

    private static String handleApiHealth(JsonNode args) throws Exception {
        String scriptPath = args.get("script_path").asText();
        
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath);
        Process p = pb.start();
        
        String output = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
        String error = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
                
        p.waitFor();
        return output + (error.isEmpty() ? "" : "\nErrors:\n" + error);
    }

    private static String handleElasticsearch(JsonNode args) throws Exception {
        String url = args.get("es_url").asText();
        String queryJson = args.get("query_json").asText();
        
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(queryJson))
                .build();
                
        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return "Status: " + httpResponse.statusCode() + "\nOutput:\n" + httpResponse.body();
    }
}
