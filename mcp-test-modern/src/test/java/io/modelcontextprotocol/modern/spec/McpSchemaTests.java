/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.spec;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.spec.McpSchema.Annotations;
import io.modelcontextprotocol.modern.spec.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.CancelledNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.Content;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitFormParams;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitUrlParams;
import io.modelcontextprotocol.modern.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.modern.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCErrorResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResultResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.spec.McpSchema.RequestId;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplateReference;
import io.modelcontextprotocol.modern.spec.McpSchema.TextContent;
import io.modelcontextprotocol.modern.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpSchemaTests {

	private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

	// ---------------------------
	// JSON-RPC envelope
	// ---------------------------

	@Test
	void deserializeRequest() throws IOException {
		JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.mapper, """
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{}}}""");

		assertThat(message).isInstanceOfSatisfying(JSONRPCRequest.class, request -> {
			assertThat(request.id()).isEqualTo(RequestId.of(1));
			assertThat(request.method()).isEqualTo("tools/list");
			assertThat(request.params()).isEqualTo(Map.of("_meta", Map.of()));
		});
	}

	@Test
	void deserializeNotification() throws IOException {
		JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.mapper, """
				{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"abc"}}""");

		assertThat(message).isInstanceOfSatisfying(JSONRPCNotification.class,
				notification -> assertThat(notification.method()).isEqualTo("notifications/cancelled"));
	}

	@Test
	void deserializeResponses() throws IOException {
		assertThat(McpSchema.deserializeJsonRpcMessage(this.mapper, """
				{"jsonrpc":"2.0","id":"a","result":{}}""")).isInstanceOf(JSONRPCResultResponse.class);
		assertThat(McpSchema.deserializeJsonRpcMessage(this.mapper, """
				{"jsonrpc":"2.0","id":"a","error":{"code":-32601,"message":"Method not found"}}"""))
			.isInstanceOfSatisfying(JSONRPCErrorResponse.class,
					response -> assertThat(response.error().code()).isEqualTo(-32601));
	}

	@Test
	void requestRejectsInvalidId() {
		assertThatIllegalArgumentException().isThrownBy(() -> new JSONRPCRequest("tools/list", null, null));
		assertThatException().isThrownBy(() -> McpSchema.deserializeJsonRpcMessage(this.mapper, """
				{"jsonrpc":"2.0","id":1.5,"method":"tools/list"}"""));
		assertThatException().isThrownBy(() -> McpSchema.deserializeJsonRpcMessage(this.mapper, """
				{"jsonrpc":"2.0","id":null,"method":"tools/list"}"""));
	}

	@Test
	void requestIdsAreSerializedAsTheirValue() throws IOException {
		assertThat(this.mapper.writeValueAsString(RequestId.of(7))).isEqualTo("7");
		assertThat(this.mapper.writeValueAsString(RequestId.of(10_000_000_000L))).isEqualTo("10000000000");
		assertThat(this.mapper.writeValueAsString(RequestId.of("abc"))).isEqualTo("\"abc\"");
		assertThatJson(this.mapper.writeValueAsString(new JSONRPCRequest("tools/list", RequestId.of(7), null)))
			.isEqualTo("""
					{"jsonrpc": "2.0", "method": "tools/list", "id": 7}""");
		assertThatJson(this.mapper.writeValueAsString(new JSONRPCResultResponse(RequestId.of("7"), Map.of())))
			.isEqualTo("""
					{"jsonrpc": "2.0", "id": "7", "result": {}}""");
	}

	@Test
	void requestIdsAreDeserializedFromStringsOrIntegers() throws IOException {
		assertThat(this.mapper.readValue("7", RequestId.class)).isEqualTo(RequestId.of(7));
		assertThat(this.mapper.readValue("10000000000", RequestId.class)).isEqualTo(RequestId.of(10_000_000_000L));
		assertThat(this.mapper.readValue("\"abc\"", RequestId.class)).isEqualTo(RequestId.of("abc"));
		assertThat(this.mapper.convertValue(Map.of("requestId", 7L), CancelledNotification.class).requestId())
			.isEqualTo(RequestId.of(7));
		for (String invalid : List.of("1.5", "true", "{}", "[1]", "100000000000000000000")) {
			assertThatException().as(invalid).isThrownBy(() -> this.mapper.readValue(invalid, RequestId.class));
		}
	}

	@Test
	void requestIdEquality() {
		var a = 7;
		Integer boxedInt = 1337;
		Long boxedLong = 1337L;
		short s = 1337;
		assertThat(RequestId.of(1337)).isEqualTo(RequestId.of(1330 + a))
			.hasSameHashCodeAs(RequestId.of(1330 + a))
			.isEqualTo(RequestId.of(boxedInt))
			.isEqualTo(RequestId.of(boxedLong))
			.isEqualTo(RequestId.of(s))
			.isEqualTo(RequestId.fromJson(1337))
			.isEqualTo(RequestId.fromJson(1337L));
		// Outside the Long cache: equality does not depend on identity
		assertThat(RequestId.of(100_000)).isEqualTo(RequestId.of(99_999 + 1));
		assertThat(RequestId.of("abc")).isEqualTo(RequestId.of(new StringBuilder("ab").append("c").toString()));
		assertThat(RequestId.of("7")).isNotEqualTo(RequestId.of(7));
		assertThat(Map.of(RequestId.of(1330 + a), "in-flight")).containsKey(RequestId.fromJson(1337));
	}

	@Test
	void errorResponseAllowsNullId() throws IOException {
		var response = new JSONRPCErrorResponse(null, new McpSchema.JSONRPCError(-32700, "Parse error"));

		assertThatJson(this.mapper.writeValueAsString(response)).isEqualTo("""
				{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}""");
	}

	// ---------------------------
	// Content polymorphism
	// ---------------------------

	@Test
	void contentIsSerializedWithType() throws IOException {
		assertThatJson(this.mapper.writeValueAsString(new TextContent("hello"))).isEqualTo("""
				{"type":"text","text":"hello"}""");
		assertThatJson(this.mapper.writeValueAsString(new ImageContent("aGVsbG8=", "image/png"))).isEqualTo("""
				{"type":"image","data":"aGVsbG8=","mimeType":"image/png"}""");
	}

	@Test
	void contentIsDeserializedByType() throws IOException {
		Content text = this.mapper.readValue("""
				{"type":"text","text":"hello"}""", Content.class);
		Content embedded = this.mapper.readValue("""
				{"type":"resource","resource":{"uri":"file:///a","blob":"aGVsbG8="}}""", Content.class);

		assertThat(text).isEqualTo(new TextContent("hello"));
		assertThat(embedded).isInstanceOfSatisfying(EmbeddedResource.class, resource -> assertThat(resource.resource())
			.isEqualTo(new BlobResourceContents("file:///a", null, "aGVsbG8=")));
	}

	@Test
	void callToolResultContentRoundTrips() throws IOException {
		var result = CallToolResult.builder()
			.addTextContent("hello")
			.addContent(new EmbeddedResource(new TextResourceContents("file:///a", "text/plain", "content")))
			.build();

		String json = this.mapper.writeValueAsString(result);

		assertThatJson(json).isEqualTo("""
				{"content":[
					{"type":"text","text":"hello"},
					{"type":"resource","resource":{"uri":"file:///a","mimeType":"text/plain","text":"content"}}
				]}""");
		assertThat(this.mapper.readValue(json, CallToolResult.class)).isEqualTo(result);
	}

	@Test
	void completeReferenceIsDeserializedByType() throws IOException {
		CompleteRequest prompt = this.mapper.readValue("""
				{"ref":{"type":"ref/prompt","name":"greeting"},"argument":{"name":"n","value":"v"}}""",
				CompleteRequest.class);
		CompleteRequest template = this.mapper.readValue("""
				{"ref":{"type":"ref/resource","uri":"file:///{path}"},"argument":{"name":"path","value":"a"}}""",
				CompleteRequest.class);

		assertThat(prompt.ref()).isEqualTo(new PromptReference("greeting"));
		assertThat(template.ref()).isEqualTo(new ResourceTemplateReference("file:///{path}"));
	}

	// ---------------------------
	// MRTR
	// ---------------------------

	@Test
	void inputRequiredResultIsSerializedWithMethodAndMode() throws IOException {
		var result = InputRequiredResult.builder()
			.addInputRequest("confirm",
					ElicitRequest.form("Proceed?", Map.of("type", "object", "properties", Map.of())))
			.addInputRequest("login", ElicitRequest.url("Log in", "https://example.com/login"))
			.requestState("state")
			.build();

		assertThatJson(this.mapper.writeValueAsString(result)).isEqualTo("""
				{
					"inputRequests": {
						"confirm": {
							"method": "elicitation/create",
							"params": {"mode": "form", "message": "Proceed?",
								"requestedSchema": {"type": "object", "properties": {}}}
						},
						"login": {
							"method": "elicitation/create",
							"params": {"mode": "url", "message": "Log in", "url": "https://example.com/login"}
						}
					},
					"requestState": "state"
				}""");
		assertThat(result.resultType()).isEqualTo(McpSchema.RESULT_TYPE_INPUT_REQUIRED);
	}

	@Test
	void inputRequiredResultRequiresInputRequestsOrRequestState() {
		assertThatIllegalArgumentException().isThrownBy(() -> InputRequiredResult.builder().build())
			.withMessage("at least one of inputRequests or requestState must not be null");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> InputRequiredResult.builder().requestState(null).meta(Map.of("k", "v")).build());
		assertThatIllegalArgumentException().isThrownBy(() -> new InputRequiredResult(null, null))
			.withMessage("at least one of inputRequests or requestState must not be null");
		assertThatIllegalArgumentException().isThrownBy(() -> new InputRequiredResult(null, null, Map.of("k", "v")));

		assertThat(InputRequiredResult.builder().requestState("state").build())
			.isEqualTo(new InputRequiredResult(null, "state"));
		assertThat(InputRequiredResult.builder().inputRequests(Map.of()).build())
			.isEqualTo(new InputRequiredResult(Map.of(), null));
	}

	@Test
	void elicitParamsWithoutModeAreFormMode() throws IOException {
		ElicitRequest request = this.mapper.readValue("""
				{"method":"elicitation/create","params":{"message":"Name?","requestedSchema":{"type":"object"}}}""",
				ElicitRequest.class);
		ElicitRequest url = this.mapper.readValue("""
				{"method":"elicitation/create","params":{"mode":"url","message":"Log in","url":"https://a"}}""",
				ElicitRequest.class);

		assertThat(request.params()).isInstanceOf(ElicitFormParams.class);
		assertThat(url.params()).isEqualTo(new ElicitUrlParams("Log in", "https://a"));
	}

	@Test
	void callToolRequestCarriesInputResponses() throws IOException {
		CallToolRequest request = this.mapper.readValue("""
				{
					"name": "deploy",
					"arguments": {"env": "prod"},
					"inputResponses": {"confirm": {"action": "accept", "content": {"ok": true}}},
					"requestState": "state",
					"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}
				}""", CallToolRequest.class);

		assertThat(request.requestState()).isEqualTo("state");
		ElicitResult confirm = this.mapper.convertValue(request.inputResponses().get("confirm"), ElicitResult.class);
		assertThat(confirm.action()).isEqualTo(ElicitResult.Action.ACCEPT);
		assertThat(confirm.content()).containsEntry("ok", true);
	}

	// ---------------------------
	// Required fields (CONTRIBUTING.md, Case B)
	// ---------------------------

	@Test
	void requiredFieldsAreRejectedAtConstruction() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new Tool(null, null, null, Map.of(), null, null, null, null));
		assertThatIllegalArgumentException().isThrownBy(() -> new Tool("t", null, null, null, null, null, null, null));
		assertThatIllegalArgumentException().isThrownBy(() -> new CallToolRequest(null, Map.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> new TextContent(null));
		assertThatIllegalArgumentException().isThrownBy(() -> Tool.builder(""));
		assertThatIllegalArgumentException().isThrownBy(() -> ResourceLink.builder("file:///a", ""));
		assertThatIllegalArgumentException().isThrownBy(() -> ProgressNotification.builder("token", null));
	}

	@Test
	void missingRequiredFieldsDeserializeToDefaults() throws IOException {
		Tool tool = this.mapper.readValue("{}", Tool.class);
		CallToolResult result = this.mapper.readValue("{}", CallToolResult.class);
		ListToolsResult list = this.mapper.readValue("{}", ListToolsResult.class);

		assertThat(tool.name()).isEmpty();
		assertThat(tool.inputSchema()).isEqualTo(Map.of("type", "object"));
		assertThat(result.content()).isEmpty();
		assertThat(list.tools()).isEmpty();
	}

	@Test
	void progressTokensAreStringsOrIntegers() throws IOException {
		assertThat(this.mapper.readValue("""
				{"progressToken":"abc","progress":1}""", ProgressNotification.class).progressToken()).isEqualTo("abc");
		assertThat(this.mapper.readValue("""
				{"progressToken":7,"progress":1}""", ProgressNotification.class).progressToken()).isEqualTo(7L);
		assertThat(this.mapper.readValue("""
				{"progressToken":10000000000,"progress":1}""", ProgressNotification.class).progressToken())
			.isEqualTo(10000000000L);
		assertThat(this.mapper.readValue("""
				{"progress":1}""", ProgressNotification.class).progressToken()).isEqualTo("");
		assertThatException().isThrownBy(() -> this.mapper.readValue("""
				{"progressToken":1.5,"progress":1}""", ProgressNotification.class));
		assertThatException().isThrownBy(() -> this.mapper.readValue("""
				{"progressToken":true,"progress":1}""", ProgressNotification.class));
	}

	@Test
	void cancelledRequestIdsAreStringsOrIntegers() throws IOException {
		assertThat(this.mapper.readValue("""
				{"requestId":"abc"}""", CancelledNotification.class).requestId()).isEqualTo(RequestId.of("abc"));
		assertThat(this.mapper.readValue("""
				{"requestId":7}""", CancelledNotification.class).requestId()).isEqualTo(RequestId.of(7));
		assertThat(this.mapper.readValue("""
				{"requestId":10000000000}""", CancelledNotification.class).requestId())
			.isEqualTo(RequestId.of(10_000_000_000L));
		assertThat(this.mapper.readValue("{}", CancelledNotification.class).requestId()).isEqualTo(RequestId.of(""));
		assertThatException().isThrownBy(() -> this.mapper.readValue("""
				{"requestId":1.5}""", CancelledNotification.class));
		assertThatException().isThrownBy(() -> this.mapper.readValue("""
				{"requestId":true}""", CancelledNotification.class));
	}

	@Test
	void integerProgressTokensAreHeldAsLong() {
		assertThat(ProgressNotification.builder(7, 1.0).build().progressToken()).isEqualTo(7L);
	}

	@Test
	void integerProgressTokensAndRequestIdsAreSerializedAsIntegers() throws IOException {
		assertThatJson(this.mapper.writeValueAsString(ProgressNotification.builder(7, 1.0).build())).isEqualTo("""
				{"progressToken": 7, "progress": 1.0}""");
		assertThatJson(this.mapper.writeValueAsString(CancelledNotification.builder(RequestId.of(7)).build()))
			.isEqualTo("""
					{"requestId": 7}""");
		assertThatIllegalArgumentException().isThrownBy(() -> ProgressNotification.builder(1.5, 1.0));
	}

	@Test
	void unknownFieldsAreIgnored() throws IOException {
		Tool tool = this.mapper.readValue("""
				{"name":"t","inputSchema":{"type":"object"},"futureField":42}""", Tool.class);

		assertThat(tool.name()).isEqualTo("t");
	}

	// ---------------------------
	// SDK-managed and optional fields
	// ---------------------------

	@Test
	void resultTypeIsNotARecordComponent() throws IOException {
		String json = this.mapper.writeValueAsString(CallToolResult.builder().addTextContent("a").build());

		assertThatJson(json).isObject().doesNotContainKey("resultType");
	}

	@Test
	void nullFieldsAreOmitted() throws IOException {
		String json = this.mapper.writeValueAsString(Tool.builder("t").build());

		assertThatJson(json).isEqualTo("""
				{"name":"t","inputSchema":{"type":"object"}}""");
	}

	@Test
	void buildersMatchCanonicalConstructors() {
		var annotations = Annotations.builder().priority(0.5).build();

		assertThat(
				ResourceLink.builder("file:///a", "a").mimeType("text/plain").annotations(annotations).size(1L).build())
			.isEqualTo(new ResourceLink("file:///a", "a", null, null, "text/plain", annotations, 1L, null, null));
		assertThat(ProgressNotification.builder("token", 0.5).total(1.0).message("m").build())
			.isEqualTo(new ProgressNotification("token", 0.5, 1.0, "m", null));
	}

	@Test
	void enumsUseWireValues() throws IOException {
		var result = new ReadResourceResult(List.of(new TextResourceContents("file:///a", null, "a")), 1000L,
				CacheScope.PUBLIC, null);

		String json = this.mapper.writeValueAsString(result);

		assertThatJson(json).node("cacheScope").isEqualTo("public");
		assertThat(this.mapper.readValue(json, ReadResourceResult.class)).isEqualTo(result);
		assertThatJson(this.mapper.writeValueAsString(new ElicitResult(ElicitResult.Action.DECLINE, null)))
			.isEqualTo(json("""
					{"action":"decline"}"""));
	}

}
