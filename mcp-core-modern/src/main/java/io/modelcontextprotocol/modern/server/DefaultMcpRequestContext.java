/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Map;

import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.spec.McpSchema.Implementation;
import io.modelcontextprotocol.modern.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.modern.spec.McpSchema.RequestId;
import org.jspecify.annotations.Nullable;

record DefaultMcpRequestContext(RequestId requestId, String protocolVersion, ClientCapabilities clientCapabilities,
		@Nullable Implementation clientInfo, @Nullable LoggingLevel logLevel, @Nullable Object progressToken,
		Map<String, Object> meta, McpTransportContext transportContext) implements McpRequestContext {
}
