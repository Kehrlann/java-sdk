/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.demo.server;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpServerFeatures.CompletionSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.PromptSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ResourceSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ResourceTemplateSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.StreamingToolSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ToolSpecification;
import io.modelcontextprotocol.modern.server.transport.StdioServerTransport;
import io.modelcontextprotocol.modern.spec.McpError;
import io.modelcontextprotocol.modern.spec.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.Prompt;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.spec.McpSchema.Resource;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplateReference;
import io.modelcontextprotocol.modern.spec.McpSchema.Role;
import io.modelcontextprotocol.modern.spec.McpSchema.TextContent;
import io.modelcontextprotocol.modern.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import io.modelcontextprotocol.modern.spec.McpSchema.ToolAnnotations;
import io.modelcontextprotocol.modern.util.DefaultMcpUriTemplateManager;

/**
 * A demo MCP server, over stdio, showcasing the features of the {@code 2026-07-28} server
 * API:
 * <ul>
 * <li>tools: plain ({@code echo}, {@code client_info}), with structured output
 * ({@code add}), streaming progress and honoring cancellation ({@code countdown}), and
 * multi round-trip elicitation ({@code book_table});</li>
 * <li>resources, with caching hints, and a resource template;</li>
 * <li>a prompt;</li>
 * <li>completion of prompt and resource template arguments.</li>
 * </ul>
 * Build and run it with:
 *
 * <pre>
 * ./mvnw -pl mcp-demo-server -am package -DskipTests
 * java -jar mcp-demo-server/target/mcp-demo-server-2.1.0-SNAPSHOT.jar
 * </pre>
 */
public final class DemoServer {

	private static final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	private static final List<String> CITIES = List.of("Amsterdam", "Berlin", "Lisbon", "London", "Madrid", "Paris",
			"Porto", "Rome");

	private static final List<String> LANGUAGES = List.of("go", "java", "javascript", "kotlin", "python", "rust",
			"typescript");

	private DemoServer() {
	}

	public static void main(String[] args) throws IOException {
		StdioServerTransport.builder(server()).build().run();
	}

	static McpServer server() {
		return McpServer.builder()
			.serverInfo("mcp-demo-server", "0.1.0")
			.instructions("A demo server. Try book_table for a multi round-trip tool, "
					+ "or countdown with a progress token for progress notifications.")
			// Applied to cacheable results that do not set their own hints
			.cacheHints(Duration.ofMinutes(1), CacheScope.PUBLIC)
			.tools(echo(), clientInfo(), add(), bookTable())
			.streamingTools(countdown())
			.resources(readme(), time())
			.resourceTemplates(weather())
			.prompts(codeReview())
			.completions(languageCompletion(), cityCompletion())
			.build();
	}

	// ---------------------------
	// Tools
	// ---------------------------

	private static ToolSpecification echo() {
		Tool tool = Tool.builder("echo")
			.description("Echoes a message back")
			.inputSchema(objectSchema(Map.of("message", Map.of("type", "string")), "message"))
			.annotations(ToolAnnotations.builder().readOnlyHint(true).build())
			.build();
		return new ToolSpecification(tool,
				(context, request) -> CallToolResult.builder()
					.addTextContent(String.valueOf(request.arguments().get("message")))
					.build());
	}

	/**
	 * There is no session: everything the server knows about the client comes with the
	 * request, in {@code _meta}.
	 */
	private static ToolSpecification clientInfo() {
		Tool tool = Tool.builder("client_info")
			.description("Describes the client that sent the request")
			.annotations(ToolAnnotations.builder().readOnlyHint(true).build())
			.build();
		return new ToolSpecification(tool, (context, request) -> {
			Map<String, Object> info = new LinkedHashMap<>();
			info.put("protocolVersion", context.protocolVersion());
			info.put("clientInfo", context.clientInfo());
			info.put("clientCapabilities", context.clientCapabilities());
			info.put("logLevel", context.logLevel());
			return CallToolResult.builder().addTextContent(toJson(info)).build();
		});
	}

	/**
	 * A tool with an output schema. The result only has {@code structuredContent}: the
	 * server validates it against the output schema, and adds a text content block
	 * holding it.
	 */
	private static ToolSpecification add() {
		Tool tool = Tool.builder("add")
			.description("Adds two numbers")
			.inputSchema(objectSchema(Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number")), "a", "b"))
			.outputSchema(objectSchema(Map.of("sum", Map.of("type", "number")), "sum"))
			.annotations(ToolAnnotations.builder().readOnlyHint(true).idempotentHint(true).build())
			.build();
		return new ToolSpecification(tool, (context, request) -> {
			double a = ((Number) request.arguments().get("a")).doubleValue();
			double b = ((Number) request.arguments().get("b")).doubleValue();
			return CallToolResult.builder().structuredContent(Map.of("sum", a + b)).build();
		});
	}

	/**
	 * A streaming tool reports progress through its sink, when the client sent a progress
	 * token. Cancelling the request interrupts the handler thread: the blocking
	 * {@code Thread.sleep} throws, and nothing more is sent for the request.
	 */
	private static StreamingToolSpecification countdown() {
		Tool tool = Tool.builder("countdown")
			.description("Counts down, one second at a time, reporting progress")
			.inputSchema(
					objectSchema(Map.of("seconds", Map.of("type", "integer", "minimum", 1, "maximum", 60)), "seconds"))
			.build();
		return new StreamingToolSpecification(tool, (context, request, sink) -> {
			int seconds = ((Number) request.arguments().get("seconds")).intValue();
			for (int elapsed = 1; elapsed <= seconds; elapsed++) {
				sleep(Duration.ofSeconds(1));
				if (context.progressToken() != null) {
					sink.progress(ProgressNotification.builder(context.progressToken(), (double) elapsed)
						.total((double) seconds)
						.message(elapsed + "/" + seconds + " seconds")
						.build());
				}
			}
			sink.complete(CallToolResult.builder().addTextContent("Liftoff!").build());
		});
	}

	/**
	 * A multi round-trip tool. Instead of sending requests to the client, the handler
	 * returns an {@link InputRequiredResult}, and the client retries the call with its
	 * {@code inputResponses}:
	 * <ol>
	 * <li>asks for the party size and time;</li>
	 * <li>asks for a confirmation, carrying the answers of round 1 in
	 * {@code requestState};</li>
	 * <li>books the table.</li>
	 * </ol>
	 */
	private static ToolSpecification bookTable() {
		Tool tool = Tool.builder("book_table")
			.description("Books a table at a restaurant, asking the user for the details")
			.inputSchema(objectSchema(Map.of("restaurant", Map.of("type", "string")), "restaurant"))
			.build();
		return new ToolSpecification(tool, DemoServer::bookTable);
	}

	private static CallToolOutcome bookTable(McpRequestContext context, CallToolRequest request) {
		String restaurant = String.valueOf(request.arguments().get("restaurant"));
		Map<String, Object> responses = request.inputResponses() != null ? request.inputResponses() : Map.of();

		if (responses.containsKey("confirmation") && request.requestState() != null) {
			Booking booking = parseRequestState(request.requestState());
			ElicitResult confirmation = jsonMapper.convertValue(responses.get("confirmation"), ElicitResult.class);
			if (!accepted(confirmation) || !Boolean.TRUE.equals(confirmation.content().get("confirm"))) {
				return CallToolResult.builder().addTextContent("No table booked.").build();
			}
			return CallToolResult.builder()
				.addTextContent("Booked a table for %d at %s, at %s.".formatted(booking.partySize(), restaurant,
						booking.time()))
				.build();
		}

		if (responses.containsKey("details")) {
			ElicitResult details = jsonMapper.convertValue(responses.get("details"), ElicitResult.class);
			if (!accepted(details)) {
				return CallToolResult.builder().addTextContent("No table booked.").build();
			}
			Booking booking = new Booking(((Number) details.content().get("partySize")).intValue(),
					String.valueOf(details.content().get("time")));
			return InputRequiredResult.builder()
				.addInputRequest("confirmation",
						ElicitRequest.form(
								"Book a table for %d at %s, at %s?".formatted(booking.partySize(), restaurant,
										booking.time()),
								objectSchema(Map.of("confirm", Map.of("type", "boolean")), "confirm")))
				.requestState(toJson(booking))
				.build();
		}

		return InputRequiredResult.builder()
			.addInputRequest("details",
					ElicitRequest.form("Booking a table at " + restaurant + ".", objectSchema(Map.of("partySize",
							Map.of("type", "integer", "title", "Party size", "minimum", 1, "maximum", 12), "time",
							Map.of("type", "string", "title", "Time", "enum", List.of("18:00", "19:30", "21:00"))),
							"partySize", "time")))
			.build();
	}

	record Booking(int partySize, String time) {
	}

	private static boolean accepted(ElicitResult result) {
		return result.action() == ElicitResult.Action.ACCEPT && result.content() != null;
	}

	// ---------------------------
	// Resources
	// ---------------------------

	/**
	 * A static resource, with its own caching hints: it never changes.
	 */
	private static ResourceSpecification readme() {
		Resource resource = Resource.builder("demo://readme", "readme")
			.title("About this server")
			.mimeType("text/markdown")
			.build();
		return new ResourceSpecification(resource,
				(context, request) -> ReadResourceResult
					.builder(List.of(new TextResourceContents(request.uri(), "text/markdown", """
							# mcp-demo-server

							A demo of the `2026-07-28` Java server API. Every request is
							self-contained: no session, no `initialize`.
							""")))
					.ttlMs(Duration.ofDays(1).toMillis())
					.build());
	}

	/**
	 * A resource with the server's default caching hints.
	 */
	private static ResourceSpecification time() {
		Resource resource = Resource.builder("demo://time", "time")
			.title("Current time")
			.mimeType("text/plain")
			.build();
		return new ResourceSpecification(resource,
				(context, request) -> ReadResourceResult
					.builder(List.of(new TextResourceContents(request.uri(), "text/plain", Instant.now().toString())))
					.build());
	}

	/**
	 * A resource template: reads of any matching URI go to this handler.
	 */
	private static ResourceTemplateSpecification weather() {
		ResourceTemplate template = ResourceTemplate.builder("demo://weather/{city}", "weather")
			.title("Weather forecast")
			.mimeType("text/plain")
			.build();
		DefaultMcpUriTemplateManager uriTemplate = new DefaultMcpUriTemplateManager(template.uriTemplate());
		return new ResourceTemplateSpecification(template, (context, request) -> {
			String city = uriTemplate.extractVariableValues(request.uri()).get("city");
			String forecast = "Sunny in %s, %d°C".formatted(city, 15 + Math.abs(city.hashCode() % 15));
			return ReadResourceResult.builder(List.of(new TextResourceContents(request.uri(), "text/plain", forecast)))
				.ttlMs(Duration.ofMinutes(10).toMillis())
				.cacheScope(CacheScope.PUBLIC)
				.build();
		});
	}

	// ---------------------------
	// Prompts
	// ---------------------------

	private static PromptSpecification codeReview() {
		Prompt prompt = Prompt.builder("code_review")
			.title("Code review")
			.description("Asks for a review of a piece of code")
			.arguments(List.of(PromptArgument.builder("language").required(true).build(),
					PromptArgument.builder("code").required(true).build()))
			.build();
		return new PromptSpecification(prompt,
				(context, request) -> new GetPromptResult("Review of " + request.arguments().get("language") + " code",
						List.of(new PromptMessage(Role.USER,
								new TextContent("Please review this " + request.arguments().get("language")
										+ " code:\n\n" + request.arguments().get("code"))))));
	}

	// ---------------------------
	// Completions
	// ---------------------------

	private static CompletionSpecification languageCompletion() {
		return new CompletionSpecification(new PromptReference("code_review"),
				(context, request) -> complete(request, "language", LANGUAGES));
	}

	private static CompletionSpecification cityCompletion() {
		return new CompletionSpecification(new ResourceTemplateReference("demo://weather/{city}"),
				(context, request) -> complete(request, "city", CITIES));
	}

	private static CompleteResult complete(CompleteRequest request, String argument, List<String> candidates) {
		if (!argument.equals(request.argument().name())) {
			return new CompleteResult(CompleteCompletion.builder(List.of()).build());
		}
		String prefix = request.argument().value().toLowerCase();
		List<String> values = candidates.stream().filter(value -> value.toLowerCase().startsWith(prefix)).toList();
		return new CompleteResult(CompleteCompletion.builder(values).total(values.size()).hasMore(false).build());
	}

	// ---------------------------
	// Helpers
	// ---------------------------

	private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
		return Map.of("type", "object", "properties", properties, "required", List.of(required));
	}

	/**
	 * Blocks for the given duration. Handlers are plain functions, so a handler that
	 * blocks must turn the {@link InterruptedException} of a cancellation into an
	 * unchecked exception, restoring the interrupt status.
	 */
	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Cancelled", ex);
		}
	}

	private static String toJson(Object value) {
		try {
			return jsonMapper.writeValueAsString(value);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The client echoes {@code requestState} as it received it. It is not
	 * integrity-protected yet, so it is checked like any other input. An {@link McpError}
	 * is returned to the client as a JSON-RPC error.
	 */
	private static Booking parseRequestState(String requestState) {
		Booking booking;
		try {
			booking = jsonMapper.readValue(requestState, Booking.class);
		}
		catch (IOException ex) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid requestState");
		}
		if (booking.partySize() < 1 || booking.partySize() > 12 || booking.time() == null) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid requestState");
		}
		return booking;
	}

}
