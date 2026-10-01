/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.common;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link DefaultMcpTransportContext}.
 */
class DefaultMcpTransportContextTests {

	@Test
	void shouldReturnValueForKey() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

	@Test
	void shouldReturnNullForMissingKey() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(context.get("missing")).isNull();
	}

	@Test
	void emptyContextShouldHaveNoValues() {
		assertThat(McpTransportContext.EMPTY.get("tenant")).isNull();
		assertThat(McpTransportContext.EMPTY).isEqualTo(new DefaultMcpTransportContext(Map.of()));
	}

	@Test
	void shouldRejectNullMetadata() {
		assertThatIllegalArgumentException().isThrownBy(() -> new DefaultMcpTransportContext(null))
			.withMessage("metadata must not be null");
	}

	@Test
	void shouldRejectNullKey() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(null, "acme");

		assertThatNullPointerException().isThrownBy(() -> new DefaultMcpTransportContext(metadata));
	}

	@Test
	void shouldRejectNullValue() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", null);

		assertThatNullPointerException().isThrownBy(() -> new DefaultMcpTransportContext(metadata));
	}

	@Test
	void shouldNotSeeLaterWritesToTheSourceMap() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");

		McpTransportContext context = new DefaultMcpTransportContext(metadata);
		metadata.put("tenant", "other");
		metadata.put("added", "later");

		assertThat(context.get("tenant")).isEqualTo("acme");
		assertThat(context.get("added")).isNull();
	}

	@Test
	void shouldNotBeEmptiedByClearingTheSourceMap() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");

		McpTransportContext context = new DefaultMcpTransportContext(metadata);
		metadata.clear();

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

	@Test
	void contextsWithSameMetadataShouldBeEqual() {
		McpTransportContext first = new DefaultMcpTransportContext(Map.of("tenant", "acme"));
		McpTransportContext second = new DefaultMcpTransportContext(new HashMap<>(Map.of("tenant", "acme")));

		assertThat(first).isEqualTo(second);
		assertThat(first).hasSameHashCodeAs(second);
	}

	@Test
	void contextsWithDifferentMetadataShouldNotBeEqual() {
		McpTransportContext first = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(first).isNotEqualTo(new DefaultMcpTransportContext(Map.of("tenant", "other")));
		assertThat(first).isNotEqualTo(new DefaultMcpTransportContext(Map.of("user", "acme")));
		assertThat(first).isNotEqualTo(McpTransportContext.EMPTY);
	}

	@Test
	void shouldNotBeEqualToNullOrOtherTypes() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(context).isNotEqualTo(null);
		assertThat(context).isNotEqualTo(Map.of("tenant", "acme"));
		assertThat(context).isNotEqualTo(new ForeignContext());
	}

	@Test
	void shouldBeEqualToItself() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(context).isEqualTo(context);
	}

	@Test
	void shouldRemainUsableAsAMapKey() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");
		McpTransportContext context = new DefaultMcpTransportContext(metadata);

		Map<McpTransportContext, String> byContext = new HashMap<>();
		byContext.put(context, "value");
		metadata.put("tenant", "other");

		assertThat(byContext.get(context)).isEqualTo("value");
	}

	@Test
	void toStringShouldIncludeMetadata() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(context).hasToString("McpTransportContext{tenant=acme}");
	}

	@Test
	void putAllShouldContainValuesFromBothContexts() {
		McpTransportContext first = new DefaultMcpTransportContext(Map.of("tenant", "acme"));
		McpTransportContext second = new DefaultMcpTransportContext(Map.of("user", "alice"));

		McpTransportContext merged = first.putAll(second);

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("user")).isEqualTo("alice");
		assertThat(merged).isEqualTo(new DefaultMcpTransportContext(Map.of("tenant", "acme", "user", "alice")));
	}

	@Test
	void putAllShouldPreferValuesFromOther() {
		McpTransportContext first = new DefaultMcpTransportContext(Map.of("tenant", "acme", "user", "alice"));
		McpTransportContext second = new DefaultMcpTransportContext(Map.of("tenant", "other"));

		McpTransportContext merged = first.putAll(second);

		assertThat(merged.get("tenant")).isEqualTo("other");
		assertThat(merged.get("user")).isEqualTo("alice");
	}

	@Test
	void putAllShouldNotModifyEitherContext() {
		McpTransportContext first = new DefaultMcpTransportContext(Map.of("tenant", "acme"));
		McpTransportContext second = new DefaultMcpTransportContext(Map.of("tenant", "other", "user", "alice"));

		McpTransportContext merged = first.putAll(second);

		assertThat(merged).isNotSameAs(first).isNotSameAs(second);
		assertThat(first).isEqualTo(new DefaultMcpTransportContext(Map.of("tenant", "acme")));
		assertThat(second).isEqualTo(new DefaultMcpTransportContext(Map.of("tenant", "other", "user", "alice")));
	}

	@Test
	void putAllWithEmptyContextShouldReturnEqualContext() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThat(context.putAll(McpTransportContext.EMPTY)).isEqualTo(context);
		assertThat(McpTransportContext.EMPTY.putAll(context)).isEqualTo(context);
	}

	@Test
	void putAllShouldRejectNullContext() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThatIllegalArgumentException().isThrownBy(() -> context.putAll(null))
			.withMessage("other must not be null");
	}

	@Test
	void putAllShouldRejectOtherImplementations() {
		McpTransportContext context = new DefaultMcpTransportContext(Map.of("tenant", "acme"));

		assertThatIllegalArgumentException().isThrownBy(() -> context.putAll(new ForeignContext()))
			.withMessageContaining(ForeignContext.class.getName());
	}

	private static final class ForeignContext implements McpTransportContext {

		@Override
		public @Nullable Object get(String key) {
			return null;
		}

		@Override
		public McpTransportContext putAll(McpTransportContext other) {
			return this;
		}

	}

}
