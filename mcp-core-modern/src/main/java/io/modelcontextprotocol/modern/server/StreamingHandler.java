/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.modern.spec.McpSchema;

/**
 * A request handler that may report progress before producing its response.
 * <p>
 * The handler is blocking: it must call {@link Sink#complete(Object)} exactly once before
 * returning, and may call {@link Sink#progress(McpSchema.ProgressNotification)} any
 * number of times before that. A handler that returns without completing fails the
 * request with an internal error.
 *
 * @param <REQUEST> the request type
 * @param <RESPONSE> the response type
 */
public interface StreamingHandler<REQUEST, RESPONSE> {

	void handle(McpRequestContext context, REQUEST request, Sink<RESPONSE> sink);

	/**
	 * A {@code tools/call} handler.
	 */
	public interface StreamingTool extends StreamingHandler<McpSchema.CallToolRequest, McpSchema.CallToolOutcome> {

	}

	/**
	 * A {@code resources/read} handler.
	 */
	public interface StreamingResource
			extends StreamingHandler<McpSchema.ReadResourceRequest, McpSchema.ReadResourceOutcome> {

	}

	/**
	 * A {@code prompts/get} handler.
	 */
	public interface StreamingPrompt extends StreamingHandler<McpSchema.GetPromptRequest, McpSchema.GetPromptOutcome> {

	}

}
