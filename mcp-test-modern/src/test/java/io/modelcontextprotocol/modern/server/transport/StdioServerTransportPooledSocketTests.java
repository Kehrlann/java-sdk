/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates a limit of cancellation by interrupt (design.md §8.2): on a virtual
 * thread, interrupting a handler blocked on a {@link Socket} closes the socket. When the
 * socket belongs to a pool, e.g. a JDBC connection, the pool gets back a broken socket
 * and hands it to the next request.
 * <p>
 * The pool here is a queue holding a single socket, connected to a "database" that
 * answers each line with {@code ok}, except {@code slow}, which it never answers.
 */
class StdioServerTransportPooledSocketTests {

	private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

	private final CountDownLatch slowQueryReceived = new CountDownLatch(1);

	private final CountDownLatch databaseSawDisconnect = new CountDownLatch(1);

	private final List<String> queriesReceived = new CopyOnWriteArrayList<>();

	private final BlockingQueue<IOException> cancelledWith = new LinkedBlockingQueue<>();

	private final BlockingQueue<Socket> pool = new ArrayBlockingQueue<>(1);

	private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

	private ServerSocket database;

	private Socket pooledSocket;

	private OutputStream toServer;

	private Thread server;

	@BeforeEach
	void setUp() throws Exception {
		this.database = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		Thread.ofVirtual().start(this::serveDatabase);
		this.pooledSocket = new Socket(InetAddress.getLoopbackAddress(), this.database.getLocalPort());
		this.pool.add(this.pooledSocket);
		startTransport();
	}

	@AfterEach
	void tearDown() throws Exception {
		this.toServer.close();
		assertThat(this.server.join(Duration.ofSeconds(5))).as("server stopped").isTrue();
		this.pooledSocket.close();
		this.database.close();
	}

	@Test
	void cancellingAHandlerBlockedOnAPooledSocketClosesTheSocket() throws Exception {
		// The pooled socket works, and is returned to the pool
		send(toolCall(1, "fast"));
		assertThatJson(receive()).node("result.content[0].text").isEqualTo("ok");

		// A request blocked reading the pooled socket is cancelled
		send(toolCall(2, "slow"));
		assertThat(this.slowQueryReceived.await(5, TimeUnit.SECONDS)).isTrue();
		send(notification(McpSchema.METHOD_NOTIFICATION_CANCELLED, Map.of("requestId", 2)));

		// The interrupt closed the socket: the handler sees it, the database sees the
		// connection close...
		assertThat(this.cancelledWith.poll(5, TimeUnit.SECONDS)).isInstanceOf(SocketException.class)
			.hasMessage("Closed by interrupt");
		assertThat(this.databaseSawDisconnect.await(5, TimeUnit.SECONDS)).isTrue();

		// ... and the next request gets the same, now closed, socket from the pool
		send(toolCall(3, "fast"));
		String response = receive();
		assertThatJson(response).node("id").isEqualTo(3);
		assertThatJson(response).node("result.isError").isEqualTo(true);
		assertThatJson(response).node("result.content[0].text").isEqualTo("SocketException: Socket is closed");
		assertThat(this.pooledSocket.isClosed()).isTrue();
		assertThat(this.queriesReceived).containsExactly("fast", "slow");
	}

	/**
	 * A server with a {@code query} tool, which borrows the pooled socket, sends the
	 * {@code query} argument and returns the answer. Like a pool without validation, it
	 * returns the socket to the pool whatever happened to it.
	 */
	private McpServer server() {
		return McpServer.builder()
			.serverInfo("pooled-socket-test", "1.0.0")
			.tool(Tool.builder("query").build(), (ctx, request) -> {
				Socket socket;
				try {
					socket = this.pool.take();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				try {
					String query = request.arguments().get("query") + "\n";
					socket.getOutputStream().write(query.getBytes(StandardCharsets.UTF_8));
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
					return CallToolResult.builder().addTextContent(reader.readLine()).build();
				}
				catch (IOException ex) {
					if (Thread.currentThread().isInterrupted()) {
						this.cancelledWith.add(ex);
						throw new IllegalStateException(ex);
					}
					return CallToolResult.builder()
						.isError(true)
						.addTextContent(ex.getClass().getSimpleName() + ": " + ex.getMessage())
						.build();
				}
				finally {
					this.pool.add(socket);
				}
			})
			.build();
	}

	/**
	 * Accepts a single connection, and answers each line with {@code ok}, except
	 * {@code slow}, which it never answers.
	 */
	private void serveDatabase() {
		try (Socket connection = this.database.accept();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
				PrintWriter writer = new PrintWriter(connection.getOutputStream(), true, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				this.queriesReceived.add(line);
				if (line.equals("slow")) {
					this.slowQueryReceived.countDown();
				}
				else {
					writer.println("ok");
				}
			}
		}
		catch (IOException ex) {
			// the connection is reset
		}
		this.databaseSawDisconnect.countDown();
	}

	/**
	 * Runs a transport for {@link #server()} on a virtual thread, with virtual request
	 * threads, the default, connected to this test through pipes.
	 */
	private void startTransport() throws IOException {
		Pipe input = Pipe.open();
		Pipe output = Pipe.open();
		StdioServerTransport transport = StdioServerTransport
			.builder(server(), Channels.newInputStream(input.source()), Channels.newOutputStream(output.sink()))
			.build();
		this.server = Thread.ofVirtual().start(() -> {
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
		this.toServer = Channels.newOutputStream(input.sink());
		InputStream fromServer = Channels.newInputStream(output.source());
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
		});
	}

	private void send(String line) throws IOException {
		this.toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		this.toServer.flush();
	}

	private String receive() throws InterruptedException {
		String line = this.received.poll(5, TimeUnit.SECONDS);
		assertThat(line).as("message received from the server").isNotNull();
		return line;
	}

	private String toolCall(Object id, String query) throws IOException {
		return this.mapper
			.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", McpSchema.METHOD_TOOLS_CALL, "params",
					Map.of("name", "query", "arguments", Map.of("query", query), "_meta",
							Map.of(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION,
									MetaKeys.CLIENT_CAPABILITIES, Map.of()))));
	}

	private String notification(String method, Map<String, Object> params) throws IOException {
		return this.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method, "params", params));
	}

}
