/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.spec.McpError;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.modern.spec.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.spec.McpSchema.Prompt;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.spec.McpSchema.RequestId;
import io.modelcontextprotocol.modern.spec.McpSchema.Resource;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.spec.McpSchema.Role;
import io.modelcontextprotocol.modern.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.spec.McpSchema.TextContent;
import io.modelcontextprotocol.modern.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class McpServerTests {

	private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

	// ---------------------------
	// _meta validation
	// ---------------------------

	@Test
	void missingParamsIsInvalidParams() throws IOException {
		McpServer server = McpServer.builder().build();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest("tools/list", RequestId.of(1), null));

		assertThatJson(json(response)).node("error.code").isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void missingMetaIsInvalidParams() throws IOException {
		McpServer server = McpServer.builder().build();

		assertThatJson(json(request(server, "tools/list", Map.of()))).node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void missingProtocolVersionIsInvalidParams() throws IOException {
		McpServer server = McpServer.builder().build();
		var params = Map.<String, Object>of("_meta", Map.of(MetaKeys.CLIENT_CAPABILITIES, Map.of()));

		assertThatJson(json(request(server, "tools/list", params))).node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void missingClientCapabilitiesIsInvalidParams() throws IOException {
		McpServer server = McpServer.builder().build();
		var params = Map.<String, Object>of("_meta", Map.of(MetaKeys.PROTOCOL_VERSION, "2026-07-28"));

		assertThatJson(json(request(server, "tools/list", params))).node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void unsupportedProtocolVersion() throws IOException {
		McpServer server = McpServer.builder().build();
		var params = Map.<String, Object>of("_meta",
				Map.of(MetaKeys.PROTOCOL_VERSION, "2025-11-25", MetaKeys.CLIENT_CAPABILITIES, Map.of()));

		assertThatJson(json(request(server, "tools/list", params))).isEqualTo("""
				{
					"jsonrpc": "2.0",
					"id": 1,
					"error": {
						"code": -32022,
						"message": "Unsupported protocol version",
						"data": {"supported": ["2026-07-28"], "requested": "2025-11-25"}
					}
				}""");
	}

	@Test
	void requestContextIsPopulatedFromMeta() {
		AtomicReference<McpRequestContext> captured = new AtomicReference<>();
		McpServer server = McpServer.builder().tool(Tool.builder("t").build(), (ctx, request) -> {
			captured.set(ctx);
			return CallToolResult.builder().build();
		}).build();
		Map<String, Object> meta = new HashMap<>(meta());
		meta.put(MetaKeys.CLIENT_INFO, Map.of("name", "test-client", "version", "0.1"));
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of("elicitation", Map.of("form", Map.of())));
		meta.put(MetaKeys.LOG_LEVEL, "warning");
		meta.put(MetaKeys.PROGRESS_TOKEN, "progress-1");
		meta.put("com.example/custom", "value");

		server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of("req-1"),
				Map.of("name", "t", "_meta", meta)));

		McpRequestContext ctx = captured.get();
		assertThat(ctx.requestId()).isEqualTo(RequestId.of("req-1"));
		assertThat(ctx.protocolVersion()).isEqualTo("2026-07-28");
		assertThat(ctx.clientInfo().name()).isEqualTo("test-client");
		assertThat(ctx.clientCapabilities().elicitation().form()).isNotNull();
		assertThat(ctx.clientCapabilities().sampling()).isNull();
		assertThat(ctx.logLevel()).isEqualTo(LoggingLevel.WARNING);
		assertThat(ctx.progressToken()).isEqualTo("progress-1");
		assertThat(ctx.meta()).containsEntry("com.example/custom", "value");
		assertThat(ctx.transportContext()).isSameAs(McpTransportContext.EMPTY);
	}

	@Test
	void transportContextIsPassedToHandlers() {
		AtomicReference<McpRequestContext> captured = new AtomicReference<>();
		McpServer server = McpServer.builder().tool(Tool.builder("t").build(), (ctx, request) -> {
			captured.set(ctx);
			return CallToolResult.builder().build();
		}).build();
		McpTransportContext transportContext = McpTransportContext.create(Map.of("principal", "alice"));

		server.handleRequest(transportContext,
				new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1), Map.of("name", "t", "_meta", meta())),
				notification -> {
				});

		assertThat(captured.get().transportContext()).isSameAs(transportContext);
		assertThat(captured.get().transportContext().get("principal")).isEqualTo("alice");
	}

	@Test
	void integerRequestIdIsKeptInContextAndEchoedAsSent() throws IOException {
		AtomicReference<McpRequestContext> captured = new AtomicReference<>();
		McpServer server = McpServer.builder().tool(Tool.builder("t").build(), (ctx, request) -> {
			captured.set(ctx);
			return CallToolResult.builder().build();
		}).build();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL,
				RequestId.of(42), Map.of("name", "t", "_meta", meta())));

		assertThat(captured.get().requestId()).isEqualTo(RequestId.of(42));
		assertThatJson(json(response)).node("id").isEqualTo(42);
	}

	// ---------------------------
	// Discovery and dispatch
	// ---------------------------

	@Test
	void discover() throws IOException {
		McpServer server = McpServer.builder()
			.serverInfo("test-server", "1.2.3")
			.instructions("Use the echo tool")
			.tool(Tool.builder("echo").build(), (ctx, request) -> CallToolResult.builder().build())
			.resource(Resource.builder("file:///readme", "readme").build(),
					(ctx, request) -> new ReadResourceResult(List.of()))
			.prompt(Prompt.builder("greeting").build(), (ctx, request) -> new GetPromptResult(null, List.of()))
			.completion(new PromptReference("greeting"),
					(ctx, request) -> new CompleteResult(new CompleteCompletion(List.of(), null, null)))
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_SERVER_DISCOVER, Map.of("_meta", meta())))).isEqualTo("""
				{
					"jsonrpc": "2.0",
					"id": 1,
					"result": {
						"resultType": "complete",
						"supportedVersions": ["2026-07-28"],
						"capabilities": {"tools": {}, "resources": {}, "prompts": {}, "completions": {}},
						"instructions": "Use the echo tool",
						"ttlMs": 0,
						"cacheScope": "private",
						"_meta": {"io.modelcontextprotocol/serverInfo": {"name": "test-server", "version": "1.2.3"}}
					}
				}""");
	}

	@Test
	void unknownMethod() throws IOException {
		McpServer server = McpServer.builder().build();

		assertThatJson(json(request(server, "ping", Map.of("_meta", meta())))).node("error.code")
			.isEqualTo(ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void undeclaredCapabilityIsMethodNotFound() throws IOException {
		McpServer server = McpServer.builder().build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_LIST, Map.of("_meta", meta())))).node("error.code")
			.isEqualTo(ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void explicitCapabilitiesOverrideDerivedOnes() throws IOException {
		McpServer server = McpServer.builder()
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tool(Tool.builder("t").build(), (ctx, request) -> CallToolResult.builder().build())
			.prompt(Prompt.builder("p").build(), (ctx, request) -> new GetPromptResult(null, List.of()))
			.build();

		assertThat(server.getCapabilities()).isEqualTo(ServerCapabilities.builder().tools(true).build());
	}

	// ---------------------------
	// Tools
	// ---------------------------

	@Test
	void listTools() throws IOException {
		McpServer server = McpServer.builder()
			.serverInfo("test-server", "1.2.3")
			.tool(Tool.builder("echo").description("Echoes its input").build(),
					(ctx, request) -> CallToolResult.builder().build())
			.tool(Tool.builder("confirm").build(), (ctx, request) -> CallToolResult.builder().build())
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_LIST, Map.of("_meta", meta())))).node("result")
			.isEqualTo("""
					{
						"resultType": "complete",
						"tools": [
							{"name": "echo", "description": "Echoes its input", "inputSchema": {"type": "object"}},
							{"name": "confirm", "inputSchema": {"type": "object"}}
						],
						"ttlMs": 0,
						"cacheScope": "private",
						"_meta": {"io.modelcontextprotocol/serverInfo": {"name": "test-server", "version": "1.2.3"}}
					}""");
	}

	@Test
	void listToolsRejectsCursor() throws IOException {
		McpServer server = McpServer.builder()
			.tool(Tool.builder("t").build(), (ctx, request) -> CallToolResult.builder().build())
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_LIST, Map.of("cursor", "abc", "_meta", meta()))))
			.node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void callTool() throws IOException {
		McpServer server = echoServer();

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL,
				Map.of("name", "echo", "arguments", Map.of("message", "hi"), "_meta", meta())));

		assertThatJson(response).node("result").isEqualTo("""
				{
					"resultType": "complete",
					"content": [{"type": "text", "text": "hi"}],
					"_meta": {"io.modelcontextprotocol/serverInfo": {"name": "test-server", "version": "1.2.3"}}
				}""");
	}

	@Test
	void callToolReturningInputRequired() throws IOException {
		McpServer server = McpServer.builder()
			.serverInfo("test-server", "1.2.3")
			.tool(Tool.builder("confirm").build(), (ctx, request) -> {
				if (request.requestState() == null) {
					return InputRequiredResult.builder()
						.addInputRequest("ok", ElicitRequest.form("Sure?", Map.of("type", "object")))
						.requestState("round-1")
						.build();
				}
				return CallToolResult.builder().addTextContent("confirmed").build();
			})
			.build();

		Map<String, Object> meta = metaWithClientCapabilities(Map.of("elicitation", Map.of()));

		String firstRound = json(
				request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "confirm", "_meta", meta)));
		String secondRound = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "confirm",
				"inputResponses", Map.of("ok", Map.of("action", "accept")), "requestState", "round-1", "_meta", meta)));

		assertThatJson(firstRound).node("result").isEqualTo("""
				{
					"resultType": "input_required",
					"inputRequests": {
						"ok": {
							"method": "elicitation/create",
							"params": {"mode": "form", "message": "Sure?", "requestedSchema": {"type": "object"}}
						}
					},
					"requestState": "round-1",
					"_meta": {"io.modelcontextprotocol/serverInfo": {"name": "test-server", "version": "1.2.3"}}
				}""");
		assertThatJson(secondRound).node("result.content[0].text").isEqualTo("confirmed");
	}

	@Test
	void inputRequestWithoutElicitationCapability() throws IOException {
		McpServer server = inputRequiredServer(ElicitRequest.form("Sure?", Map.of("type", "object")));

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "ask", "_meta", meta())));

		assertThatJson(response).isEqualTo("""
				{
					"jsonrpc": "2.0",
					"id": 1,
					"error": {
						"code": -32021,
						"message": "Missing required client capability",
						"data": {"requiredCapabilities": {"elicitation": {"form": {}}}}
					}
				}""");
	}

	@Test
	void urlInputRequestWithFormOnlyElicitationCapability() throws IOException {
		McpServer server = inputRequiredServer(ElicitRequest.form("Sure?", Map.of("type", "object")),
				ElicitRequest.url("Sign in", "https://example.com/login"));
		// No mode declared means form mode only
		Map<String, Object> meta = metaWithClientCapabilities(Map.of("elicitation", Map.of()));

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "ask", "_meta", meta)));

		assertThatJson(response).node("error.code").isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);
		assertThatJson(response).node("error.data.requiredCapabilities").isEqualTo("""
				{"elicitation": {"url": {}}}""");
	}

	@Test
	void formInputRequestWithUrlOnlyElicitationCapability() throws IOException {
		McpServer server = inputRequiredServer(ElicitRequest.form("Sure?", Map.of("type", "object")));
		Map<String, Object> meta = metaWithClientCapabilities(Map.of("elicitation", Map.of("url", Map.of())));

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "ask", "_meta", meta)));

		assertThatJson(response).node("error.data.requiredCapabilities").isEqualTo("""
				{"elicitation": {"form": {}}}""");
	}

	@Test
	void inputRequestsWithDeclaredElicitationModes() throws IOException {
		McpServer server = inputRequiredServer(ElicitRequest.form("Sure?", Map.of("type", "object")),
				ElicitRequest.url("Sign in", "https://example.com/login"));
		Map<String, Object> meta = metaWithClientCapabilities(
				Map.of("elicitation", Map.of("form", Map.of(), "url", Map.of())));

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "ask", "_meta", meta)));

		assertThatJson(response).node("result.resultType").isEqualTo("input_required");
	}

	@Test
	void requestStateOnlyInputRequiredNeedsNoCapability() throws IOException {
		McpServer server = McpServer.builder()
			.tool(Tool.builder("busy").build(),
					(ctx, request) -> InputRequiredResult.builder().requestState("retry-later").build())
			.build();

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "busy", "_meta", meta())));

		assertThatJson(response).node("result.requestState").isEqualTo("retry-later");
	}

	@Test
	void promptInputRequestWithoutElicitationCapability() throws IOException {
		McpServer server = McpServer.builder()
			.prompt(Prompt.builder("ask").build(),
					(ctx, request) -> InputRequiredResult.builder()
						.addInputRequest("ok", ElicitRequest.form("Sure?", Map.of("type", "object")))
						.build())
			.build();

		String response = json(request(server, McpSchema.METHOD_PROMPTS_GET, Map.of("name", "ask", "_meta", meta())));

		assertThatJson(response).node("error.code").isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);
	}

	@Test
	void callUnknownTool() throws IOException {
		McpServer server = echoServer();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "nope", "_meta", meta()))))
			.node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void callToolWithMalformedParams() throws IOException {
		McpServer server = echoServer();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL,
				Map.of("name", "echo", "arguments", "not-an-object", "_meta", meta()))))
			.node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void handlerMcpErrorIsReturnedAsIs() throws IOException {
		McpServer server = McpServer.builder().tool(Tool.builder("rejects").build(), (ctx, request) -> {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Bad arguments", Map.of("hint", "try again"));
		}).build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "rejects", "_meta", meta()))))
			.node("error")
			.isEqualTo("""
					{"code": -32602, "message": "Bad arguments", "data": {"hint": "try again"}}""");
	}

	@Test
	void handlerExceptionIsInternalErrorWithoutDetails() throws IOException {
		McpServer server = McpServer.builder().tool(Tool.builder("fails").build(), (ctx, request) -> {
			throw new IllegalStateException("secret internal detail");
		}).build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "fails", "_meta", meta()))))
			.node("error")
			.isEqualTo("""
					{"code": -32603, "message": "Internal error"}""");
	}

	// ---------------------------
	// Tool validation
	// ---------------------------

	@Test
	void validToolNames() {
		for (String name : List.of("getUser", "DATA_EXPORT_v2", "admin.tools.list", "a-b", "x".repeat(128))) {
			assertThatCode(() -> McpServer.builder()
				.tool(Tool.builder(name).build(), (ctx, request) -> CallToolResult.builder().build())
				.build()).as(name).doesNotThrowAnyException();
		}
	}

	@Test
	void invalidToolNames() {
		for (String name : List.of("has space", "a,b", "tool/name", "caf\u00e9", "x".repeat(129))) {
			assertThatIllegalArgumentException().as(name)
				.isThrownBy(() -> McpServer.builder()
					.tool(Tool.builder(name).build(), (ctx, request) -> CallToolResult.builder().build()))
				.withMessageContaining("Tool name");
		}
	}

	@Test
	void inputSchemaMustBeAnObjectSchema() {
		Tool tool = Tool.builder("t").inputSchema(Map.of("type", "string")).build();
		McpServer.Builder builder = McpServer.builder().tool(tool, (ctx, request) -> CallToolResult.builder().build());

		assertThatIllegalArgumentException().isThrownBy(builder::build)
			.withMessageContaining("Tool 't' inputSchema must have \"type\": \"object\"");
	}

	@Test
	void invalidInputSchemaIsRejected() {
		Tool tool = Tool.builder("t")
			.inputSchema(Map.of("type", "object", "properties", Map.of("a", Map.of("type", "not-a-type"))))
			.build();
		McpServer.Builder builder = McpServer.builder().tool(tool, (ctx, request) -> CallToolResult.builder().build());

		assertThatIllegalArgumentException().isThrownBy(builder::build).withMessageContaining("Tool 't' inputSchema");
	}

	@Test
	void invalidOutputSchemaIsRejected() {
		Tool tool = Tool.builder("t").outputSchema(Map.of("required", "not-an-array")).build();
		McpServer.Builder builder = McpServer.builder().tool(tool, (ctx, request) -> CallToolResult.builder().build());

		assertThatIllegalArgumentException().isThrownBy(builder::build).withMessageContaining("Tool 't' outputSchema");
	}

	@Test
	void conformingStructuredContent() throws IOException {
		McpServer server = weatherServer(CallToolResult.builder()
			.addTextContent("{\"temperature\": 21.5}")
			.structuredContent(Map.of("temperature", 21.5))
			.build());

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "weather", "_meta", meta()))))
			.node("result.structuredContent")
			.isEqualTo("""
					{"temperature": 21.5}""");
	}

	@Test
	void structuredContentIsAddedAsTextContentWhenContentIsEmpty() throws IOException {
		McpServer server = McpServer.builder()
			.tool(Tool.builder("t").build(),
					(ctx, request) -> CallToolResult.builder()
						.structuredContent(Map.of("temperature", 21.5))
						.meta(Map.of("custom", "value"))
						.build())
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "t", "_meta", meta()))))
			.node("result")
			.isEqualTo(
					"""
							{
								"resultType": "complete",
								"content": [{"type": "text", "text": "{\\"temperature\\":21.5}"}],
								"structuredContent": {"temperature": 21.5},
								"_meta": {"custom": "value", "io.modelcontextprotocol/serverInfo": {"name": "mcp-server", "version": "1.0.0"}}
							}""");
	}

	@Test
	void contentIsKeptWhenStructuredContentIsReturned() throws IOException {
		McpServer server = McpServer.builder()
			.tool(Tool.builder("t").build(),
					(ctx, request) -> CallToolResult.builder()
						.addTextContent("It is 21.5 degrees")
						.structuredContent(Map.of("temperature", 21.5))
						.build())
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "t", "_meta", meta()))))
			.node("result.content")
			.isEqualTo("""
					[{"type": "text", "text": "It is 21.5 degrees"}]""");
	}

	@Test
	void nonConformingStructuredContentIsToolError() throws IOException {
		McpServer server = weatherServer(
				CallToolResult.builder().structuredContent(Map.of("temperature", "warm")).build());

		String response = json(
				request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "weather", "_meta", meta())));

		assertThatJson(response).node("result.isError").isEqualTo(true);
		assertThatJson(response).node("result.structuredContent").isAbsent();
		assertThatJson(response).node("result.content[0].text")
			.asString()
			.startsWith("Tool 'weather' returned structuredContent that does not conform to its outputSchema");
	}

	@Test
	void missingStructuredContentIsToolError() throws IOException {
		McpServer server = weatherServer(CallToolResult.builder().addTextContent("21.5").build());

		String response = json(
				request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "weather", "_meta", meta())));

		assertThatJson(response).node("result.isError").isEqualTo(true);
		assertThatJson(response).node("result.content[0].text")
			.isEqualTo("Tool 'weather' has an outputSchema but returned no structuredContent");
	}

	@Test
	void toolErrorIsNotCheckedAgainstOutputSchema() throws IOException {
		McpServer server = weatherServer(CallToolResult.builder().addTextContent("No sensor").isError(true).build());

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "weather", "_meta", meta()))))
			.node("result.content[0].text")
			.isEqualTo("No sensor");
	}

	@Test
	void inputRequiredIsNotCheckedAgainstOutputSchema() throws IOException {
		McpServer server = weatherServer(InputRequiredResult.builder().requestState("retry-later").build());

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "weather", "_meta", meta()))))
			.node("result.resultType")
			.isEqualTo("input_required");
	}

	@Test
	void stringStructuredContentIsValidatedAsJsonString() throws IOException {
		Tool tool = Tool.builder("color").outputSchema(Map.of("type", "string")).build();
		McpServer server = McpServer.builder()
			.tool(tool, (ctx, request) -> CallToolResult.builder().structuredContent("red").build())
			.build();

		String response = json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "color", "_meta", meta())));

		assertThatJson(response).node("result.structuredContent").isEqualTo("red");
		assertThatJson(response).node("result.content[0].text").asString().isEqualTo("\"red\"");
	}

	// ---------------------------
	// Resources
	// ---------------------------

	@Test
	void readResource() throws IOException {
		McpServer server = McpServer.builder()
			.serverInfo("test-server", "1.2.3")
			.resource(Resource.builder("file:///readme", "readme").mimeType("text/plain").build(),
					(ctx, request) -> new ReadResourceResult(
							List.of(new TextResourceContents(request.uri(), "text/plain", "Read me"))))
			.build();

		assertThatJson(json(
				request(server, McpSchema.METHOD_RESOURCES_READ, Map.of("uri", "file:///readme", "_meta", meta()))))
			.node("result")
			.isEqualTo("""
					{
						"resultType": "complete",
						"contents": [{"uri": "file:///readme", "mimeType": "text/plain", "text": "Read me"}],
						"ttlMs": 0,
						"cacheScope": "private",
						"_meta": {"io.modelcontextprotocol/serverInfo": {"name": "test-server", "version": "1.2.3"}}
					}""");
	}

	@Test
	void readResourceFromTemplateKeepsHandlerCacheHints() throws IOException {
		McpServer server = McpServer.builder()
			.resourceTemplate(ResourceTemplate.builder("file:///docs/{name}", "docs").build(),
					(ctx, request) -> new ReadResourceResult(
							List.of(new TextResourceContents(request.uri(), null, "doc")), 60_000L, CacheScope.PUBLIC,
							null))
			.build();

		String response = json(
				request(server, McpSchema.METHOD_RESOURCES_READ, Map.of("uri", "file:///docs/intro", "_meta", meta())));

		assertThatJson(response).node("result.contents[0].text").isEqualTo("doc");
		assertThatJson(response).node("result.ttlMs").isEqualTo(60000);
		assertThatJson(response).node("result.cacheScope").isEqualTo("public");
	}

	@Test
	void readResourceFromTemplateWithQuery() throws IOException {
		McpServer server = McpServer.builder()
			.resourceTemplate(ResourceTemplate.builder("file://name/search?={search}", "search").build(),
					(ctx, request) -> new ReadResourceResult(
							List.of(new TextResourceContents(request.uri(), null, "found")), null, null, null))
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_RESOURCES_READ,
				Map.of("uri", "file://name/search?=abcd", "_meta", meta()))))
			.node("result.contents[0].text")
			.isEqualTo("found");
	}

	@Test
	void readUnknownResource() throws IOException {
		McpServer server = McpServer.builder()
			.resource(Resource.builder("file:///readme", "readme").build(),
					(ctx, request) -> new ReadResourceResult(List.of()))
			.build();

		assertThatJson(
				json(request(server, McpSchema.METHOD_RESOURCES_READ, Map.of("uri", "file:///nope", "_meta", meta()))))
			.node("error")
			.isEqualTo("""
					{"code": -32602, "message": "Resource not found", "data": {"uri": "file:///nope"}}""");
	}

	@Test
	void listResourcesAndTemplates() throws IOException {
		McpServer server = McpServer.builder()
			.resource(Resource.builder("file:///readme", "readme").mimeType("text/plain").build(),
					(ctx, request) -> new ReadResourceResult(List.of()))
			.resourceTemplate(ResourceTemplate.builder("file:///docs/{name}", "docs").build(),
					(ctx, request) -> new ReadResourceResult(List.of()))
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_RESOURCES_LIST, Map.of("_meta", meta()))))
			.node("result.resources")
			.isEqualTo("""
					[{"uri": "file:///readme", "name": "readme", "mimeType": "text/plain"}]""");
		assertThatJson(json(request(server, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, Map.of("_meta", meta()))))
			.node("result.resourceTemplates")
			.isEqualTo("""
					[{"uriTemplate": "file:///docs/{name}", "name": "docs"}]""");
	}

	// ---------------------------
	// Prompts and completions
	// ---------------------------

	@Test
	void getPrompt() throws IOException {
		McpServer server = greetingServer();

		assertThatJson(json(request(server, McpSchema.METHOD_PROMPTS_GET,
				Map.of("name", "greeting", "arguments", Map.of("name", "Ada"), "_meta", meta()))))
			.node("result.messages")
			.isEqualTo("""
					[{"role": "user", "content": {"type": "text", "text": "Hello Ada"}}]""");
	}

	@Test
	void getPromptWithMissingRequiredArguments() throws IOException {
		AtomicReference<McpRequestContext> called = new AtomicReference<>();
		Prompt prompt = Prompt.builder("greeting")
			.arguments(List.of(PromptArgument.builder("name").required(true).build(),
					PromptArgument.builder("title").required(true).build(),
					PromptArgument.builder("mood").required(false).build(), PromptArgument.builder("tone").build()))
			.build();
		McpServer server = McpServer.builder().prompt(prompt, (ctx, request) -> {
			called.set(ctx);
			return new GetPromptResult(null, List.of());
		}).build();
		Map<String, String> arguments = new HashMap<>();
		arguments.put("title", null);

		assertThatJson(json(request(server, McpSchema.METHOD_PROMPTS_GET,
				Map.of("name", "greeting", "arguments", arguments, "_meta", meta()))))
			.node("error")
			.isEqualTo("""
					{"code": -32602, "message": "Missing required arguments for prompt 'greeting': name, title"}""");
		assertThatJson(json(request(server, McpSchema.METHOD_PROMPTS_GET, Map.of("name", "greeting", "_meta", meta()))))
			.node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(called.get()).isNull();
	}

	@Test
	void getPromptWithRequiredArgumentsOnly() throws IOException {
		Prompt prompt = Prompt.builder("greeting")
			.arguments(List.of(PromptArgument.builder("name").required(true).build(),
					PromptArgument.builder("mood").build()))
			.build();
		McpServer server = McpServer.builder()
			.prompt(prompt, (ctx, request) -> new GetPromptResult(null, List.of()))
			.build();

		assertThatJson(json(request(server, McpSchema.METHOD_PROMPTS_GET,
				Map.of("name", "greeting", "arguments", Map.of("name", ""), "_meta", meta()))))
			.node("result")
			.isPresent();
	}

	@Test
	void getUnknownPrompt() throws IOException {
		McpServer server = greetingServer();

		assertThatJson(json(request(server, McpSchema.METHOD_PROMPTS_GET, Map.of("name", "nope", "_meta", meta()))))
			.node("error.code")
			.isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void completeIgnoresPromptReferenceTitle() throws IOException {
		McpServer server = McpServer.builder()
			.completion(new PromptReference("greeting"),
					(ctx, request) -> new CompleteResult(new CompleteCompletion(List.of("Alice", "Bob"), 2, false)))
			.build();

		String response = json(request(server, McpSchema.METHOD_COMPLETION_COMPLETE,
				Map.of("ref", Map.of("type", "ref/prompt", "name", "greeting", "title", "Greeting"), "argument",
						Map.of("name", "name", "value", "A"), "_meta", meta())));

		assertThatJson(response).node("result.completion").isEqualTo("""
				{"values": ["Alice", "Bob"], "total": 2, "hasMore": false}""");
		assertThatJson(response).node("result").isObject().doesNotContainKey("ttlMs");
	}

	// ---------------------------
	// Streaming handlers
	// ---------------------------

	@Test
	void streamingToolSendsProgressBeforeCompleting() throws IOException {
		McpServer server = McpServer.builder().streamingTool(Tool.builder("slow").build(), (ctx, request, sink) -> {
			sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).total(2.0).message("half").build());
			sink.progress(ProgressNotification.builder(ctx.progressToken(), 2.0).total(2.0).build());
			sink.complete(CallToolResult.builder().addTextContent("done").build());
		}).build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "slow", "_meta", metaWithProgressToken("token-1"))), notifications::add);

		assertThatJson(json(response)).node("result.content[0].text").isEqualTo("done");
		assertThat(notifications).hasSize(2);
		assertThatJson(this.mapper.writeValueAsString(notifications.get(0))).isEqualTo("""
				{
					"jsonrpc": "2.0",
					"method": "notifications/progress",
					"params": {"progressToken": "token-1", "progress": 1.0, "total": 2.0, "message": "half"}
				}""");
		assertThatJson(this.mapper.writeValueAsString(notifications.get(1))).node("params.progress").isEqualTo(2.0);
	}

	@Test
	void streamingResourceSendsProgress() throws IOException {
		McpServer server = McpServer.builder()
			.streamingResource(Resource.builder("file:///a.txt", "a").build(), (ctx, request, sink) -> {
				sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).build());
				sink.complete(new ReadResourceResult(List.of(new TextResourceContents(request.uri(), null, "a"))));
			})
			.build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ,
				RequestId.of(1), Map.of("uri", "file:///a.txt", "_meta", metaWithProgressToken(7))),
				notifications::add);

		assertThatJson(json(response)).node("result.contents[0].text").isEqualTo("a");
		assertThat(notifications).singleElement()
			.satisfies(notification -> assertThatJson(this.mapper.writeValueAsString(notification)).node("params")
				.isEqualTo("""
						{"progressToken": 7, "progress": 1.0}"""));
	}

	@Test
	void streamingResourceTemplateSendsProgress() throws IOException {
		McpServer server = McpServer.builder()
			.streamingResourceTemplate(ResourceTemplate.builder("docs://{name}", "docs").build(),
					(ctx, request, sink) -> {
						sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).build());
						sink.complete(
								new ReadResourceResult(List.of(new TextResourceContents(request.uri(), null, "doc"))));
					})
			.build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ,
				RequestId.of(1), Map.of("uri", "docs://readme", "_meta", metaWithProgressToken("t"))),
				notifications::add);

		assertThatJson(json(response)).node("result.contents[0].text").isEqualTo("doc");
		assertThat(notifications).hasSize(1);
	}

	@Test
	void streamingPromptSendsProgress() throws IOException {
		McpServer server = McpServer.builder()
			.streamingPrompt(Prompt.builder("greeting").build(), (ctx, request, sink) -> {
				sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).build());
				sink.complete(new GetPromptResult(null, List.of(new PromptMessage(Role.USER, new TextContent("Hi")))));
			})
			.build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_PROMPTS_GET,
				RequestId.of(1), Map.of("name", "greeting", "_meta", metaWithProgressToken("t"))), notifications::add);

		assertThatJson(json(response)).node("result.messages[0].content.text").isEqualTo("Hi");
		assertThat(notifications).hasSize(1);
	}

	@Test
	void progressWithoutRequestTokenIsRejected() throws IOException {
		AtomicReference<Exception> failure = new AtomicReference<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			try {
				sink.progress(ProgressNotification.builder("made-up", 1.0).build());
			}
			catch (IllegalArgumentException ex) {
				failure.set(ex);
			}
			sink.complete(CallToolResult.builder().build());
		}).build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		server.handleRequest(
				new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1), Map.of("name", "t", "_meta", meta())),
				notifications::add);

		assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class);
		assertThat(notifications).isEmpty();
	}

	@Test
	void progressWithForeignTokenIsRejected() throws IOException {
		AtomicReference<Exception> failure = new AtomicReference<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			try {
				sink.progress(ProgressNotification.builder("other", 1.0).build());
			}
			catch (IllegalArgumentException ex) {
				failure.set(ex);
			}
			sink.complete(CallToolResult.builder().build());
		}).build();

		server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "t", "_meta", metaWithProgressToken("mine"))), notification -> {
				});

		assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nonIncreasingProgressIsRejected() {
		List<Exception> failures = new ArrayList<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).build());
			for (double progress : new double[] { 1.0, 0.5, Double.NaN }) {
				try {
					sink.progress(ProgressNotification.builder(ctx.progressToken(), progress).build());
				}
				catch (IllegalArgumentException ex) {
					failures.add(ex);
				}
			}
			sink.progress(ProgressNotification.builder(ctx.progressToken(), 2.0).build());
			sink.complete(CallToolResult.builder().build());
		}).build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "t", "_meta", metaWithProgressToken("p"))), notifications::add);

		assertThat(failures).hasSize(3)
			.first()
			.extracting(Exception::getMessage)
			.isEqualTo("progress must increase with each notification, was 1.0, got 1.0");
		assertThat(notifications).<Object>extracting(n -> ((Map<?, ?>) n.params()).get("progress"))
			.containsExactly(1.0, 2.0);
	}

	@Test
	void nanFirstProgressIsRejected() {
		AtomicReference<Exception> failure = new AtomicReference<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			try {
				sink.progress(ProgressNotification.builder(ctx.progressToken(), Double.NaN).build());
			}
			catch (IllegalArgumentException ex) {
				failure.set(ex);
			}
			sink.complete(CallToolResult.builder().build());
		}).build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "t", "_meta", metaWithProgressToken("p"))), notifications::add);

		assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class);
		assertThat(notifications).isEmpty();
	}

	@Test
	void sinkIsUnusableOnceCompleted() throws IOException {
		AtomicReference<Sink<?>> captured = new AtomicReference<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			captured.set(sink);
			sink.complete(CallToolResult.builder().build());
			try {
				sink.complete(CallToolResult.builder().build());
			}
			catch (IllegalStateException ex) {
				failure.set(ex);
			}
		}).build();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "t", "_meta", metaWithProgressToken("t"))));

		assertThatJson(json(response)).node("result").isPresent();
		assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
		assertThatIllegalStateException()
			.isThrownBy(() -> captured.get().progress(ProgressNotification.builder("t", 1.0).build()));
	}

	@Test
	void handlerReturningWithoutCompletingIsInternalError() throws IOException {
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
		}).build();

		assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "t", "_meta", meta()))))
			.node("error.code")
			.isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void progressTokensAreKeptAndEchoedAsSent() throws IOException {
		List<Object> tokens = new ArrayList<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			tokens.add(ctx.progressToken());
			sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).build());
			sink.complete(CallToolResult.builder().build());
		}).build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		for (Object token : List.of(7, 10_000_000_000L, "7")) {
			server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
					Map.of("name", "t", "_meta", metaWithProgressToken(token))), notifications::add);
		}

		assertThat(tokens).containsExactly(7L, 10_000_000_000L, "7");
		assertThat(notifications).hasSize(3);
		assertThatJson(this.mapper.writeValueAsString(notifications.get(0))).node("params.progressToken")
			.isIntegralNumber()
			.isEqualTo(7);
		assertThatJson(this.mapper.writeValueAsString(notifications.get(1))).node("params.progressToken")
			.isIntegralNumber()
			.isEqualTo(10_000_000_000L);
		assertThatJson(this.mapper.writeValueAsString(notifications.get(2))).node("params.progressToken")
			.isString()
			.isEqualTo("7");
	}

	@Test
	void progressWithIntTokenMatchesIntegerProgressToken() {
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			sink.progress(ProgressNotification.builder(7, 1.0).build());
			sink.complete(CallToolResult.builder().build());
		}).build();
		List<JSONRPCNotification> notifications = new ArrayList<>();

		server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "t", "_meta", metaWithProgressToken(7))), notifications::add);

		assertThat(notifications).hasSize(1);
	}

	@Test
	void progressWithStringFormOfIntegerTokenIsRejected() {
		AtomicReference<Throwable> error = new AtomicReference<>();
		McpServer server = McpServer.builder().streamingTool(Tool.builder("t").build(), (ctx, request, sink) -> {
			try {
				sink.progress(ProgressNotification.builder("7", 1.0).build());
			}
			catch (IllegalArgumentException ex) {
				error.set(ex);
			}
			sink.complete(CallToolResult.builder().build());
		}).build();

		server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "t", "_meta", metaWithProgressToken(7))));

		assertThat(error.get()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidProgressTokenIsInvalidParams() throws IOException {
		McpServer server = echoServer();

		for (Object token : List.of(1.5, true, Map.of("not", "a token"))) {
			assertThatJson(json(request(server, McpSchema.METHOD_TOOLS_CALL,
					Map.of("name", "echo", "_meta", metaWithProgressToken(token)))))
				.node("error.code")
				.isEqualTo(ErrorCodes.INVALID_PARAMS);
		}
	}

	@Test
	void duplicateStreamingAndBlockingRegistrationsAreRejected() {
		McpServer.Builder builder = McpServer.builder()
			.tool(Tool.builder("t").build(), (ctx, request) -> CallToolResult.builder().build());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> builder.streamingTool(Tool.builder("t").build(),
					(ctx, request, sink) -> sink.complete(CallToolResult.builder().build())))
			.withMessageContaining("'t'");
	}

	// ---------------------------
	// Cancellation
	// ---------------------------

	@Test
	void interruptedHandlerIsCancelled() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		McpServer server = McpServer.builder().tool(Tool.builder("wait").build(), (ctx, request) -> {
			started.countDown();
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			return CallToolResult.builder().build();
		}).build();

		Cancelled cancelled = cancel(server, started);

		assertThatJson(json(cancelled.response())).node("error").isEqualTo("""
				{"code": -32603, "message": "Request cancelled"}""");
		assertThat(cancelled.interrupted()).isTrue();
		assertThat(cancelled.errorLogs()).isEmpty();
	}

	@Test
	void interruptStatusIsRestoredWhenHandlerClearedIt() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		McpServer server = McpServer.builder().tool(Tool.builder("wait").build(), (ctx, request) -> {
			started.countDown();
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException ex) {
				throw new IllegalStateException(ex);
			}
			return CallToolResult.builder().build();
		}).build();

		Cancelled cancelled = cancel(server, started);

		assertThatJson(json(cancelled.response())).node("error.message").isEqualTo("Request cancelled");
		assertThat(cancelled.interrupted()).isTrue();
		assertThat(cancelled.errorLogs()).isEmpty();
	}

	@Test
	void handlerCompletingDespiteInterruptReturnsItsResult() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		McpServer server = McpServer.builder().tool(Tool.builder("wait").build(), (ctx, request) -> {
			started.countDown();
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return CallToolResult.builder().addTextContent("done").build();
		}).build();

		Cancelled cancelled = cancel(server, started);

		assertThatJson(json(cancelled.response())).node("result.content[0].text").isEqualTo("done");
		assertThat(cancelled.interrupted()).isTrue();
	}

	@Test
	void failureWithoutInterruptIsNotACancellation() throws IOException {
		McpServer server = McpServer.builder().tool(Tool.builder("fail").build(), (ctx, request) -> {
			throw new IllegalStateException("boom");
		}).build();

		JSONRPCResponse response = server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
				Map.of("name", "fail", "_meta", meta())));

		assertThatJson(json(response)).node("error.message").isEqualTo("Internal error");
		assertThat(Thread.interrupted()).isFalse();
	}

	// ---------------------------
	// Builder
	// ---------------------------

	@Test
	void duplicateRegistrationsAreRejected() {
		McpServer.Builder builder = McpServer.builder()
			.tool(Tool.builder("t").build(), (ctx, request) -> CallToolResult.builder().build());

		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> builder.tool(Tool.builder("t").build(), (ctx, request) -> CallToolResult.builder().build()))
			.withMessageContaining("'t'");
	}

	@Test
	void cacheHintsAreConfigurable() throws IOException {
		McpServer server = McpServer.builder().cacheHints(Duration.ofMinutes(5), CacheScope.PUBLIC).build();

		JSONRPCResponse response = request(server, McpSchema.METHOD_SERVER_DISCOVER, Map.of("_meta", meta()));

		assertThatJson(json(response)).node("result.ttlMs").isEqualTo(300000);
		assertThatJson(json(response)).node("result.cacheScope").isEqualTo("public");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpServer.builder().cacheHints(Duration.ofSeconds(-1), CacheScope.PUBLIC));
	}

	// ---------------------------
	// Helpers
	// ---------------------------

	private static Map<String, Object> meta() {
		return Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION, MetaKeys.CLIENT_CAPABILITIES,
				Map.of());
	}

	private static Map<String, Object> metaWithClientCapabilities(Map<String, Object> clientCapabilities) {
		Map<String, Object> meta = new HashMap<>(meta());
		meta.put(MetaKeys.CLIENT_CAPABILITIES, clientCapabilities);
		return meta;
	}

	private static Map<String, Object> metaWithProgressToken(Object progressToken) {
		Map<String, Object> meta = new HashMap<>(meta());
		meta.put(MetaKeys.PROGRESS_TOKEN, progressToken);
		return meta;
	}

	private static McpServer echoServer() {
		return McpServer.builder()
			.serverInfo("test-server", "1.2.3")
			.tool(Tool.builder("echo").build(),
					(ctx, request) -> CallToolResult.builder()
						.addTextContent(String.valueOf(request.arguments().get("message")))
						.build())
			.build();
	}

	/**
	 * A server with an {@code ask} tool that returns the given input requests.
	 */
	private static McpServer inputRequiredServer(InputRequest... inputRequests) {
		return McpServer.builder().tool(Tool.builder("ask").build(), (ctx, request) -> {
			InputRequiredResult.Builder result = InputRequiredResult.builder();
			for (int i = 0; i < inputRequests.length; i++) {
				result.addInputRequest("input-" + i, inputRequests[i]);
			}
			return result.build();
		}).build();
	}

	/**
	 * A server with a {@code weather} tool, with an output schema, that returns the given
	 * outcome.
	 */
	private static McpServer weatherServer(McpSchema.CallToolOutcome outcome) {
		Tool tool = Tool.builder("weather")
			.outputSchema(Map.of("type", "object", "properties", Map.of("temperature", Map.of("type", "number")),
					"required", List.of("temperature")))
			.build();
		return McpServer.builder().tool(tool, (ctx, request) -> outcome).build();
	}

	private static McpServer greetingServer() {
		return McpServer.builder()
			.prompt(Prompt.builder("greeting").build(), (ctx, request) -> new GetPromptResult(null,
					List.of(new PromptMessage(Role.USER, new TextContent("Hello " + request.arguments().get("name"))))))
			.build();
	}

	/**
	 * Calls the {@code wait} tool on a virtual thread, as a transport does, and
	 * interrupts that thread once the handler has started.
	 */
	private static Cancelled cancel(McpServer server, CountDownLatch started) throws InterruptedException {
		Logger logger = (Logger) LoggerFactory.getLogger(McpServer.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			AtomicReference<JSONRPCResponse> response = new AtomicReference<>();
			AtomicBoolean interrupted = new AtomicBoolean();
			Thread thread = Thread.ofVirtual().start(() -> {
				response.set(server.handleRequest(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, RequestId.of(1),
						Map.of("name", "wait", "_meta", meta()))));
				interrupted.set(Thread.currentThread().isInterrupted());
			});
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			thread.interrupt();
			assertThat(thread.join(Duration.ofSeconds(5))).isTrue();
			List<ILoggingEvent> errorLogs = logs.list.stream()
				.filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
				.toList();
			return new Cancelled(response.get(), interrupted.get(), errorLogs);
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}
	}

	private record Cancelled(JSONRPCResponse response, boolean interrupted, List<ILoggingEvent> errorLogs) {
	}

	private static JSONRPCResponse request(McpServer server, String method, Map<String, Object> params) {
		return server.handleRequest(new JSONRPCRequest(method, RequestId.of(1), params));
	}

	private String json(JSONRPCResponse response) throws IOException {
		return this.mapper.writeValueAsString(response);
	}

}
