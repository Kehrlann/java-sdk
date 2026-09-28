/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.common;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Context associated with the transport layer. It allows to add transport-level metadata
 * for use further down the line. Specifically, it can be beneficial to extract HTTP
 * request metadata, such as headers or the authenticated principal, for use in MCP
 * feature implementations.
 * <p>
 * On the server side, the transport populates it from the incoming request and passes it
 * to the server with the request.
 */
public interface McpTransportContext {

	/**
	 * An empty, unmodifiable context.
	 */
	McpTransportContext EMPTY = new DefaultMcpTransportContext(Map.of());

	/**
	 * Create an unmodifiable context containing the given metadata.
	 * @param metadata the transport metadata
	 * @return the context containing the metadata
	 */
	static McpTransportContext create(Map<String, Object> metadata) {
		return new DefaultMcpTransportContext(metadata);
	}

	/**
	 * Extract a value from the context.
	 * @param key the key under which the data is expected
	 * @return the associated value or {@code null} if missing
	 */
	@Nullable Object get(String key);

}
