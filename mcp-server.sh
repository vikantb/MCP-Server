#!/bin/bash

# Log file for debugging (stderr goes to client terminal logs)
echo "Starting Bash MCP Server..." >&2

# Read JSON-RPC requests line by line from stdin
while read -r line; do
  [ -z "$line" ] && continue

  # Parse method and id using jq
  METHOD=$(echo "$line" | jq -r '.method // empty')
  ID=$(echo "$line" | jq -r '.id // empty')

  case "$METHOD" in
    "initialize")
      echo '{"jsonrpc":"2.0","id":'$ID',"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"oncall-orchestrator-bash","version":"1.0.0"}}}'
      ;;

    "tools/list")
      echo '{"jsonrpc":"2.0","id":'$ID',"result":{"tools":[
        {
          "name": "run_remote_command",
          "description": "Execute a bash command on a remote server via the deployed Spring Boot script execution service.",
          "inputSchema": {
            "type": "object",
            "properties": {
              "server_url": {"type": "string", "description": "The base URL of the remote Spring Boot execution service"},
              "command": {"type": "string", "description": "The bash command to run (e.g. dir, ipconfig)"}
            },
            "required": ["server_url", "command"]
          }
        },
        {
          "name": "interact_with_kafka",
          "description": "Send or consume messages from Kafka using the local Spring Boot utility service.",
          "inputSchema": {
            "type": "object",
            "properties": {
              "utility_url": {"type": "string", "description": "The local URL of the Kafka utility service"},
              "action": {"type": "string", "description": "Action to perform: consume or produce"},
              "topic": {"type": "string", "description": "The Kafka topic name"},
              "payload": {"type": "string", "description": "The JSON payload to send (if producing)"}
            },
            "required": ["utility_url", "action", "topic"]
          }
        },
        {
          "name": "generate_api_health_report",
          "description": "Runs the local bash script that executes CURLs against all service APIs and returns the report.",
          "inputSchema": {
            "type": "object",
            "properties": {
              "script_path": {"type": "string", "description": "The absolute path to the bash script to execute"}
            },
            "required": ["script_path"]
          }
        },
        {
          "name": "query_elasticsearch",
          "description": "Query the Elasticsearch cluster to search for logs.",
          "inputSchema": {
            "type": "object",
            "properties": {
              "es_url": {"type": "string", "description": "The URL of the Elasticsearch _search endpoint"},
              "query_json": {"type": "string", "description": "The Elasticsearch query DSL in JSON string format"}
            },
            "required": ["es_url", "query_json"]
          }
        }
      ]}}'
      ;;

    "tools/call")
      TOOL_NAME=$(echo "$line" | jq -r '.params.name')
      
      case "$TOOL_NAME" in
        "run_remote_command")
          SERVER_URL=$(echo "$line" | jq -r '.params.arguments.server_url')
          COMMAND=$(echo "$line" | jq -r '.params.arguments.command')

          # Local validation/sanitization rules
          CLEAN_CMD=$(echo "$COMMAND" | xargs) # trim
          FIRST_WORD=$(echo "$CLEAN_CMD" | cut -d' ' -f1 | tr '[:upper:]' '[:lower:]')
          
          # Forbidden patterns check: ;, &, |, >, <, `, $, newlines, carriage returns
          if [[ "$CLEAN_CMD" == *[';&|><`$']* ]]; then
            OUTPUT="Error: Command validation failed. Command rejected locally by MCP server sanitization rules (blocked control characters)."
          elif [[ ! " tail grep cat ls df free dir type findstr ipconfig echo " =~ " $FIRST_WORD " ]]; then
            OUTPUT="Error: Command validation failed. Command rejected locally by MCP server sanitization rules. Allowed binaries: tail, grep, cat, ls, df, free, dir, type, findstr, ipconfig, echo."
          else
            # Retrieve API Key from env, fallback to default
            API_KEY=${EXECUTION_API_KEY:-test-api-key}
            OUTPUT=$(curl -s -X POST "$SERVER_URL" \
              -H "Content-Type: text/plain" \
              -H "X-API-KEY: $API_KEY" \
              -d "$COMMAND")
          fi

          ESCAPED_OUTPUT=$(echo "$OUTPUT" | jq -Rsa .)
          echo "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"result\":{\"content\":[{\"type\":\"text\",\"text\":$ESCAPED_OUTPUT}]}}"
          ;;

        "interact_with_kafka")
          UTILITY_URL=$(echo "$line" | jq -r '.params.arguments.utility_url')
          ACTION=$(echo "$line" | jq -r '.params.arguments.action')
          TOPIC=$(echo "$line" | jq -r '.params.arguments.topic')
          PAYLOAD=$(echo "$line" | jq -r '.params.arguments.payload // "{}"')

          # Construct JSON payload safely
          REQ_BODY=$(jq -n \
            --arg act "$ACTION" \
            --arg top "$TOPIC" \
            --arg pay "$PAYLOAD" \
            '{action: $act, topic: $top, payload: $pay}')

          OUTPUT=$(curl -s -X POST "$UTILITY_URL" \
            -H "Content-Type: application/json" \
            -d "$REQ_BODY")

          ESCAPED_OUTPUT=$(echo "$OUTPUT" | jq -Rsa .)
          echo "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"result\":{\"content\":[{\"type\":\"text\",\"text\":$ESCAPED_OUTPUT}]}}"
          ;;

        "generate_api_health_report")
          SCRIPT_PATH=$(echo "$line" | jq -r '.params.arguments.script_path')
          
          OUTPUT=$(bash "$SCRIPT_PATH" 2>&1)

          ESCAPED_OUTPUT=$(echo "$OUTPUT" | jq -Rsa .)
          echo "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"result\":{\"content\":[{\"type\":\"text\",\"text\":$ESCAPED_OUTPUT}]}}"
          ;;

        "query_elasticsearch")
          ES_URL=$(echo "$line" | jq -r '.params.arguments.es_url')
          QUERY_JSON=$(echo "$line" | jq -r '.params.arguments.query_json')

          OUTPUT=$(curl -s -X POST "$ES_URL" \
            -H "Content-Type: application/json" \
            -d "$QUERY_JSON")

          ESCAPED_OUTPUT=$(echo "$OUTPUT" | jq -Rsa .)
          echo "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"result\":{\"content\":[{\"type\":\"text\",\"text\":$ESCAPED_OUTPUT}]}}"
          ;;
      esac
      ;;
  esac
done
