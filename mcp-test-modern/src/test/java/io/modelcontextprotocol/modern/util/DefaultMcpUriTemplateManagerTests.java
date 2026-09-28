/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.util;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link DefaultMcpUriTemplateManager}.
 */
class DefaultMcpUriTemplateManagerTests {

	@Test
	void shouldExtractVariableNamesFromTemplate() {
		assertThat(new DefaultMcpUriTemplateManager("/api/users/{userId}/posts/{postId}").getVariableNames())
			.containsExactly("userId", "postId");
	}

	@Test
	void shouldReturnEmptyListWhenTemplateHasNoVariables() {
		assertThat(new DefaultMcpUriTemplateManager("/api/users/all").getVariableNames()).isEmpty();
	}

	@Test
	void shouldRejectNullTemplate() {
		assertThatIllegalArgumentException().isThrownBy(() -> new DefaultMcpUriTemplateManager(null));
	}

	@Test
	void shouldRejectEmptyTemplate() {
		assertThatIllegalArgumentException().isThrownBy(() -> new DefaultMcpUriTemplateManager(""));
	}

	@Test
	void shouldThrowExceptionWhenTemplateContainsDuplicateVariables() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> new DefaultMcpUriTemplateManager("/api/users/{userId}/posts/{userId}").getVariableNames());
	}

	@Test
	void shouldExtractVariableValuesFromRequestUri() {
		assertThat(new DefaultMcpUriTemplateManager("/api/users/{userId}/posts/{postId}")
			.extractVariableValues("/api/users/123/posts/456")).isEqualTo(Map.of("userId", "123", "postId", "456"));
	}

	@Test
	void shouldReturnEmptyMapWhenTemplateHasNoVariables() {
		assertThat(new DefaultMcpUriTemplateManager("/api/users/all").extractVariableValues("/api/users/all"))
			.isEmpty();
	}

	@Test
	void shouldReturnEmptyMapWhenRequestUriIsNull() {
		assertThat(new DefaultMcpUriTemplateManager("/api/users/{userId}/posts/{postId}").extractVariableValues(null))
			.isEmpty();
	}

	@Test
	void shouldReturnEmptyMapWhenRequestUriDoesNotMatch() {
		assertThat(new DefaultMcpUriTemplateManager("/api/users/{userId}").extractVariableValues("/api/posts/123"))
			.isEmpty();
	}

	@Test
	void shouldMatchUriAgainstTemplatePattern() {
		DefaultMcpUriTemplateManager uriTemplateManager = new DefaultMcpUriTemplateManager(
				"/api/users/{userId}/posts/{postId}");

		assertThat(uriTemplateManager.matches("/api/users/123/posts/456")).isTrue();
		assertThat(uriTemplateManager.matches("/api/users/123/comments/456")).isFalse();
		assertThat(uriTemplateManager.matches("/api/users/1/2/posts/456")).isFalse();
		assertThat(uriTemplateManager.matches("/api/users//posts/456")).isFalse();
	}

	@Test
	void shouldMatchUriWithQueryParameters() {
		DefaultMcpUriTemplateManager uriTemplateManager = new DefaultMcpUriTemplateManager(
				"file://name/search?={search}");

		assertThat(uriTemplateManager.matches("file://name/search?=abcd")).isTrue();
	}

	@Test
	void templateWithoutVariablesMatchesIdenticalUriOnly() {
		DefaultMcpUriTemplateManager uriTemplateManager = new DefaultMcpUriTemplateManager("file:///a.txt");

		assertThat(uriTemplateManager.matches("file:///a.txt")).isTrue();
		assertThat(uriTemplateManager.matches("file:///a.txt2")).isFalse();
	}

}
