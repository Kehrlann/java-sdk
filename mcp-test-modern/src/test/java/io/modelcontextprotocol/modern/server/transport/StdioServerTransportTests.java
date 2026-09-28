/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpError;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCErrorResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResultResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StdioServerTransportTests {

	private static final String END_OF_STREAM = "<end of stream>";

	private static final int FLOOD_NOTIFICATIONS = 200;

	private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

	private final CountDownLatch release = new CountDownLatch(1);

	private final CountDownLatch waiting = new CountDownLatch(1);

	private final CountDownLatch interrupted = new CountDownLatch(1);

	private final CountDownLatch outputOpen = new CountDownLatch(1);

	private final CountDownLatch flooded = new CountDownLatch(1);

	private final AtomicInteger progressSent = new AtomicInteger();

	private @Nullable Client client;

	@AfterEach
	void tearDown() throws Exception {
		this.release.countDown();
		this.outputOpen.countDown();
		if (this.client != null) {
			this.client.close();
		}
	}

	@Test
	void answersRequests() throws Exception {
		Client client = start();

		client.send(toolCall(1, "echo", Map.of("message", "hello")));

		String response = client.receive();
		assertThatJson(response).node("id").isEqualTo(1);
		assertThatJson(response).node("result.content[0].text").isEqualTo("hello");
	}

	@Test
	void sendsProgressBeforeTheResponse() throws Exception {
		Client client = start();

		client.send(request(1, McpSchema.METHOD_TOOLS_CALL,
				Map.of("name", "progress", "_meta", Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION,
						MetaKeys.CLIENT_CAPABILITIES, Map.of(), MetaKeys.PROGRESS_TOKEN, "token"))));

		String progress = client.receive();
		assertThatJson(progress).node("method").isEqualTo(McpSchema.METHOD_NOTIFICATION_PROGRESS);
		assertThatJson(progress).node("params.progressToken").isEqualTo("token");
		assertThatJson(client.receive()).node("id").isEqualTo(1);
	}

	@Test
	void handlesRequestsConcurrently() throws Exception {
		Client client = start();

		client.send(toolCall(1, "wait", Map.of()));
		assertThat(this.waiting.await(5, TimeUnit.SECONDS)).isTrue();
		client.send(toolCall(2, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).node("id").isEqualTo(2);
		this.release.countDown();
		assertThatJson(client.receive()).node("id").isEqualTo(1);
	}

	@Test
	void cancellationInterruptsTheHandlerAndDropsTheResponse() throws Exception {
		Client client = start();

		client.send(toolCall("req-1", "wait", Map.of()));
		assertThat(this.waiting.await(5, TimeUnit.SECONDS)).isTrue();
		client.send(notification(McpSchema.METHOD_NOTIFICATION_CANCELLED, Map.of("requestId", "req-1")));
		client.send(toolCall(2, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).node("id").isEqualTo(2);
		assertThat(this.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
		client.closeInput();
		assertThat(client.receive()).isEqualTo(END_OF_STREAM);
	}

	@Test
	void cancellationOfAnUnknownRequestIsIgnored() throws Exception {
		Client client = start();

		client.send(notification(McpSchema.METHOD_NOTIFICATION_CANCELLED, Map.of("requestId", 42)));
		client.send(notification(McpSchema.METHOD_NOTIFICATION_CANCELLED, Map.of()));
		client.send(toolCall(1, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).node("id").isEqualTo(1);
	}

	@Test
	void duplicateRequestIdIsRejected() throws Exception {
		Client client = start();

		client.send(toolCall(1, "wait", Map.of()));
		assertThat(this.waiting.await(5, TimeUnit.SECONDS)).isTrue();
		client.send(toolCall(1, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "id": 1, "error": {"code": -32600, "message": "Duplicate request id"}}""");
		this.release.countDown();
		assertThatJson(client.receive()).node("result.content[0].text").isEqualTo("done");
	}

	@Test
	void invalidJsonIsAParseError() throws Exception {
		Client client = start();

		client.send("{not json");
		client.send(toolCall(1, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "error": {"code": -32700, "message": "Parse error"}}""");
		assertThatJson(client.receive()).node("id").isEqualTo(1);
	}

	@Test
	void invalidMessageIsAnInvalidRequest() throws Exception {
		Client client = start();

		client.send("""
				{"jsonrpc": "1.0", "id": 1, "method": "tools/list"}""");

		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "error": {"code": -32600, "message": "Invalid request"}}""");
	}

	@Test
	void tooLargeMessageIsRejectedAndSkipped() throws Exception {
		Client client = start(builder -> builder.maxMessageSize(1000));

		client.send(toolCall(1, "echo", Map.of("message", "x".repeat(1000))));
		client.send(toolCall(2, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).isEqualTo(
				"""
						{"jsonrpc": "2.0", "error": {"code": -32600, "message": "Message exceeds the maximum size of 1000 characters"}}""");
		assertThatJson(client.receive()).node("id").isEqualTo(2);
	}

	@Test
	void acceptsCrLfAndSkipsBlankLines() throws Exception {
		Client client = start();

		client.send("");
		client.send(toolCall(1, "echo", Map.of("message", "hello")) + "\r");

		assertThatJson(client.receive()).node("id").isEqualTo(1);
	}

	@Test
	void ignoresResponsesAndOtherNotifications() throws Exception {
		Client client = start();

		client.send("""
				{"jsonrpc": "2.0", "id": 1, "result": {}}""");
		client.send(notification("notifications/unknown", Map.of()));
		client.send(toolCall(2, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).node("id").isEqualTo(2);
	}

	@Test
	void endOfInputCancelsInFlightRequestsAndReturns() throws Exception {
		Client client = start();

		client.send(toolCall(1, "wait", Map.of()));
		assertThat(this.waiting.await(5, TimeUnit.SECONDS)).isTrue();
		client.closeInput();

		assertThat(client.receive()).isEqualTo(END_OF_STREAM);
		assertThat(this.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void usesTheConfiguredThreadFactories() throws Exception {
		List<Thread> requestThreads = new CopyOnWriteArrayList<>();
		List<Thread> writerThreads = new CopyOnWriteArrayList<>();
		Client client = start(builder -> builder
			.requestThreadFactory(recording(Thread.ofPlatform().name("custom-request").factory(), requestThreads))
			.writerThreadFactory(recording(Thread.ofPlatform().name("custom-writer").factory(), writerThreads)));

		client.send(toolCall(1, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).node("id").isEqualTo(1);
		assertThat(requestThreads).singleElement().satisfies(thread -> assertThat(thread.isVirtual()).isFalse());
		assertThat(writerThreads).singleElement().satisfies(thread -> assertThat(thread.isVirtual()).isFalse());
	}

	@Test
	void handlersWaitWhileTheOutboxIsFull() throws Exception {
		Client client = start(UnaryOperator.identity(), output -> gated(output, this.outputOpen));

		client.send(request(1, McpSchema.METHOD_TOOLS_CALL,
				Map.of("name", "flood", "_meta", Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION,
						MetaKeys.CLIENT_CAPABILITIES, Map.of(), MetaKeys.PROGRESS_TOKEN, "token"))));

		// The outbox holds 16MB: about 64 notifications of 256KB
		Thread.sleep(500);
		assertThat(this.progressSent.get()).isBetween(1, 64);
		this.outputOpen.countDown();
		for (int i = 0; i < FLOOD_NOTIFICATIONS; i++) {
			assertThatJson(client.receive()).node("method").isEqualTo(McpSchema.METHOD_NOTIFICATION_PROGRESS);
		}
		assertThatJson(client.receive()).node("id").isEqualTo(1);
	}

	@Test
	void writerKeepsDrainingAfterTheOutputStreamFails() throws Exception {
		Client client = start(UnaryOperator.identity(), output -> new OutputStream() {

			@Override
			public void write(int b) {
				throw new IllegalStateException("expected");
			}

		});

		client.send(request(1, McpSchema.METHOD_TOOLS_CALL,
				Map.of("name", "flood", "_meta", Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION,
						MetaKeys.CLIENT_CAPABILITIES, Map.of(), MetaKeys.PROGRESS_TOKEN, "token"))));

		// More than the outbox holds: sent only if the writer keeps releasing room
		assertThat(this.flooded.await(5, TimeUnit.SECONDS)).isTrue();
		client.closeInput();
		assertThat(client.receive()).isEqualTo(END_OF_STREAM);
	}

	@Test
	void serializesMessagesOnTheThreadSendingThem() throws Exception {
		List<Thread> requestThreads = new CopyOnWriteArrayList<>();
		List<Thread> serializingThreads = new CopyOnWriteArrayList<>();
		Client client = start(builder -> builder
			.jsonMapper(new TestJsonMapper(this.mapper, value -> serializingThreads.add(Thread.currentThread())))
			.requestThreadFactory(recording(Thread.ofVirtual().factory(), requestThreads)));

		client.send("{not json");
		assertThatJson(client.receive()).node("error.code").isEqualTo(-32700);
		client.send(toolCall(1, "echo", Map.of("message", "hello")));
		assertThatJson(client.receive()).node("id").isEqualTo(1);

		assertThat(serializingThreads).containsExactly(client.server, requestThreads.get(0));
	}

	@Test
	void dropsMessagesThatFailToSerialize() throws Exception {
		Client client = start(builder -> builder.jsonMapper(new TestJsonMapper(this.mapper, value -> {
			if (value instanceof JSONRPCNotification) {
				throw new IllegalStateException("Cannot serialize notifications");
			}
		})));

		client.send(request(1, McpSchema.METHOD_TOOLS_CALL,
				Map.of("name", "progress", "_meta", Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION,
						MetaKeys.CLIENT_CAPABILITIES, Map.of(), MetaKeys.PROGRESS_TOKEN, "token"))));

		assertThatJson(client.receive()).node("id").isEqualTo(1);
		client.send(toolCall(2, "echo", Map.of("message", "hello")));
		assertThatJson(client.receive()).node("id").isEqualTo(2);
	}

	@Test
	void resultThatFailsToSerializeIsAnInternalError() throws Exception {
		Client client = start(builder -> builder.jsonMapper(new TestJsonMapper(this.mapper, value -> {
			if (value instanceof JSONRPCResultResponse) {
				throw new IllegalStateException("Cannot serialize results");
			}
		})));

		client.send(toolCall(1, "echo", Map.of("message", "hello")));

		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "Internal error"}}""");
	}

	@Test
	void errorWithDataThatFailsToSerializeIsAnInternalError() throws Exception {
		Client client = start(builder -> builder.jsonMapper(new TestJsonMapper(this.mapper, value -> {
			if (value instanceof JSONRPCErrorResponse response && response.error().data() != null) {
				throw new IllegalStateException("Cannot serialize error data");
			}
		})));

		client.send(toolCall(1, "mcp-error", Map.of()));

		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "Internal error"}}""");
	}

	@Test
	void requestRejectedByTheThreadFactoryIsAnInternalError() throws Exception {
		AtomicInteger created = new AtomicInteger();
		ThreadFactory rejectingFirst = runnable -> created.getAndIncrement() == 0 ? null
				: Thread.ofVirtual().unstarted(runnable);
		Client client = start(builder -> builder.requestThreadFactory(rejectingFirst));

		client.send(toolCall(1, "echo", Map.of("message", "hello")));
		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "Internal error"}}""");

		// The id is not left in flight
		client.send(toolCall(1, "echo", Map.of("message", "hello")));
		assertThatJson(client.receive()).node("result.content[0].text").isEqualTo("hello");
	}

	@Test
	void handlerThrowingAnErrorIsAnInternalError() throws Exception {
		Client client = start();

		client.send(toolCall(1, "error", Map.of()));
		assertThatJson(client.receive()).isEqualTo("""
				{"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "Internal error"}}""");

		// The id is not left in flight
		client.send(toolCall(1, "echo", Map.of("message", "hello")));
		assertThatJson(client.receive()).node("result.content[0].text").isEqualTo("hello");
	}

	@Test
	void canOnlyRunOnce() throws Exception {
		Pipe input = Pipe.open();
		input.sink().close();
		StdioServerTransport transport = StdioServerTransport
			.builder(server(), Channels.newInputStream(input.source()), OutputStream.nullOutputStream())
			.build();

		transport.run();

		assertThatIllegalStateException().isThrownBy(transport::run);
	}

	@Test
	void writerRejectedByTheThreadFactoryFailsTheRun() {
		StdioServerTransport transport = StdioServerTransport
			.builder(server(), InputStream.nullInputStream(), OutputStream.nullOutputStream())
			.writerThreadFactory(runnable -> null)
			.build();

		assertThatIllegalStateException().isThrownBy(transport::run)
			.withMessage("The writer thread factory created no thread");
	}

	/**
	 * A server with an {@code echo} tool, a {@code progress} tool, a {@code flood} tool
	 * sending {@value #FLOOD_NOTIFICATIONS} progress notifications of 256KB, an
	 * {@code error} tool throwing an {@link Error}, which {@link McpServer} does not
	 * catch, an {@code mcp-error} tool throwing an {@link McpError} with data, and a
	 * {@code wait} tool that blocks until {@link #release} or interrupted.
	 */
	private McpServer server() {
		return McpServer.builder()
			.serverInfo("stdio-test", "1.0.0")
			.tool(Tool.builder("echo").build(),
					(ctx, request) -> CallToolResult.builder()
						.addTextContent(String.valueOf(request.arguments().get("message")))
						.build())
			.streamingTool(Tool.builder("progress").build(), (ctx, request, sink) -> {
				sink.progress(ProgressNotification.builder(ctx.progressToken(), 1.0).build());
				sink.complete(CallToolResult.builder().build());
			})
			.streamingTool(Tool.builder("flood").build(), (ctx, request, sink) -> {
				String message = "x".repeat(256 * 1024);
				for (int i = 1; i <= FLOOD_NOTIFICATIONS; i++) {
					sink.progress(
							ProgressNotification.builder(ctx.progressToken(), (double) i).message(message).build());
					this.progressSent.incrementAndGet();
				}
				this.flooded.countDown();
				sink.complete(CallToolResult.builder().build());
			})
			.tool(Tool.builder("error").build(), (ctx, request) -> {
				throw new AssertionError("expected");
			})
			.tool(Tool.builder("mcp-error").build(), (ctx, request) -> {
				throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid", Map.of("detail", "value"));
			})
			.tool(Tool.builder("wait").build(), (ctx, request) -> {
				this.waiting.countDown();
				try {
					this.release.await();
				}
				catch (InterruptedException ex) {
					this.interrupted.countDown();
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				return CallToolResult.builder().addTextContent("done").build();
			})
			.build();
	}

	/**
	 * Runs a transport for {@link #server()} on a virtual thread, connected to a client
	 * through pipes.
	 */
	private Client start() throws IOException {
		return start(UnaryOperator.identity());
	}

	/**
	 * Runs the transport like {@link #start()}, configured by the given customizer.
	 */
	private Client start(UnaryOperator<StdioServerTransport.Builder> customizer) throws IOException {
		return start(customizer, UnaryOperator.identity());
	}

	/**
	 * Runs the transport like {@link #start(UnaryOperator)}, writing to the given
	 * decoration of the output pipe.
	 */
	private Client start(UnaryOperator<StdioServerTransport.Builder> customizer,
			UnaryOperator<OutputStream> outputDecorator) throws IOException {
		Pipe input = Pipe.open();
		Pipe output = Pipe.open();
		StdioServerTransport transport = customizer
			.apply(StdioServerTransport.builder(server(), Channels.newInputStream(input.source()),
					outputDecorator.apply(Channels.newOutputStream(output.sink()))))
			.build();
		Thread server = Thread.ofVirtual().start(() -> {
			try {
				transport.run();
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			finally {
				try {
					output.sink().close();
				}
				catch (IOException ex) {
					// ignore
				}
			}
		});
		this.client = new Client(Channels.newOutputStream(input.sink()), Channels.newInputStream(output.source()),
				server);
		return this.client;
	}

	/**
	 * An output stream whose writes block until {@code open} is counted down.
	 */
	private static OutputStream gated(OutputStream delegate, CountDownLatch open) {
		return new FilterOutputStream(delegate) {

			@Override
			public void write(int b) throws IOException {
				awaitOpen();
				this.out.write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				awaitOpen();
				this.out.write(b, off, len);
			}

			private void awaitOpen() throws InterruptedIOException {
				try {
					open.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException();
				}
			}

		};
	}

	private static ThreadFactory recording(ThreadFactory delegate, List<Thread> created) {
		return runnable -> {
			Thread thread = delegate.newThread(runnable);
			created.add(thread);
			return thread;
		};
	}

	private String toolCall(Object id, String name, Map<String, Object> arguments) throws IOException {
		return request(id, McpSchema.METHOD_TOOLS_CALL, Map.of("name", name, "arguments", arguments, "_meta", Map
			.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION, MetaKeys.CLIENT_CAPABILITIES, Map.of())));
	}

	private String request(Object id, String method, Map<String, Object> params) throws IOException {
		return this.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
	}

	private String notification(String method, Map<String, Object> params) throws IOException {
		return this.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method, "params", params));
	}

	/**
	 * A JSON mapper calling a hook before serializing a value to bytes, as the transport
	 * does to write a message.
	 */
	private record TestJsonMapper(McpJsonMapper delegate, Consumer<Object> beforeWrite) implements McpJsonMapper {

		@Override
		public <T> T readValue(String content, Class<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T readValue(byte[] content, Class<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T readValue(String content, TypeRef<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
			return this.delegate.readValue(content, type);
		}

		@Override
		public <T> T convertValue(Object fromValue, Class<T> type) {
			return this.delegate.convertValue(fromValue, type);
		}

		@Override
		public <T> T convertValue(Object fromValue, TypeRef<T> type) {
			return this.delegate.convertValue(fromValue, type);
		}

		@Override
		public String writeValueAsString(Object value) throws IOException {
			return this.delegate.writeValueAsString(value);
		}

		@Override
		public byte[] writeValueAsBytes(Object value) throws IOException {
			this.beforeWrite.accept(value);
			return this.delegate.writeValueAsBytes(value);
		}

	}

	/**
	 * The client end of the pipes. Lines written by the server are read on a separate
	 * thread, so that receiving can time out.
	 */
	private static final class Client implements AutoCloseable {

		private final OutputStream toServer;

		private final Thread server;

		private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		private Client(OutputStream toServer, InputStream fromServer, Thread server) {
			this.toServer = toServer;
			this.server = server;
			Thread.ofVirtual().start(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(fromServer, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						this.received.add(line);
					}
				}
				catch (IOException ex) {
					// the pipe is closed
				}
				this.received.add(END_OF_STREAM);
			});
		}

		void send(String line) throws IOException {
			this.toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
			this.toServer.flush();
		}

		String receive() throws InterruptedException {
			String line = this.received.poll(5, TimeUnit.SECONDS);
			assertThat(line).as("message received from the server").isNotNull();
			return line;
		}

		/**
		 * Closes the input of the server, and waits for it to stop.
		 */
		void closeInput() throws Exception {
			this.toServer.close();
			assertThat(this.server.join(Duration.ofSeconds(5))).as("server stopped").isTrue();
		}

		@Override
		public void close() throws Exception {
			if (this.server.isAlive()) {
				closeInput();
			}
		}

	}

}
