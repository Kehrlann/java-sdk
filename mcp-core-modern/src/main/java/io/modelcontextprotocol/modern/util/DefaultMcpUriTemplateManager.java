/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Matches URIs against a URI template, and extracts the values of its variables.
 * <p>
 * Supports simple {@code {variableName}} expressions, each matching a single path
 * segment: a non-empty value without {@code /}. Other text in the template is matched
 * literally.
 */
public final class DefaultMcpUriTemplateManager {

	/**
	 * Pattern to match URI variables in the format {variableName}.
	 */
	private static final Pattern URI_VARIABLE_PATTERN = Pattern.compile("\\{([^/]+?)\\}");

	private final String uriTemplate;

	/**
	 * The template as a regex with one capturing group per variable, {@code null} if the
	 * template has no variables.
	 */
	private final @Nullable Pattern pattern;

	/**
	 * Constructor for DefaultMcpUriTemplateManager.
	 * @param uriTemplate The URI template to be used for variable extraction
	 * @throws IllegalArgumentException if the URI template is null or empty
	 */
	public DefaultMcpUriTemplateManager(String uriTemplate) {
		Assert.hasText(uriTemplate, "URI template must not be null or empty");
		this.uriTemplate = uriTemplate;
		this.pattern = isUriTemplate(uriTemplate) ? toPattern(uriTemplate) : null;
	}

	/**
	 * Converts the URI template into a regex, escaping the literal parts so that
	 * characters like {@code ?} are matched as is.
	 */
	private static Pattern toPattern(String uriTemplate) {
		StringBuilder patternBuilder = new StringBuilder("^");
		Matcher variableMatcher = URI_VARIABLE_PATTERN.matcher(uriTemplate);
		int lastEnd = 0;
		while (variableMatcher.find()) {
			patternBuilder.append(Pattern.quote(uriTemplate.substring(lastEnd, variableMatcher.start())));
			patternBuilder.append("([^/]+)");
			lastEnd = variableMatcher.end();
		}
		if (lastEnd < uriTemplate.length()) {
			patternBuilder.append(Pattern.quote(uriTemplate.substring(lastEnd)));
		}
		patternBuilder.append("$");
		return Pattern.compile(patternBuilder.toString());
	}

	/**
	 * Extract URI variable names from this URI template.
	 * @return A list of variable names extracted from the template
	 * @throws IllegalArgumentException if duplicate variable names are found
	 */
	public List<String> getVariableNames() {
		List<String> variables = new ArrayList<>();
		Matcher matcher = URI_VARIABLE_PATTERN.matcher(this.uriTemplate);
		while (matcher.find()) {
			String variableName = matcher.group(1);
			if (variables.contains(variableName)) {
				throw new IllegalArgumentException("Duplicate URI variable name in template: " + variableName);
			}
			variables.add(variableName);
		}
		return variables;
	}

	/**
	 * Extract URI variable values from the actual request URI.
	 * @param requestUri The actual URI from the request
	 * @return A map of variable names to their values, empty if the URI is {@code null},
	 * empty, or does not match the template
	 */
	public Map<String, String> extractVariableValues(@Nullable String requestUri) {
		Map<String, String> variableValues = new HashMap<>();
		List<String> uriVariables = getVariableNames();
		if (this.pattern == null || requestUri == null || requestUri.isEmpty()) {
			return variableValues;
		}
		Matcher matcher = this.pattern.matcher(requestUri);
		if (matcher.matches()) {
			for (int i = 0; i < uriVariables.size(); i++) {
				variableValues.put(uriVariables.get(i), matcher.group(i + 1));
			}
		}
		return variableValues;
	}

	/**
	 * Check if a URI matches the uriTemplate with variables. A template without variables
	 * only matches the identical URI.
	 * @param uri The URI to check
	 * @return true if the URI matches the pattern, false otherwise
	 */
	public boolean matches(String uri) {
		if (this.pattern == null) {
			return uri.equals(this.uriTemplate);
		}
		return this.pattern.matcher(uri).matches();
	}

	/**
	 * Check if the given URI is a URI template.
	 * @param uri the URI to check
	 * @return Returns true if the URI contains variables in the format {variableName}
	 */
	public boolean isUriTemplate(String uri) {
		return URI_VARIABLE_PATTERN.matcher(uri).find();
	}

}
