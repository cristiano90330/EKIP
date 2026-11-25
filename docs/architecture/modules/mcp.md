# MCP 工具入口

Java MCP 端点负责传输与 Key 认证，工具桥接复用 Agent 工具执行。

## 源码与契约入口

- MCP 传输、凭证与桥接 [mcp/](../../../knowledge-platform/src/main/java/com/pony/knowledge/mcp/)、[McpToolBridge.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/mcp/bridge/McpToolBridge.java)。
- 安全配置与共享工具 [security/](../../../knowledge-platform/src/main/java/com/pony/knowledge/security/)、[tool/](../../../knowledge-platform/src/main/java/com/pony/knowledge/agent/tool/)。
