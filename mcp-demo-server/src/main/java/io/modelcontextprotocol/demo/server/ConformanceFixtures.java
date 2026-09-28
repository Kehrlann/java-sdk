/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.demo.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpServerFeatures.CompletionSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.PromptSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ResourceSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ResourceTemplateSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.StreamingToolSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ToolSpecification;
import io.modelcontextprotocol.modern.spec.McpError;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.modern.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.Prompt;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.spec.McpSchema.Resource;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.spec.McpSchema.Role;
import io.modelcontextprotocol.modern.spec.McpSchema.TextContent;
import io.modelcontextprotocol.modern.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import io.modelcontextprotocol.modern.util.DefaultMcpUriTemplateManager;
import jakarta.servlet.http.HttpServletRequest;

import static io.modelcontextprotocol.demo.server.DemoServer.objectSchema;

/**
 * The tools, resources, prompts and completions the
 * <a href="https://github.com/modelcontextprotocol/conformance">MCP conformance suite</a>
 * expects of a server, mirroring its TypeScript "everything server". They are registered
 * when the {@code MCP_CONFORMANCE} environment variable is {@code true}:
 *
 * <pre>
 * MCP_CONFORMANCE=true java -jar mcp-demo-server/target/mcp-demo-server-2.1.0-SNAPSHOT.jar
 * npm run start server -- --url http://localhost:8080/mcp --requirements 2026-07-28
 * </pre>
 *
 * Tasks are not supported.
 */
final class ConformanceFixtures {

	private static final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	private static final TypeRef<Map<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	/** A 1x1 red PNG pixel. */
	private static final String TEST_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

	/** A minimal WAV file. */
	private static final String TEST_AUDIO_BASE64 = "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQIAAAA=";

	/**
	 * The key of the transport context entry holding the {@code Mcp-Param-*} headers of a
	 * request, by lower-cased name suffix.
	 */
	private static final String PARAM_HEADERS = "mcp-param-headers";

	private static final String PARAM_HEADER_PREFIX = "mcp-param-";

	private static final String BASE64_PREFIX = "=?base64?";

	private static final String BASE64_SUFFIX = "?=";

	/** Canonical, padded, Base64: {@link Base64.Decoder} accepts missing padding. */
	private static final Pattern BASE64 = Pattern
		.compile("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");

	/** Signs {@code requestState}, which the client echoes back verbatim. */
	private static final byte[] STATE_KEY = randomKey();

	private static final Map<String, Object> NAME_SCHEMA = objectSchema(Map.of("name", Map.of("type", "string")),
			"name");

	private static final Map<String, Object> OK_SCHEMA = objectSchema(Map.of("ok", Map.of("type", "boolean")), "ok");

	private static final Map<String, Object> ROOTS_REQUEST = Map.of("method", "roots/list", "params", Map.of());

	private ConformanceFixtures() {
	}

	static McpServer.Builder register(McpServer.Builder builder) {
		return builder
			.tools(simpleText(), imageContent(), audioContent(), embeddedResource(), multipleContentTypes(),
					errorHandling(), jsonSchema202012(), missingCapability(), loggingTool(), customHeaders())
			.tools(inputRequiredElicitation(), inputRequiredSampling(), inputRequiredListRoots(),
					inputRequiredRequestState(), inputRequiredMultipleInputs(), inputRequiredMultiRound(),
					inputRequiredTamperedState(), inputRequiredCapabilities())
			.streamingTools(toolWithProgress(), streamingElicitation())
			.resources(staticText(), staticBinary())
			.resourceTemplates(template())
			.prompts(simplePrompt(), promptWithArguments(), promptWithEmbeddedResource(), promptWithImage(),
					inputRequiredPrompt())
			.completions(new CompletionSpecification(new PromptReference("test_prompt_with_arguments"),
					(context, request) -> DemoServer.complete(request, "arg1", List.of("paris", "park", "party"))));
	}

	/**
	 * Exposes the {@code Mcp-Param-*} headers of a request to handlers, as the transport
	 * does not validate them against the tool arguments yet, see
	 * {@link #checkParamHeaders}.
	 */
	static McpTransportContext transportContext(HttpServletRequest request) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			String lowerCaseName = name.toLowerCase(Locale.ROOT);
			if (lowerCaseName.startsWith(PARAM_HEADER_PREFIX)) {
				headers.put(lowerCaseName.substring(PARAM_HEADER_PREFIX.length()), request.getHeader(name));
			}
		}
		return McpTransportContext.create(Map.of(PARAM_HEADERS, headers));
	}

	// ---------------------------
	// Tools: content types
	// ---------------------------

	private static ToolSpecification simpleText() {
		return textTool("test_simple_text", "Tests simple text content response",
				"This is a simple text response for testing.");
	}

	private static ToolSpecification imageContent() {
		return tool("test_image_content", "Tests image content response",
				(context, request) -> CallToolResult.builder()
					.addContent(new ImageContent(TEST_IMAGE_BASE64, "image/png"))
					.build());
	}

	private static ToolSpecification audioContent() {
		return tool("test_audio_content", "Tests audio content response",
				(context, request) -> CallToolResult.builder()
					.addContent(new AudioContent(TEST_AUDIO_BASE64, "audio/wav"))
					.build());
	}

	private static ToolSpecification embeddedResource() {
		return tool("test_embedded_resource", "Tests embedded resource content response",
				(context, request) -> CallToolResult.builder()
					.addContent(new EmbeddedResource(new TextResourceContents("test://embedded-resource", "text/plain",
							"This is an embedded resource content.")))
					.build());
	}

	private static ToolSpecification multipleContentTypes() {
		return tool("test_multiple_content_types", "Tests response with multiple content types (text, image, resource)",
				(context, request) -> CallToolResult.builder()
					.addTextContent("Multiple content types test:")
					.addContent(new ImageContent(TEST_IMAGE_BASE64, "image/png"))
					.addContent(new EmbeddedResource(new TextResourceContents("test://mixed-content-resource",
							"application/json", DemoServer.toJson(Map.of("test", "data", "value", 123)))))
					.build());
	}

	/**
	 * A tool execution error is reported in the result, for the model to see, not as a
	 * JSON-RPC error.
	 */
	private static ToolSpecification errorHandling() {
		return tool("test_error_handling", "Tests error response handling",
				(context, request) -> CallToolResult.builder()
					.addTextContent("This tool intentionally returns an error for testing")
					.isError(true)
					.build());
	}

	/**
	 * The input schema uses JSON Schema 2020-12 keywords, which must be listed as is.
	 */
	private static ToolSpecification jsonSchema202012() {
		Tool tool = Tool.builder("json_schema_2020_12_tool")
			.description("Tool with JSON Schema 2020-12 features for conformance testing (SEP-1613)")
			.inputSchema(parseJson("""
					{
					  "$schema": "https://json-schema.org/draft/2020-12/schema",
					  "type": "object",
					  "$defs": {
					    "address": {
					      "$anchor": "addressDef",
					      "type": "object",
					      "properties": {
					        "street": { "type": "string" },
					        "city": { "type": "string" }
					      }
					    }
					  },
					  "properties": {
					    "name": { "type": "string" },
					    "address": { "$ref": "#/$defs/address" },
					    "contactMethod": { "type": "string", "enum": ["phone", "email"] },
					    "phone": { "type": "string" },
					    "email": { "type": "string" }
					  },
					  "allOf": [{ "anyOf": [{ "required": ["phone"] }, { "required": ["email"] }] }],
					  "if": {
					    "properties": { "contactMethod": { "const": "phone" } },
					    "required": ["contactMethod"]
					  },
					  "then": { "required": ["phone"] },
					  "else": { "required": ["email"] },
					  "additionalProperties": false
					}
					"""))
			.build();
		return new ToolSpecification(tool,
				(context, request) -> CallToolResult.builder()
					.addTextContent("JSON Schema 2020-12 tool called with: " + DemoServer.toJson(request.arguments()))
					.build());
	}

	// ---------------------------
	// Tools: per-request client metadata
	// ---------------------------

	/**
	 * Stands for a tool that needs sampling: the server must not rely on a capability the
	 * client did not declare in the request.
	 */
	private static ToolSpecification missingCapability() {
		return tool("test_missing_capability", "Test tool requiring sampling", (context, request) -> {
			requireCapability(context.clientCapabilities().sampling() != null, "sampling");
			return CallToolResult.builder().addTextContent("Success").build();
		});
	}

	/**
	 * Servers must not log for a request that did not set a log level. Handlers have no
	 * way to send {@code notifications/message}, so this tool never logs.
	 */
	private static ToolSpecification loggingTool() {
		return textTool("test_logging_tool", "Diagnostic logging validator tool", "Logging evaluated");
	}

	/**
	 * Reports progress, when the client sent a progress token, and returns the token.
	 */
	private static StreamingToolSpecification toolWithProgress() {
		Tool tool = Tool.builder("test_tool_with_progress")
			.description("Tests tool that reports progress notifications")
			.build();
		return new StreamingToolSpecification(tool, (context, request, sink) -> {
			for (int progress = 0; progress <= 100; progress += 50) {
				if (context.progressToken() != null) {
					sink.progress(ProgressNotification.builder(context.progressToken(), (double) progress)
						.total(100.0)
						.message("Completed step " + progress + " of 100")
						.build());
				}
				DemoServer.sleep(Duration.ofMillis(50));
			}
			sink.complete(CallToolResult.builder().addTextContent(String.valueOf(context.progressToken())).build());
		});
	}

	/**
	 * The response stream of a request only carries notifications and the response: to
	 * elicit, the tool returns an {@link InputRequiredResult} instead of sending an
	 * {@code elicitation/create} request on the stream.
	 */
	private static StreamingToolSpecification streamingElicitation() {
		Tool tool = Tool.builder("test_streaming_elicitation")
			.description("Diagnostic tool validating response progress streams")
			.build();
		return new StreamingToolSpecification(tool, (context, request, sink) -> {
			if (context.progressToken() != null) {
				sink.progress(ProgressNotification.builder(context.progressToken(), 50.0).total(100.0).build());
			}
			if (request.inputResponses() != null && request.inputResponses().containsKey("user_name")) {
				sink.complete(CallToolResult.builder().addTextContent("Streaming complete").build());
				return;
			}
			sink.complete(InputRequiredResult.builder()
				.addInputRequest("user_name", ElicitRequest.form("What is your name?", NAME_SCHEMA))
				.build());
		});
	}

	// ---------------------------
	// Tools: custom headers
	// ---------------------------

	/**
	 * A tool with a parameter mirrored in the {@code Mcp-Param-Region} header, so that
	 * intermediaries can route on it without parsing the body.
	 */
	private static ToolSpecification customHeaders() {
		Tool tool = Tool.builder("test_custom_headers")
			.description("Tests Mcp-Param-* custom headers (SEP-2243)")
			.inputSchema(objectSchema(Map.of("region", Map.of("type", "string", "x-mcp-header", "Region")), "region"))
			.build();
		return new ToolSpecification(tool, (context, request) -> {
			checkParamHeaders(tool, context, request);
			return CallToolResult.builder().addTextContent("Region: " + request.arguments().get("region")).build();
		});
	}

	/**
	 * Checks that the {@code Mcp-Param-*} header of each {@code x-mcp-header} parameter
	 * matches its value in the body: both absent, or equal once the header is decoded.
	 * Only on HTTP, where the transport context holds the headers.
	 */
	@SuppressWarnings("unchecked")
	private static void checkParamHeaders(Tool tool, McpRequestContext context, CallToolRequest request) {
		if (!(context.transportContext().get(PARAM_HEADERS) instanceof Map<?, ?> headers)) {
			return;
		}
		Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
		Map<String, Object> arguments = request.arguments() != null ? request.arguments() : Map.of();
		properties.forEach((param, definition) -> {
			if (!(((Map<String, Object>) definition).get("x-mcp-header") instanceof String headerName)) {
				return;
			}
			String header = (String) headers.get(headerName.toLowerCase(Locale.ROOT));
			Object value = arguments.get(param);
			if (header == null && value == null) {
				return;
			}
			if (header == null || value == null || !decodeHeaderValue(header).equals(String.valueOf(value))) {
				throw new McpError(ErrorCodes.HEADER_MISMATCH,
						"Header Mcp-Param-" + headerName + " does not match the value of " + param);
			}
		});
	}

	/**
	 * A value wrapped in {@code =?base64?...?=} is Base64-encoded, anything else is
	 * literal.
	 */
	private static String decodeHeaderValue(String value) {
		if (value.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length() || !value.startsWith(BASE64_PREFIX)
				|| !value.endsWith(BASE64_SUFFIX)) {
			return value;
		}
		String encoded = value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
		if (!BASE64.matcher(encoded).matches()) {
			throw new McpError(ErrorCodes.HEADER_MISMATCH, "Invalid Base64 header value: " + value);
		}
		return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
	}

	// ---------------------------
	// Tools: multi round-trip requests
	// ---------------------------

	private static ToolSpecification inputRequiredElicitation() {
		return tool("test_input_required_result_elicitation",
				"MRTR: returns InputRequiredResult with elicitation request", (context, request) -> {
					Map<String, Object> responses = inputResponses(request);
					if (responses.containsKey("user_name")) {
						return text("Hello, " + elicited(responses, "user_name", "name") + "!");
					}
					return InputRequiredResult.builder()
						.addInputRequest("user_name", ElicitRequest.form("What is your name?", NAME_SCHEMA))
						.build();
				});
	}

	private static ToolSpecification inputRequiredSampling() {
		return tool("test_input_required_result_sampling", "MRTR: returns InputRequiredResult with sampling request",
				(context, request) -> {
					requireCapability(context.clientCapabilities().sampling() != null, "sampling");
					Map<String, Object> responses = inputResponses(request);
					if (responses.containsKey("sample_request")) {
						return text("Sampling result: " + sampled(responses, "sample_request"));
					}
					return new LegacyInputRequiredResult(
							Map.of("sample_request", samplingRequest("What is the capital of France?", 100)), null);
				});
	}

	private static ToolSpecification inputRequiredListRoots() {
		return tool("test_input_required_result_list_roots",
				"MRTR: returns InputRequiredResult with roots/list request", (context, request) -> {
					requireCapability(context.clientCapabilities().roots() != null, "roots");
					Map<String, Object> responses = inputResponses(request);
					if (responses.containsKey("roots_request")) {
						return text("Found " + roots(responses, "roots_request").size() + " root(s)");
					}
					return new LegacyInputRequiredResult(Map.of("roots_request", ROOTS_REQUEST), null);
				});
	}

	private static ToolSpecification inputRequiredRequestState() {
		return tool("test_input_required_result_request_state", "MRTR: returns InputRequiredResult with requestState",
				(context, request) -> {
					Map<String, Object> responses = inputResponses(request);
					if (request.requestState() != null && responses.containsKey("confirm")) {
						Map<String, Object> state = verify(request.requestState());
						if ("request-state".equals(state.get("kind"))
								&& Boolean.TRUE.equals(elicitedContent(responses, "confirm").get("ok"))) {
							return text("state-ok: requestState validated");
						}
					}
					return InputRequiredResult.builder()
						.addInputRequest("confirm", ElicitRequest.form("Please confirm", OK_SCHEMA))
						.requestState(sign(Map.of("kind", "request-state")))
						.build();
				});
	}

	private static ToolSpecification inputRequiredMultipleInputs() {
		return tool("test_input_required_result_multiple_inputs",
				"MRTR: returns InputRequiredResult with multiple input requests", (context, request) -> {
					ClientCapabilities capabilities = context.clientCapabilities();
					requireCapability(capabilities.sampling() != null, "sampling");
					requireCapability(capabilities.roots() != null, "roots");
					Map<String, Object> responses = inputResponses(request);
					if (request.requestState() != null && responses.containsKey("user_name")
							&& responses.containsKey("greeting") && responses.containsKey("client_roots")
							&& "multiple-inputs".equals(verify(request.requestState()).get("kind"))) {
						return text(
								"Name: %s; Greeting: %s; Roots: %d".formatted(elicited(responses, "user_name", "name"),
										sampled(responses, "greeting"), roots(responses, "client_roots").size()));
					}
					Map<String, Object> inputRequests = new LinkedHashMap<>();
					inputRequests.put("user_name", elicitationRequest("What is your name?", NAME_SCHEMA));
					inputRequests.put("greeting", samplingRequest("Generate a greeting", 50));
					inputRequests.put("client_roots", ROOTS_REQUEST);
					return new LegacyInputRequiredResult(inputRequests, sign(Map.of("kind", "multiple-inputs")));
				});
	}

	/**
	 * Each round carries the answers of the previous ones in {@code requestState}.
	 */
	private static ToolSpecification inputRequiredMultiRound() {
		return tool("test_input_required_result_multi_round", "MRTR: multi-round InputRequiredResult workflow",
				(context, request) -> {
					Map<String, Object> responses = inputResponses(request);
					Map<String, Object> state = request.requestState() != null ? verify(request.requestState())
							: Map.of();
					Object round = state.get("round");
					if (Integer.valueOf(1).equals(round) && responses.containsKey("step1")) {
						return InputRequiredResult.builder()
							.addInputRequest("step2",
									ElicitRequest.form("Step 2: What is your favorite color?",
											objectSchema(Map.of("color", Map.of("type", "string")), "color")))
							.requestState(sign(Map.of("round", 2, "name", elicited(responses, "step1", "name"))))
							.build();
					}
					if (Integer.valueOf(2).equals(round) && responses.containsKey("step2")) {
						return text("Multi-round complete for %s who likes %s".formatted(state.get("name"),
								elicited(responses, "step2", "color")));
					}
					return InputRequiredResult.builder()
						.addInputRequest("step1", ElicitRequest.form("Step 1: What is your name?", NAME_SCHEMA))
						.requestState(sign(Map.of("round", 1)))
						.build();
				});
	}

	/**
	 * A {@code requestState} that fails verification is rejected with a JSON-RPC error.
	 */
	private static ToolSpecification inputRequiredTamperedState() {
		return tool("test_input_required_result_tampered_state", "MRTR: HMAC-signed requestState integrity test",
				(context, request) -> {
					if (request.requestState() != null
							&& "tamper-test".equals(verify(request.requestState()).get("kind"))
							&& inputResponses(request).containsKey("confirm")) {
						return text("integrity-ok: state verified");
					}
					return InputRequiredResult.builder()
						.addInputRequest("confirm", ElicitRequest.form("Please confirm", OK_SCHEMA))
						.requestState(sign(Map.of("kind", "tamper-test")))
						.build();
				});
	}

	/**
	 * Only asks for input the client declared it can provide.
	 */
	private static ToolSpecification inputRequiredCapabilities() {
		return tool("test_input_required_result_capabilities", "MRTR: respects client capabilities in inputRequests",
				(context, request) -> {
					Map<String, Object> responses = inputResponses(request);
					if (!responses.isEmpty()) {
						return text("capabilities-ok: received " + String.join(",", responses.keySet()));
					}
					ClientCapabilities capabilities = context.clientCapabilities();
					Map<String, Object> inputRequests = new LinkedHashMap<>();
					if (capabilities.elicitation() != null) {
						inputRequests.put("elicit_input", elicitationRequest("Elicitation input",
								objectSchema(Map.of("value", Map.of("type", "string")), "value")));
					}
					if (capabilities.sampling() != null) {
						inputRequests.put("sample_input", samplingRequest("Sample request", 50));
					}
					if (inputRequests.isEmpty()) {
						return text("No supported capabilities declared");
					}
					return new LegacyInputRequiredResult(inputRequests, sign(Map.of("kind", "capabilities-test")));
				});
	}

	/**
	 * An {@code input_required} result with sampling or roots input requests, which
	 * {@link InputRequiredResult} does not model: they are deprecated in
	 * {@code 2026-07-28}, but still exercised by the conformance suite.
	 * {@link CallToolOutcome} is open, so the server serializes this result as is. Unlike
	 * for an {@link InputRequiredResult}, the server does not check that the client
	 * declared the capabilities the input requests need: the handler must.
	 *
	 * @param inputRequests the input requests, in their wire form
	 * @param requestState the request state, {@code null} for none
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record LegacyInputRequiredResult(Map<String, Object> inputRequests,
			String requestState) implements CallToolOutcome {

		@Override
		public Map<String, Object> meta() {
			return null;
		}

		@Override
		public String resultType() {
			return McpSchema.RESULT_TYPE_INPUT_REQUIRED;
		}

	}

	private static Map<String, Object> elicitationRequest(String message, Map<String, Object> requestedSchema) {
		return Map.of("method", McpSchema.METHOD_ELICITATION_CREATE, "params",
				Map.of("message", message, "requestedSchema", requestedSchema));
	}

	private static Map<String, Object> samplingRequest(String prompt, int maxTokens) {
		return Map.of("method", "sampling/createMessage", "params",
				Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("type", "text", "text", prompt))),
						"maxTokens", maxTokens));
	}

	private static Map<String, Object> inputResponses(CallToolRequest request) {
		return request.inputResponses() != null ? request.inputResponses() : Map.of();
	}

	private static Map<String, Object> elicitedContent(Map<String, Object> responses, String key) {
		ElicitResult result = jsonMapper.convertValue(responses.get(key), ElicitResult.class);
		return result.action() == ElicitResult.Action.ACCEPT && result.content() != null ? result.content() : Map.of();
	}

	private static String elicited(Map<String, Object> responses, String key, String field) {
		return elicitedContent(responses, key).get(field) instanceof String value ? value : "unknown";
	}

	/**
	 * The text of a {@code sampling/createMessage} result.
	 */
	private static String sampled(Map<String, Object> responses, String key) {
		Object content = asMap(responses.get(key)).get("content");
		return asMap(content).get("text") instanceof String text ? text : "no response";
	}

	/**
	 * The roots of a {@code roots/list} result.
	 */
	private static List<?> roots(Map<String, Object> responses, String key) {
		return asMap(responses.get(key)).get("roots") instanceof List<?> roots ? roots : List.of();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static void requireCapability(boolean declared, String capability) {
		if (!declared) {
			throw new McpError(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY,
					"Missing required client capability: " + capability,
					Map.of("requiredCapabilities", Map.of(capability, Map.of())));
		}
	}

	/**
	 * Signs a request state, with a nonce so that every state is different:
	 * {@code base64url(json).base64url(hmac)}.
	 */
	private static String sign(Map<String, Object> state) {
		Map<String, Object> nonced = new LinkedHashMap<>(state);
		nonced.put("nonce", UUID.randomUUID().toString());
		String payload = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(DemoServer.toJson(nonced).getBytes(StandardCharsets.UTF_8));
		return payload + "." + hmac(payload);
	}

	/**
	 * Verifies a request state signed by {@link #sign}. The client echoes it, so it may
	 * have been tampered with: a state that fails verification is rejected with an
	 * {@link McpError}, returned to the client as a JSON-RPC error.
	 */
	private static Map<String, Object> verify(String requestState) {
		int dot = requestState.lastIndexOf('.');
		if (dot < 0 || !MessageDigest.isEqual(hmac(requestState.substring(0, dot)).getBytes(StandardCharsets.UTF_8),
				requestState.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "requestState integrity check failed");
		}
		try {
			return jsonMapper.readValue(Base64.getUrlDecoder().decode(requestState.substring(0, dot)), MAP_TYPE_REF);
		}
		catch (IOException ex) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid requestState");
		}
	}

	private static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private static String hmac(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(STATE_KEY, "HmacSHA256"));
			return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// ---------------------------
	// Resources
	// ---------------------------

	private static ResourceSpecification staticText() {
		Resource resource = Resource.builder("test://static-text", "static-text")
			.title("Static Text Resource")
			.description("A static text resource for testing")
			.mimeType("text/plain")
			.build();
		return new ResourceSpecification(resource,
				(context, request) -> ReadResourceResult.builder(List.of(new TextResourceContents(request.uri(),
						"text/plain", "This is the content of the static text resource.")))
					.build());
	}

	private static ResourceSpecification staticBinary() {
		Resource resource = Resource.builder("test://static-binary", "static-binary")
			.title("Static Binary Resource")
			.description("A static binary resource (image) for testing")
			.mimeType("image/png")
			.build();
		return new ResourceSpecification(resource,
				(context, request) -> ReadResourceResult
					.builder(List.of(new BlobResourceContents(request.uri(), "image/png", TEST_IMAGE_BASE64)))
					.build());
	}

	private static ResourceTemplateSpecification template() {
		ResourceTemplate template = ResourceTemplate.builder("test://template/{id}/data", "template")
			.title("Resource Template")
			.description("A resource template with parameter substitution")
			.mimeType("application/json")
			.build();
		DefaultMcpUriTemplateManager uriTemplate = new DefaultMcpUriTemplateManager(template.uriTemplate());
		return new ResourceTemplateSpecification(template, (context, request) -> {
			String id = uriTemplate.extractVariableValues(request.uri()).get("id");
			String data = DemoServer.toJson(Map.of("id", id, "templateTest", true, "data", "Data for ID: " + id));
			return ReadResourceResult
				.builder(List.of(new TextResourceContents(request.uri(), "application/json", data)))
				.build();
		});
	}

	// ---------------------------
	// Prompts
	// ---------------------------

	private static PromptSpecification simplePrompt() {
		Prompt prompt = Prompt.builder("test_simple_prompt")
			.title("Simple Test Prompt")
			.description("A simple prompt without arguments")
			.build();
		return new PromptSpecification(prompt,
				(context, request) -> prompt(new TextContent("This is a simple prompt for testing.")));
	}

	private static PromptSpecification promptWithArguments() {
		Prompt prompt = Prompt.builder("test_prompt_with_arguments")
			.title("Prompt With Arguments")
			.description("A prompt with required arguments")
			.arguments(List.of(PromptArgument.builder("arg1").description("First test argument").required(true).build(),
					PromptArgument.builder("arg2").description("Second test argument").required(true).build()))
			.build();
		return new PromptSpecification(prompt,
				(context, request) -> prompt(new TextContent("Prompt with arguments: arg1='%s', arg2='%s'"
					.formatted(request.arguments().get("arg1"), request.arguments().get("arg2")))));
	}

	private static PromptSpecification promptWithEmbeddedResource() {
		Prompt prompt = Prompt.builder("test_prompt_with_embedded_resource")
			.title("Prompt With Embedded Resource")
			.description("A prompt that includes an embedded resource")
			.arguments(List.of(PromptArgument.builder("resourceUri")
				.description("URI of the resource to embed")
				.required(true)
				.build()))
			.build();
		return new PromptSpecification(prompt,
				(context, request) -> prompt(
						new EmbeddedResource(new TextResourceContents(request.arguments().get("resourceUri"),
								"text/plain", "Embedded resource content for testing.")),
						new TextContent("Please process the embedded resource above.")));
	}

	private static PromptSpecification promptWithImage() {
		Prompt prompt = Prompt.builder("test_prompt_with_image")
			.title("Prompt With Image")
			.description("A prompt that includes image content")
			.build();
		return new PromptSpecification(prompt,
				(context, request) -> prompt(new ImageContent(TEST_IMAGE_BASE64, "image/png"),
						new TextContent("Please analyze the image above.")));
	}

	/**
	 * Prompts may need input too.
	 */
	private static PromptSpecification inputRequiredPrompt() {
		Prompt prompt = Prompt.builder("test_input_required_result_prompt")
			.description("MRTR: prompt that requires elicitation input")
			.build();
		return new PromptSpecification(prompt, (context, request) -> {
			Map<String, Object> responses = request.inputResponses() != null ? request.inputResponses() : Map.of();
			if (responses.containsKey("user_context")) {
				return prompt(
						new TextContent("Prompt with context: " + elicited(responses, "user_context", "context")));
			}
			return InputRequiredResult.builder()
				.addInputRequest("user_context",
						ElicitRequest.form("What context should the prompt use?",
								objectSchema(Map.of("context", Map.of("type", "string")), "context")))
				.build();
		});
	}

	// ---------------------------
	// Helpers
	// ---------------------------

	private static ToolSpecification tool(String name, String description,
			BiFunction<McpRequestContext, CallToolRequest, CallToolOutcome> handler) {
		return new ToolSpecification(Tool.builder(name).description(description).build(), handler);
	}

	private static ToolSpecification textTool(String name, String description, String text) {
		return tool(name, description, (context, request) -> text(text));
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addTextContent(text).build();
	}

	private static GetPromptResult prompt(McpSchema.Content... contents) {
		return GetPromptResult
			.builder(List.of(contents).stream().map(content -> new PromptMessage(Role.USER, content)).toList())
			.build();
	}

	private static Map<String, Object> parseJson(String json) {
		try {
			return jsonMapper.readValue(json, MAP_TYPE_REF);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
