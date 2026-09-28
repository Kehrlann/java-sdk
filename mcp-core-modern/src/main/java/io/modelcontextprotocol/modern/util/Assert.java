/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.util;

import org.jspecify.annotations.Nullable;

/**
 * Assertion utility class that assists in validating arguments.
 */
public final class Assert {

	private Assert() {
	}

	/**
	 * Assert that an object is not {@code null}.
	 * @param object the object to check
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if the object is {@code null}
	 */
	public static void notNull(@Nullable Object object, String message) {
		if (object == null) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * Assert that the given String contains valid text content; that is, it must not be
	 * {@code null} and must contain at least one non-whitespace character.
	 * @param text the String to check
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if the text does not contain valid text content
	 */
	public static void hasText(@Nullable String text, String message) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * Assert a boolean expression, throwing an {@code IllegalArgumentException} if the
	 * expression evaluates to {@code false}.
	 * @param expression a boolean expression
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if {@code expression} is {@code false}
	 */
	public static void isTrue(boolean expression, String message) {
		if (!expression) {
			throw new IllegalArgumentException(message);
		}
	}

}
