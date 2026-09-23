/**
 * Copyright 2026 - 2026 the original author or authors.
 */
package io.modelcontextprotocol.util;

import java.util.function.Supplier;

/**
 * Kept for backwards compatibility, the implementation now lives in {@code mcp-json}.
 *
 * @param <S> the type of the supplier
 * @param <R> the type of the supplier result/returned value
 * @deprecated Use {@link io.modelcontextprotocol.json.McpServiceLoader}
 */
@Deprecated
public class McpServiceLoader<S extends Supplier<R>, R> extends io.modelcontextprotocol.json.McpServiceLoader<S, R> {

	public McpServiceLoader(Class<S> supplierType) {
		super(supplierType);
	}

}
