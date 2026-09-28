/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.spec;

import java.util.Objects;

import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCError;
import org.jspecify.annotations.Nullable;

/**
 * A protocol-level error, sent to the client as a JSON-RPC error response. Request
 * handlers throw it to reject a request, e.g. with
 * {@link McpSchema.ErrorCodes#INVALID_PARAMS}.
 * <p>
 * Tool execution errors are not protocol errors: report them in a
 * {@link McpSchema.CallToolResult} with {@code isError} set, so the model can see them.
 */
public class McpError extends RuntimeException {

	private final int code;

	private final @Nullable Object data;

	public McpError(int code, String message) {
		this(code, message, null);
	}

	public McpError(int code, String message, @Nullable Object data) {
		super(message);
		this.code = code;
		this.data = data;
	}

	public int getCode() {
		return this.code;
	}

	/**
	 * Never {@code null}: the message is required by every constructor.
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage());
	}

	public @Nullable Object getData() {
		return this.data;
	}

	public JSONRPCError toJsonRpcError() {
		return new JSONRPCError(this.code, getMessage(), this.data);
	}

}
