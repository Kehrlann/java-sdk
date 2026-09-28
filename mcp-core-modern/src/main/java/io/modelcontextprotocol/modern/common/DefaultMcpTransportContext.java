/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.common;

import java.util.Map;

import io.modelcontextprotocol.modern.util.Assert;
import org.jspecify.annotations.Nullable;

/**
 * Default implementation for {@link McpTransportContext} which uses a map as storage.
 */
final class DefaultMcpTransportContext implements McpTransportContext {

	private final Map<String, Object> metadata;

	DefaultMcpTransportContext(Map<String, Object> metadata) {
		Assert.notNull(metadata, "metadata must not be null");
		this.metadata = Map.copyOf(metadata);
	}

	@Override
	public @Nullable Object get(String key) {
		return this.metadata.get(key);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		DefaultMcpTransportContext that = (DefaultMcpTransportContext) o;
		return this.metadata.equals(that.metadata);
	}

	@Override
	public int hashCode() {
		return this.metadata.hashCode();
	}

	@Override
	public String toString() {
		return "McpTransportContext" + this.metadata;
	}

}
