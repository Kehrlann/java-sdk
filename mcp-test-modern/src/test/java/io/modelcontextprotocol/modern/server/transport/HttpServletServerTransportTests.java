/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.spec.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

class HttpServletServerTransportTests {

	private static final String VERSION = McpSchema.LATEST_PROTOCOL_VERSION;

	private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

	private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final CountDownLatch release = new CountDownLatch(1);

	private final CountDownLatch interrupted = new CountDownLatch(1);

	private static final DelegatingServlet servlet = new DelegatingServlet();

	private static @Nullable Tomcat tomcat;

	private static int port;

	/**
	 * Tomcat is started once, serving the transport each test builds with
	 * {@link #start(UnaryOperator)}.
	 */
	@BeforeAll
	static void startTomcat(@TempDir Path baseDir) throws LifecycleException {
		Tomcat server = new Tomcat();
		server.setPort(0);
		server.setBaseDir(baseDir.toString());
		Context context = server.addContext("", baseDir.toString());
		// Initialized on start, so that its config is there to initialize the transports
		Tomcat.addServlet(context, "mcp", servlet).setLoadOnStartup(1);
		context.addServletMappingDecoded("/mcp", "mcp");
		server.getConnector();
		server.start();
		tomcat = server;
		port = server.getConnector().getLocalPort();
	}

	@AfterAll
	static void stopTomcat() throws LifecycleException {
		if (tomcat != null) {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	@AfterEach
	void tearDown() {
		this.release.countDown();
	}

	@Test
	void answersARequestWithJson() throws Exception {
		start();

		HttpResponse<String> response = post(toolCall(1, "echo", Map.of("message", "hello")));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(contentType -> assertThat(contentType).startsWith("application/json"));
		assertThatJson(response.body()).node("id").isEqualTo(1);
		assertThatJson(response.body()).node("result.content[0].text").isEqualTo("hello");
	}

	@Test
	void streamsNotificationsBeforeTheResponse() throws Exception {
		start();

		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROGRESS_TOKEN, "token");
		HttpResponse<String> response = post(
				request(1, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "progress", "_meta", meta)));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(contentType -> assertThat(contentType).startsWith("text/event-stream"));
		assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
		List<String> events = sseData(response.body());
		assertThat(events).hasSize(4);
		for (int i = 0; i < 3; i++) {
			assertThatJson(events.get(i)).node("method").isEqualTo(McpSchema.METHOD_NOTIFICATION_PROGRESS);
			assertThatJson(events.get(i)).node("params.progress").isEqualTo((double) (i + 1));
		}
		assertThatJson(events.get(3)).node("id").isEqualTo(1);
		assertThatJson(events.get(3)).node("result").isPresent();
	}

	@Test
	void answersUnknownMethodsWith404() throws Exception {
		start();

		HttpResponse<String> response = post(request(1, "unknown/method", Map.of("_meta", meta())));

		assertThat(response.statusCode()).isEqualTo(404);
		assertThatJson(response.body()).node("id").isEqualTo(1);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void answersUnsupportedProtocolVersionsWith400() throws Exception {
		start();

		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROTOCOL_VERSION, "v999.0.0");
		HttpResponse<String> response = post(request(1, McpSchema.METHOD_SERVER_DISCOVER, Map.of("_meta", meta)),
				builder -> builder.setHeader(HttpServletServerTransport.PROTOCOL_VERSION_HEADER, "v999.0.0"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION);
		assertThatJson(response.body()).node("error.data.requested").isEqualTo("v999.0.0");
	}

	@Test
	void answersMissingMetaWith400BeforeCheckingHeaders() throws Exception {
		start();

		HttpResponse<String> missingMeta = post(request(1, McpSchema.METHOD_SERVER_DISCOVER, Map.of()));
		HttpResponse<String> missingCapabilities = post(
				request(2, McpSchema.METHOD_SERVER_DISCOVER,
						Map.of("_meta", Map.of(MetaKeys.PROTOCOL_VERSION, VERSION))),
				builder -> builder.setHeader(HttpServletServerTransport.PROTOCOL_VERSION_HEADER, "other"));

		assertThat(missingMeta.statusCode()).isEqualTo(400);
		assertThatJson(missingMeta.body()).node("error.code").isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(missingCapabilities.statusCode()).isEqualTo(400);
		assertThatJson(missingCapabilities.body()).node("error.code").isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void answersMissingClientCapabilitiesWith400() throws Exception {
		start();

		HttpResponse<String> response = post(toolCall(1, "elicit", Map.of()));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);
	}

	@Test
	void answersInternalErrorsWith200() throws Exception {
		start();

		HttpResponse<String> response = post(toolCall(1, "fail", Map.of()));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void rejectsAProtocolVersionHeaderNotMatchingTheBody() throws Exception {
		start();

		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROTOCOL_VERSION, "v999.0.0");
		HttpResponse<String> response = post(request(1, McpSchema.METHOD_SERVER_DISCOVER, Map.of("_meta", meta)),
				builder -> builder.setHeader(HttpServletServerTransport.PROTOCOL_VERSION_HEADER, VERSION));

		assertHeaderMismatch(response, 1);
	}

	@Test
	void rejectsAMissingProtocolVersionHeader() throws Exception {
		start();

		HttpResponse<String> response = post(request(1, McpSchema.METHOD_SERVER_DISCOVER, Map.of("_meta", meta())),
				builder -> headers(builder,
						Map.of(HttpServletServerTransport.METHOD_HEADER, McpSchema.METHOD_SERVER_DISCOVER)));

		assertHeaderMismatch(response, 1);
	}

	@Test
	void rejectsAMethodHeaderNotMatchingTheBody() throws Exception {
		start();

		HttpResponse<String> wrongMethod = post(request(1, McpSchema.METHOD_TOOLS_LIST, Map.of("_meta", meta())),
				builder -> builder.setHeader(HttpServletServerTransport.METHOD_HEADER, McpSchema.METHOD_PROMPTS_LIST));
		HttpResponse<String> wrongCase = post(request(2, McpSchema.METHOD_TOOLS_LIST, Map.of("_meta", meta())),
				builder -> builder.setHeader(HttpServletServerTransport.METHOD_HEADER, "TOOLS/LIST"));
		HttpResponse<String> missing = post(request(3, McpSchema.METHOD_TOOLS_LIST, Map.of("_meta", meta())),
				builder -> headers(builder, Map.of(HttpServletServerTransport.PROTOCOL_VERSION_HEADER, VERSION)));

		assertHeaderMismatch(wrongMethod, 1);
		assertHeaderMismatch(wrongCase, 2);
		assertHeaderMismatch(missing, 3);
	}

	@Test
	void rejectsARepeatedHeader() throws Exception {
		start();

		HttpResponse<String> response = post(request(1, McpSchema.METHOD_TOOLS_LIST, Map.of("_meta", meta())),
				builder -> builder.header(HttpServletServerTransport.METHOD_HEADER, McpSchema.METHOD_TOOLS_LIST));

		assertHeaderMismatch(response, 1);
	}

	@Test
	void rejectsANameHeaderNotMatchingTheBody() throws Exception {
		start();

		HttpResponse<String> wrongName = post(toolCall(1, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader(HttpServletServerTransport.NAME_HEADER, "wrong_tool_name"));
		HttpResponse<String> missing = post(toolCall(2, "echo", Map.of("message", "hello")),
				builder -> headers(builder, Map.of(HttpServletServerTransport.PROTOCOL_VERSION_HEADER, VERSION,
						HttpServletServerTransport.METHOD_HEADER, McpSchema.METHOD_TOOLS_CALL)));
		HttpResponse<String> invalidBase64 = post(toolCall(3, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader(HttpServletServerTransport.NAME_HEADER, "=?base64?ZWNo!!!?="));

		assertHeaderMismatch(wrongName, 1);
		assertHeaderMismatch(missing, 2);
		assertHeaderMismatch(invalidBase64, 3);
	}

	@Test
	void acceptsANameHeaderWithSurroundingWhitespace() throws Exception {
		start();

		HttpResponse<String> response = post(toolCall(1, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader(HttpServletServerTransport.NAME_HEADER, "  echo  "));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThatJson(response.body()).node("result.content[0].text").isEqualTo("hello");
	}

	@Test
	void decodesABase64NameHeader() throws Exception {
		start();

		String uri = "test://café";
		String encoded = "=?base64?" + Base64.getEncoder().encodeToString(uri.getBytes(StandardCharsets.UTF_8)) + "?=";
		HttpResponse<String> response = post(
				request(1, McpSchema.METHOD_RESOURCES_READ, Map.of("uri", uri, "_meta", meta())),
				builder -> builder.setHeader(HttpServletServerTransport.NAME_HEADER, encoded));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThatJson(response.body()).node("result.contents[0].text").isEqualTo(uri);
	}

	@Test
	void acceptsNotificationsWith202() throws Exception {
		start();

		HttpResponse<String> response = post(this.mapper.writeValueAsString(
				Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 1))),
				builder -> headers(builder, Map.of()));

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.body()).isEmpty();
	}

	@Test
	void answersOtherMethodsWith405() throws Exception {
		start();

		for (String method : List.of("GET", "DELETE", "PUT")) {
			HttpResponse<String> response = this.httpClient.send(HttpRequest.newBuilder(endpoint())
				.method(method, HttpRequest.BodyPublishers.noBody())
				.header("Accept", "application/json, text/event-stream")
				.build(), HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).as(method).isEqualTo(405);
			assertThat(response.headers().firstValue("Allow")).as(method).hasValue("POST");
		}
	}

	@Test
	void rejectsInvalidBodies() throws Exception {
		start();

		HttpResponse<String> invalidJson = post("{not json", builder -> headers(builder, Map.of()));
		HttpResponse<String> batch = post("[" + toolCall(1, "echo", Map.of("message", "hello")) + "]",
				builder -> headers(builder, Map.of()));
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
				builder -> headers(builder, Map.of()));

		assertThat(invalidJson.statusCode()).isEqualTo(400);
		assertThatJson(invalidJson.body()).node("error.code").isEqualTo(ErrorCodes.PARSE_ERROR);
		assertThat(batch.statusCode()).isEqualTo(400);
		assertThatJson(batch.body()).node("error.code").isEqualTo(ErrorCodes.INVALID_REQUEST);
		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.INVALID_REQUEST);
	}

	@Test
	void rejectsRequestsNotAcceptingJsonAndEventStreams() throws Exception {
		start();

		HttpResponse<String> jsonOnly = post(toolCall(1, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Accept", "application/json"));
		HttpResponse<String> wildcard = post(toolCall(2, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Accept", "*/*"));
		HttpResponse<String> reversed = post(toolCall(3, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Accept", "text/event-stream, application/json;q=0.9"));

		assertThat(jsonOnly.statusCode()).isEqualTo(406);
		assertThat(wildcard.statusCode()).isEqualTo(200);
		assertThat(reversed.statusCode()).isEqualTo(200);
	}

	@Test
	void rejectsBodiesThatAreNotJson() throws Exception {
		start();

		HttpResponse<String> response = post(toolCall(1, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Content-Type", "text/plain"));
		HttpResponse<String> withCharset = post(toolCall(2, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Content-Type", "application/json; charset=utf-8"));

		assertThat(response.statusCode()).isEqualTo(415);
		assertThat(withCharset.statusCode()).isEqualTo(200);
	}

	@Test
	void rejectsBodiesLargerThanTheMaximumSize() throws Exception {
		start(builder -> builder.maxRequestSize(1024));

		HttpResponse<String> response = post(toolCall(1, "echo", Map.of("message", "x".repeat(2048))));

		assertThat(response.statusCode()).isEqualTo(413);
	}

	@Test
	void checksTheOrigin() throws Exception {
		start();

		HttpResponse<String> evil = post(toolCall(1, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Origin", "http://evil.example.com"));
		HttpResponse<String> localhost = post(toolCall(2, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Origin", "http://localhost:" + port));

		assertThat(evil.statusCode()).isEqualTo(403);
		assertThat(localhost.statusCode()).isEqualTo(200);
	}

	@Test
	void checksTheOriginAgainstTheAllowedOrigins() throws Exception {
		start(builder -> builder.allowedOrigins(List.of("https://example.com")));

		HttpResponse<String> allowed = post(toolCall(1, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Origin", "https://example.com"));
		HttpResponse<String> localhost = post(toolCall(2, "echo", Map.of("message", "hello")),
				builder -> builder.setHeader("Origin", "http://localhost:" + port));

		assertThat(allowed.statusCode()).isEqualTo(200);
		assertThat(localhost.statusCode()).isEqualTo(403);
	}

	@Test
	void checksTheHostAgainstTheAllowedHosts() throws Exception {
		start(builder -> builder.allowedHosts(List.of("localhost:*")));

		try (Socket evil = rawPost("evil.example.com", toolCall(1, "echo", Map.of("message", "hello")));
				Socket localhost = rawPost("localhost:" + port, toolCall(2, "echo", Map.of("message", "hello")))) {
			assertThat(reader(evil).readLine()).startsWith("HTTP/1.1 403");
			assertThat(reader(localhost).readLine()).startsWith("HTTP/1.1 200");
		}
	}

	@Test
	void closingTheResponseStreamCancelsTheRequest() throws Exception {
		start();

		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROGRESS_TOKEN, "token");
		String body = request(1, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "stream", "_meta", meta));
		try (Socket socket = rawPost("localhost:" + port, body)) {
			BufferedReader reader = reader(socket);
			String line;
			do {
				line = reader.readLine();
			}
			while (line != null && !line.startsWith("data:"));
			assertThat(line).contains(McpSchema.METHOD_NOTIFICATION_PROGRESS);
		}

		assertThat(this.interrupted.await(5, TimeUnit.SECONDS)).as("handler interrupted").isTrue();
	}

	@Test
	void passesTheTransportContextToHandlers() throws Exception {
		start(builder -> builder.contextExtractor(
				request -> McpTransportContext.create(Map.of("user-agent", request.getHeader("User-Agent")))));

		HttpResponse<String> response = post(toolCall(1, "context", Map.of()),
				builder -> builder.setHeader("User-Agent", "test-agent"));

		assertThatJson(response.body()).node("result.content[0].text").isEqualTo("test-agent");
	}

	@Test
	void handlesRequestsOnTheRequestThreads() throws Exception {
		List<Thread> created = new ArrayList<>();
		start(builder -> builder.requestThreadFactory(runnable -> {
			Thread thread = Thread.ofVirtual().name("custom-request").unstarted(runnable);
			created.add(thread);
			return thread;
		}));

		HttpResponse<String> response = post(toolCall(1, "thread", Map.of()));

		assertThatJson(response.body()).node("result.content[0].text").isEqualTo("custom-request");
		assertThat(created).hasSize(1);
	}

	@Test
	void answersAnInternalErrorWhenNoThreadIsCreated() throws Exception {
		start(builder -> builder.requestThreadFactory(runnable -> null));

		HttpResponse<String> response = post(toolCall(1, "echo", Map.of("message", "hello")));

		assertThatJson(response.body()).node("id").isEqualTo(1);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	private static void assertHeaderMismatch(HttpResponse<String> response, int id) {
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(contentType -> assertThat(contentType).startsWith("application/json"));
		assertThatJson(response.body()).node("id").isEqualTo(id);
		assertThatJson(response.body()).node("error.code").isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	/**
	 * A server with an {@code echo} tool, a {@code progress} tool sending 3
	 * notifications, a {@code stream} tool sending progress until interrupted, an
	 * {@code elicit} tool requiring elicitation, a {@code fail} tool throwing, and
	 * {@code context} and {@code thread} tools returning the {@code user-agent} of the
	 * transport context and the name of their thread. Resources are read by a template
	 * returning their URI.
	 */
	private McpServer server() {
		return McpServer.builder()
			.serverInfo("http-test", "1.0.0")
			.tool(Tool.builder("echo").build(),
					(ctx, request) -> CallToolResult.builder()
						.addTextContent(String.valueOf(request.arguments().get("message")))
						.build())
			.streamingTool(Tool.builder("progress").build(), (ctx, request, sink) -> {
				for (int i = 1; i <= 3; i++) {
					sink.progress(ProgressNotification.builder(ctx.progressToken(), (double) i).build());
				}
				sink.complete(CallToolResult.builder().build());
			})
			.streamingTool(Tool.builder("stream").build(), (ctx, request, sink) -> {
				try {
					for (int i = 1;; i++) {
						sink.progress(ProgressNotification.builder(ctx.progressToken(), (double) i).build());
						Thread.sleep(10);
					}
				}
				catch (InterruptedException ex) {
					this.interrupted.countDown();
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			})
			.tool(Tool.builder("elicit").build(),
					(ctx, request) -> InputRequiredResult.builder()
						.addInputRequest("confirm",
								ElicitRequest.form("Confirm?", Map.of("type", "object", "properties", Map.of())))
						.build())
			.tool(Tool.builder("fail").build(), (ctx, request) -> {
				throw new IllegalStateException("expected");
			})
			.tool(Tool.builder("context").build(),
					(ctx, request) -> CallToolResult.builder()
						.addTextContent(String.valueOf(ctx.transportContext().get("user-agent")))
						.build())
			.tool(Tool.builder("thread").build(),
					(ctx, request) -> CallToolResult.builder().addTextContent(Thread.currentThread().getName()).build())
			.resourceTemplate(ResourceTemplate.builder("test://{name}", "test").build(),
					(ctx, request) -> ReadResourceResult
						.builder(List.of(new TextResourceContents(request.uri(), "text/plain", request.uri())))
						.build())
			.build();
	}

	private void start() throws ServletException {
		start(UnaryOperator.identity());
	}

	private void start(UnaryOperator<HttpServletServerTransport.Builder> customizer) throws ServletException {
		servlet.setDelegate(customizer.apply(HttpServletServerTransport.builder(server())).build());
	}

	private URI endpoint() {
		return URI.create("http://localhost:" + port + "/mcp");
	}

	/**
	 * Posts a request with the headers derived from its body.
	 */
	private HttpResponse<String> post(String body) throws Exception {
		return post(body, UnaryOperator.identity());
	}

	/**
	 * Posts a request with the headers derived from its body, then customized.
	 */
	private HttpResponse<String> post(String body, UnaryOperator<HttpRequest.Builder> customizer) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
			.timeout(Duration.ofSeconds(5))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream");
		for (Map.Entry<String, String> header : derivedHeaders(body).entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}
		return this.httpClient.send(customizer.apply(builder).build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Replaces the headers derived from the body.
	 */
	private static HttpRequest.Builder headers(HttpRequest.Builder builder, Map<String, String> headers) {
		HttpRequest request = builder.build();
		HttpRequest.Builder copy = HttpRequest.newBuilder(request.uri())
			.timeout(Duration.ofSeconds(5))
			.POST(request.bodyPublisher().orElseThrow())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream");
		headers.forEach(copy::header);
		return copy;
	}

	/**
	 * The {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name} headers
	 * of a request, as a client sends them.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, String> derivedHeaders(String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		Object json;
		try {
			json = this.mapper.readValue(body, Object.class);
		}
		catch (IOException ex) {
			return headers;
		}
		if (!(json instanceof Map<?, ?> message)) {
			return headers;
		}
		Map<String, Object> params = message.get("params") instanceof Map<?, ?> map ? (Map<String, Object>) map
				: Map.of();
		if (params.get("_meta") instanceof Map<?, ?> meta && meta.get(MetaKeys.PROTOCOL_VERSION) instanceof String v) {
			headers.put(HttpServletServerTransport.PROTOCOL_VERSION_HEADER, v);
		}
		if (message.get("method") instanceof String method) {
			headers.put(HttpServletServerTransport.METHOD_HEADER, method);
		}
		Object name = McpSchema.METHOD_RESOURCES_READ.equals(message.get("method")) ? params.get("uri")
				: params.get("name");
		if (name instanceof String value) {
			headers.put(HttpServletServerTransport.NAME_HEADER, encode(value));
		}
		return headers;
	}

	/**
	 * Encodes a header value that is not plain ASCII as {@code =?base64?...?=}.
	 */
	private static String encode(String value) {
		if (value.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
			return value;
		}
		return "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
	}

	/**
	 * Posts a request over a socket, to control the {@code Host} header and the
	 * connection.
	 */
	private Socket rawPost(String host, String body) throws IOException {
		byte[] content = body.getBytes(StandardCharsets.UTF_8);
		StringBuilder request = new StringBuilder("POST /mcp HTTP/1.1\r\n").append("Host: ")
			.append(host)
			.append("\r\n")
			.append("Content-Type: application/json\r\n")
			.append("Accept: application/json, text/event-stream\r\n")
			.append("Content-Length: ")
			.append(content.length)
			.append("\r\n");
		derivedHeaders(body).forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
		request.append("\r\n");
		Socket socket = new Socket("localhost", port);
		socket.setSoTimeout(5000);
		OutputStream out = socket.getOutputStream();
		out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
		out.write(content);
		out.flush();
		return socket;
	}

	private static BufferedReader reader(Socket socket) throws IOException {
		return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
	}

	private static List<String> sseData(String body) {
		return body.lines().filter(line -> line.startsWith("data: ")).map(line -> line.substring(6)).toList();
	}

	private static Map<String, Object> meta() {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		return meta;
	}

	private String toolCall(Object id, String name, Map<String, Object> arguments) throws IOException {
		return request(id, McpSchema.METHOD_TOOLS_CALL, Map.of("name", name, "arguments", arguments, "_meta", meta()));
	}

	private String request(Object id, String method, Map<String, Object> params) throws IOException {
		return this.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
	}

	/**
	 * A servlet forwarding requests to the transport of the current test.
	 */
	private static final class DelegatingServlet extends HttpServlet {

		private volatile @Nullable HttpServlet delegate;

		void setDelegate(HttpServlet delegate) throws ServletException {
			delegate.init(getServletConfig());
			this.delegate = delegate;
		}

		@Override
		public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
			HttpServlet current = this.delegate;
			if (current == null) {
				throw new ServletException("No transport started");
			}
			current.service(request, response);
		}

	}

}
