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

/**
 * Per-request information passed to every handler. There is no session: everything the
 * server knows about the client comes from the request's {@code _meta}, validated before
 * the handler is called.
 */
public interface McpRequestContext {

	/**
	 * The JSON-RPC id of the request.
	 * @return the request id
	 */
	RequestId requestId();

	/**
	 * The protocol version of the request, one the server supports.
	 * @return the protocol version
	 */
	String protocolVersion();

	/**
	 * The capabilities the client declared for this request. A handler must not rely on
	 * capabilities that are not declared here.
	 * @return the client capabilities
	 */
	ClientCapabilities clientCapabilities();

	/**
	 * The client's self-reported identity. Not verified: use it for display and logging
	 * only.
	 * @return the client info, {@code null} if the client did not send it
	 */
	@Nullable Implementation clientInfo();

	/**
	 * The minimum log level the client wants for this request.
	 * @return the log level, {@code null} if the client did not send it
	 */
	@Nullable LoggingLevel logLevel();

	/**
	 * The token to report progress with, see
	 * {@link Sink#progress(io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification)}.
	 * @return the progress token, a string or a {@link Long}; {@code null} if the client
	 * did not ask for progress
	 */
	@Nullable Object progressToken();

	/**
	 * The raw request {@code _meta}, including keys not modeled by the SDK.
	 * @return the request metadata
	 */
	Map<String, Object> meta();

	/**
	 * The transport-level metadata of the request, such as HTTP headers or the
	 * authenticated principal, as populated by the transport.
	 * @return the transport context, {@link McpTransportContext#EMPTY} if the transport
	 * provided none
	 */
	McpTransportContext transportContext();

}
