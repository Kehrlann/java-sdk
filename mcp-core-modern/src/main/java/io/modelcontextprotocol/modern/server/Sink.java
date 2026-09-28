/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.modern.spec.McpSchema;

/**
 * Where a {@link StreamingHandler} sends its progress and its response. Only usable while
 * the handler runs.
 *
 * @param <RESPONSE> the response type
 */
public interface Sink<RESPONSE> {

	/**
	 * Sends a {@code notifications/progress} on the response stream of the request.
	 * @param notification the progress, for the token of the request, see
	 * {@link McpRequestContext#progressToken()}, with a {@code progress} greater than the
	 * one of the previous notification
	 * @throws IllegalArgumentException if the request carried no progress token, or a
	 * different one, or if {@code progress} does not increase
	 * @throws IllegalStateException if the handler has completed
	 */
	void progress(McpSchema.ProgressNotification notification);

	/**
	 * Completes the request with its response.
	 * @param response the response
	 * @throws IllegalStateException if the handler has already completed
	 */
	void complete(RESPONSE response);

}
