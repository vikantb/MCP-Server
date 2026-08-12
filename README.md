# OnCall HTTP-Orchestrator MCP Server

## 1. Problem Statement

On-call support engineers face significant friction when managing, debugging, and monitoring enterprise applications deployed across multiple secure Linux environments (e.g., SIT, Pre-Prod). This friction stems from several strict infrastructure and security constraints:

*   **Strict PAM (Privileged Access Management) Limitations:**
    *   Direct SSH access to application servers is prohibited or highly restricted.
    *   Engineers must authenticate through tools like Arcos, which enforce rigorous Multi-Factor Authentication (MFA) workflows (e.g., credentials -> push notification -> OTP).
    *   This authentication process is tedious, time-consuming, and hostile to automated scripts or persistent SSH tunnels.
*   **Segmented Observability Stack:**
    *   Service logs are pushed by Filebeat to Kafka topics.
    *   Logstash consumes from Kafka, parses the logs, and pushes them to Elasticsearch.
    *   Debugging issues often requires checking multiple disparate systems (Elasticsearch for logs, Kafka for pipeline health, and direct API endpoints for service health).
*   **Operational Bottlenecks:**
    *   Routine tasks—such as moving configuration files, rotating or debugging raw logs, updating application properties, and checking API health—require either enduring the MFA gauntlet to reach a remote shell or running manual curl commands locally.
*   **Local Toolchain Restrictions (The "Locked-Down Laptop"):**
    *   Engineers are operating on corporate-issued devices where installing new runtimes (like Node.js) or unauthorized binaries is strictly forbidden without lengthy upper-management approval processes. 
    *   However, Java (specifically the JDK used for Spring Boot development) is pre-approved and available on the engineer's machine.

### The Workarounds (The Baseline)
To survive these constraints, the engineer has already developed a suite of ingenious, isolated HTTP-based workarounds:
1.  **Remote Script Execution Service:** A custom Spring Boot service deployed on the remote Linux servers that accepts a bash command via an HTTP request body and executes it locally. This effectively acts as an HTTP-based remote shell.
2.  **Local Kafka Utility Service:** A local Spring Boot service that exposes HTTP endpoints to produce to or consume from the Kafka cluster.
3.  **Local Health Check Scripts:** A collection of `.http` files and bash scripts that execute a battery of `curl` commands against the remote service APIs to generate a health report.
4.  **Local Elasticsearch Access:** The ability to execute `curl` commands directly against the Elasticsearch cluster from the local network.

**The core problem:** While these workarounds bypass the MFA and SSH restrictions, they remain fragmented. The engineer (and any AI coding assistant helping the engineer) still has to manually orchestrate these disparate HTTP services, scripts, and queries to resolve incidents.

---

## 2. Purpose

The purpose of this project is to build an **Intelligent Orchestrator** using the **Model Context Protocol (MCP)** that unifies these fragmented workarounds into a single, cohesive interface that an AI agent (like Claude, Cursor, or Antigravity) can understand and utilize.

By building this MCP server in **Java**, we achieve the following goals:
1.  **Compliance:** We adhere entirely to the local machine's toolchain restrictions by using the pre-approved Java runtime, eliminating the need to request permission to install Node.js or Python.
2.  **AI Empowerment:** We expose the engineer's HTTP workarounds as semantic **MCP Tools**. Instead of the engineer manually running a curl command to check a log, the AI agent can intelligently decide to call the `run_remote_command` tool or the `query_elasticsearch` tool based on natural language requests (e.g., "Check the SIT logs for OutOfMemory errors").
3.  **Frictionless Operations:** We completely abstract away the Arcos MFA barrier. The AI agent can manage files, update properties, and debug logs via the HTTP-based Remote Script Execution Service without ever initiating an SSH connection.

### Exposed MCP Tools
*   `run_remote_command`: Proxies bash commands to the remote Script Execution Service.
*   `interact_with_kafka`: Interfaces with the local Kafka utility to consume/produce messages.
*   `generate_api_health_report`: Executes the local bash health-check scripts and parses the output.
*   `query_elasticsearch`: Constructs and sends JSON queries directly to the Elasticsearch API.

---

## 3. Scope of Improvement

While the current architecture successfully circumvents the immediate environmental restrictions, there are several areas where this MCP server and the broader ecosystem can be improved and hardened:

### A. Security & Hardening
*   **Authentication/Authorization for the Backdoor:** The Remote Script Execution Service deployed on the Linux servers is essentially an unauthenticated remote shell. This represents a significant security risk. 
    *   *Improvement:* Implement Mutual TLS (mTLS), JWT validation, or API key authentication between the local MCP server and the remote execution service to ensure only authorized endpoints can execute commands.
*   **Command Sanitization:** Currently, the MCP server passes raw commands to the remote service. 
    *   *Improvement:* The remote execution service (or the MCP server) should enforce an "allowlist" of permitted commands (e.g., `tail`, `cat`, `grep`, `mv`) and strictly block destructive commands (e.g., `rm -rf`, `reboot`, `chmod`).

### B. MCP Server Enhancements (Java Implementation)
*   **Dynamic Tool Discovery:** Instead of hardcoding the URLs for the Elasticsearch cluster or the remote execution services, the MCP server could read from a local `.env` or `application.yml` file, allowing it to easily switch between SIT and Pre-Prod environments.
*   **Resource Exposure:** In addition to Tools, the MCP server could expose static data as **MCP Resources**. For example, exposing the known architecture diagrams, runbooks, or static configuration files (`resource://runbooks/sit-outage.md`), allowing the AI to read the runbook before taking action.
*   **Robust Error Handling:** Enhance the Java `HttpClient` implementation to better handle timeouts, 5xx server errors, and network partitions, returning clean, actionable error messages to the AI agent rather than raw stack traces.

### C. Observability Integration
*   **Log Parsing:** The `query_elasticsearch` tool currently returns raw JSON. 
    *   *Improvement:* The MCP server could parse and format the Elasticsearch hits into a more token-efficient, human-readable string before passing it back to the AI context, saving context window space.
*   **Metrics Extraction:** If the Spring Boot APIs expose Prometheus endpoints (`/actuator/prometheus`), a new tool could be added to fetch and parse these metrics to evaluate memory usage or thread starvation dynamically.
