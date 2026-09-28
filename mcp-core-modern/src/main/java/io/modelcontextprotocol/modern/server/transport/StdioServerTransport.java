/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.CancelledNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCError;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCErrorResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.RequestId;
import io.modelcontextprotocol.modern.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stdio transport for an {@link McpServer}: reads newline-delimited JSON-RPC messages
 * from an input stream, {@code stdin} by default, and writes them to an output stream,
 * {@code stdout} by default, see {@link #builder(McpServer, InputStream, OutputStream)}.
 * Nothing else may be written to the output stream: logs go to {@code stderr}.
 *
 * <pre>{@code
 * public static void main(String[] args) throws IOException {
 *     McpServer server = McpServer.builder()...build();
 *     StdioServerTransport.builder(server).build().run();
 * }
 * }</pre>
 * <p>
 * {@link #run()} reads messages until the input stream ends, which is how the client
 * shuts the server down. Each request is handled on its own thread, created by the
 * {@link Builder#requestThreadFactory(ThreadFactory) request thread factory}, virtual by
 * default, so requests are handled concurrently and their responses may be written out of
 * order.
 * <p>
 * A {@code notifications/cancelled} cancels the in-flight request it references by
 * interrupting its thread: nothing more is written for that request, neither its
 * notifications nor its response. When the input stream ends, the in-flight requests are
 * cancelled the same way.
 * <p>
 * Every message is written by a single writer thread, created by the
 * {@link Builder#writerThreadFactory(ThreadFactory) writer thread factory}, virtual by
 * default, never by the thread of a handler. A handler thread may be interrupted, and on
 * a virtual thread an interrupt during a blocking write to a channel closes the channel,
 * for all the requests. Transports multiplexing requests over one connection, e.g. a
 * socket, must do the same.
 * <p>
 * A message is serialized by the thread sending it, and queued for the writer thread. The
 * queue holds up to 16MB of messages. When it is full, e.g. because the client does not
 * read the output stream, the threads sending messages wait for the writer thread to
 * catch up: handlers sending notifications or responses, and reading when an invalid
 * message must be answered. A handler waiting to send a notification stops waiting when
 * its request is cancelled. A message that fails to serialize is dropped, except a
 * response, which is replaced by an {@link ErrorCodes#INTERNAL_ERROR} error.
 */
public final class StdioServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioServerTransport.class);

	private static final int DEFAULT_MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

	private static final int OUTBOX_CAPACITY = 16 * 1024 * 1024;

	/**
	 * Stops the writer thread once written messages are drained. Compared by identity.
	 */
	private static final byte[] END_OF_OUTPUT = new byte[0];

	private final McpServer server;

	private final McpJsonMapper jsonMapper;

	private final InputStream inputStream;

	private final OutputStream outputStream;

	private final ThreadFactory requestThreadFactory;

	private final ThreadFactory writerThreadFactory;

	private final int maxMessageSize;

	private final ConcurrentMap<RequestId, InFlightRequest> inFlight = new ConcurrentHashMap<>();

	private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();

	/**
	 * Bounds the bytes of the messages queued or being written. A permit per byte, and
	 * rather than a bounded queue, so that {@link #END_OF_OUTPUT} can always be queued.
	 * Fair, so that smaller messages sent meanwhile do not keep a large one waiting.
	 */
	private final Semaphore outboxPermits = new Semaphore(OUTBOX_CAPACITY, true);

	private final AtomicBoolean started = new AtomicBoolean();

	private StdioServerTransport(Builder builder) {
		this.server = builder.server;
		this.jsonMapper = builder.jsonMapper != null ? builder.jsonMapper : McpJsonDefaults.getMapper();
		this.inputStream = builder.inputStream;
		this.outputStream = builder.outputStream;
		this.requestThreadFactory = builder.requestThreadFactory;
		this.writerThreadFactory = builder.writerThreadFactory;
		this.maxMessageSize = builder.maxMessageSize;
	}

	/**
	 * Creates a builder for a transport serving the given server over {@link System#in}
	 * and {@link System#out}.
	 * @param server the server handling the requests
	 * @return the builder
	 */
	public static Builder builder(McpServer server) {
		return new Builder(server, System.in, System.out);
	}

	/**
	 * Creates a builder for a transport serving the given server over the given streams.
	 * @param server the server handling the requests
	 * @param inputStream the stream messages are read from
	 * @param outputStream the stream messages are written to
	 * @return the builder
	 */
	public static Builder builder(McpServer server, InputStream inputStream, OutputStream outputStream) {
		return new Builder(server, inputStream, outputStream);
	}

	/**
	 * Reads and handles messages until the input stream ends, then cancels the in-flight
	 * requests, writes the messages already queued, and returns. Can only be called once.
	 * @throws IOException if reading the input stream fails, or an
	 * {@link InterruptedIOException} if the thread is interrupted while waiting to queue
	 * an error response
	 * @throws IllegalStateException if the transport has already been run, or the writer
	 * thread factory creates no thread
	 */
	public void run() throws IOException {
		if (!this.started.compareAndSet(false, true)) {
			throw new IllegalStateException("The transport has already been run");
		}
		Thread writer = this.writerThreadFactory.newThread(this::writeMessages);
		if (writer == null) {
			throw new IllegalStateException("The writer thread factory created no thread");
		}
		writer.start();
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(this.inputStream, StandardCharsets.UTF_8));
			while (true) {
				String line;
				try {
					line = readLine(reader);
				}
				catch (MessageTooLargeException ex) {
					String error = "Message exceeds the maximum size of " + this.maxMessageSize + " characters";
					logger.warn(error);
					reply(errorResponse(null, ErrorCodes.INVALID_REQUEST, error));
					continue;
				}
				if (line == null) {
					break;
				}
				if (!line.isBlank()) {
					handleLine(line);
				}
			}
		}
		finally {
			this.inFlight.keySet().forEach(this::cancel);
			this.outbox.add(END_OF_OUTPUT);
			try {
				writer.join();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void handleLine(String line) throws InterruptedIOException {
		JSONRPCMessage message;
		try {
			message = McpSchema.deserializeJsonRpcMessage(this.jsonMapper, line);
		}
		catch (IOException ex) {
			logger.warn("Received invalid JSON: {}", line, ex);
			reply(errorResponse(null, ErrorCodes.PARSE_ERROR, "Parse error"));
			return;
		}
		catch (RuntimeException ex) {
			logger.warn("Received an invalid JSON-RPC message: {}", line, ex);
			reply(errorResponse(null, ErrorCodes.INVALID_REQUEST, "Invalid request"));
			return;
		}
		switch (message) {
			case JSONRPCRequest request -> handleRequest(request);
			case JSONRPCNotification notification when McpSchema.METHOD_NOTIFICATION_CANCELLED
				.equals(notification.method()) -> handleCancellation(notification);
			case JSONRPCNotification notification -> logger.debug("Ignoring notification {}", notification.method());
			case JSONRPCResponse response ->
				logger.warn("Ignoring response {}: the server sends no requests", response.id());
		}
	}

	/**
	 * Requests and cancellations are read in order, on the same thread, so a request is
	 * registered before any cancellation of its id is read.
	 */
	private void handleRequest(JSONRPCRequest request) throws InterruptedIOException {
		// Only this thread adds requests, so the id stays free until it is put
		if (this.inFlight.containsKey(request.id())) {
			reply(errorResponse(request.id(), ErrorCodes.INVALID_REQUEST, "Duplicate request id"));
			return;
		}
		try {
			InFlightRequest inFlightRequest = new InFlightRequest(request);
			// Put before starting, so that the handler finds it when it completes
			this.inFlight.put(request.id(), inFlightRequest);
			inFlightRequest.thread.start();
		}
		catch (RuntimeException ex) {
			this.inFlight.remove(request.id());
			logger.error("Failed to start a thread for request {}", request.id(), ex);
			reply(errorResponse(request.id(), ErrorCodes.INTERNAL_ERROR, "Internal error"));
		}
	}

	private void handleCancellation(JSONRPCNotification notification) {
		Object params = notification.params();
		if (params == null) {
			logger.warn("Ignoring {} without params", McpSchema.METHOD_NOTIFICATION_CANCELLED);
			return;
		}
		CancelledNotification cancelled;
		try {
			cancelled = this.jsonMapper.convertValue(params, CancelledNotification.class);
		}
		catch (RuntimeException ex) {
			logger.warn("Ignoring invalid {}", McpSchema.METHOD_NOTIFICATION_CANCELLED, ex);
			return;
		}
		// Unknown or completed requests are ignored
		cancel(cancelled.requestId());
	}

	private void cancel(RequestId id) {
		InFlightRequest inFlightRequest = this.inFlight.remove(id);
		if (inFlightRequest != null) {
			logger.debug("Cancelling request {}", id);
			inFlightRequest.thread.interrupt();
		}
	}

	/**
	 * Sends an error response from the reader thread.
	 * @throws InterruptedIOException if the thread is interrupted while waiting for room
	 * in the outbox
	 */
	private void reply(JSONRPCErrorResponse response) throws InterruptedIOException {
		try {
			send(response);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while waiting to send a message");
		}
	}

	/**
	 * Serializes a message and queues it for the writer thread, waiting while the outbox
	 * is full.
	 * @throws InterruptedException if the thread is interrupted while waiting. The
	 * message is dropped.
	 */
	private void send(JSONRPCMessage message) throws InterruptedException {
		byte[] json = serialize(message);
		if (json != null) {
			this.outboxPermits.acquire(permits(json));
			this.outbox.add(json);
		}
	}

	/**
	 * A message larger than the outbox takes all of it, and waits for it to be empty.
	 */
	private static int permits(byte[] json) {
		return Math.min(json.length, OUTBOX_CAPACITY);
	}

	private void writeMessages() {
		boolean failed = false;
		while (true) {
			byte[] json;
			try {
				json = this.outbox.take();
			}
			catch (InterruptedException ex) {
				// Not expected: the writer thread is never interrupted
				Thread.currentThread().interrupt();
				return;
			}
			if (json == END_OF_OUTPUT) {
				return;
			}
			try {
				// Once the client is gone, the messages are dropped
				if (!failed) {
					this.outputStream.write(json);
					this.outputStream.write('\n');
					this.outputStream.flush();
				}
			}
			catch (IOException | RuntimeException ex) {
				// Keep draining, so that the threads sending messages do not wait forever
				logger.error("Failed to write to the output stream, dropping all further messages", ex);
				failed = true;
			}
			finally {
				this.outboxPermits.release(permits(json));
			}
		}
	}

	/**
	 * Serializes a message, or the {@link ErrorCodes#INTERNAL_ERROR} error replacing a
	 * response that fails to serialize, so that its request is still answered. An error
	 * without data is not replaced: the replacement would fail the same way.
	 * @return the JSON, or {@code null} if the message fails to serialize
	 */
	private byte @Nullable [] serialize(JSONRPCMessage message) {
		try {
			return this.jsonMapper.writeValueAsBytes(message);
		}
		catch (IOException | RuntimeException ex) {
			if (message instanceof JSONRPCResponse response
					&& !(response instanceof JSONRPCErrorResponse error && error.error().data() == null)) {
				logger.error("Failed to serialize the response to request {}", response.id(), ex);
				return serialize(errorResponse(response.id(), ErrorCodes.INTERNAL_ERROR, "Internal error"));
			}
			logger.error("Failed to serialize {}, dropping it", message, ex);
			return null;
		}
	}

	/**
	 * Reads a line terminated by {@code \n} or {@code \r\n}.
	 * @return the line, or {@code null} at the end of the stream
	 * @throws MessageTooLargeException if the line is longer than the maximum message
	 * size. The line is skipped, so the next read returns the next line.
	 */
	private @Nullable String readLine(BufferedReader reader) throws IOException {
		StringBuilder line = new StringBuilder();
		boolean tooLarge = false;
		int c;
		while ((c = reader.read()) != -1) {
			if (c == '\n') {
				if (tooLarge) {
					throw new MessageTooLargeException();
				}
				if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
					line.setLength(line.length() - 1);
				}
				return line.toString();
			}
			if (line.length() >= this.maxMessageSize) {
				tooLarge = true;
				line.setLength(0);
			}
			if (!tooLarge) {
				line.append((char) c);
			}
		}
		return line.isEmpty() ? null : line.toString();
	}

	private static JSONRPCErrorResponse errorResponse(@Nullable RequestId id, int code, String message) {
		return new JSONRPCErrorResponse(id, new JSONRPCError(code, message));
	}

	/**
	 * A request being handled, on its own thread. Whichever of the handler or the
	 * cancellation removes it from {@code inFlight} first decides whether its response is
	 * written.
	 */
	private final class InFlightRequest implements Runnable {

		private final JSONRPCRequest request;

		private final Thread thread;

		/**
		 * @throws RejectedExecutionException if the thread factory creates no thread
		 */
		private InFlightRequest(JSONRPCRequest request) {
			this.request = request;
			Thread thread = requestThreadFactory.newThread(this);
			if (thread == null) {
				throw new RejectedExecutionException("The request thread factory created no thread");
			}
			this.thread = thread;
		}

		@Override
		public void run() {
			JSONRPCResponse response;
			try {
				response = server.handleRequest(McpTransportContext.EMPTY, this.request, this::sendNotification);
			}
			catch (RuntimeException | Error ex) {
				// Not expected, failures are returned as errors, but e.g. an Error thrown
				// by a handler propagates. Answered, so that the request id is released.
				logger.error("Failed to handle request {} ({})", this.request.id(), this.request.method(), ex);
				response = errorResponse(this.request.id(), ErrorCodes.INTERNAL_ERROR, "Internal error");
			}
			if (inFlight.remove(this.request.id(), this)) {
				// The request can no longer be cancelled: an interrupt left over by the
				// handler must not drop its response
				Thread.interrupted();
				try {
					send(response);
				}
				catch (InterruptedException ex) {
					// Not expected: nothing interrupts the thread any more
					Thread.currentThread().interrupt();
				}
			}
		}

		/**
		 * May be called from any thread the handler starts.
		 */
		private void sendNotification(JSONRPCNotification notification) {
			if (inFlight.get(this.request.id()) == this) {
				try {
					send(notification);
				}
				catch (InterruptedException ex) {
					// Cancelled while waiting for room in the outbox
					Thread.currentThread().interrupt();
				}
			}
		}

	}

	private static final class MessageTooLargeException extends IOException {

	}

	/**
	 * Builder for {@link StdioServerTransport}.
	 */
	public static final class Builder {

		private final McpServer server;

		private @Nullable McpJsonMapper jsonMapper;

		private final InputStream inputStream;

		private final OutputStream outputStream;

		private ThreadFactory requestThreadFactory = Thread.ofVirtual().name("mcp-stdio-request-", 0).factory();

		private ThreadFactory writerThreadFactory = Thread.ofVirtual().name("mcp-stdio-writer").factory();

		private int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;

		private Builder(McpServer server, InputStream inputStream, OutputStream outputStream) {
			Assert.notNull(server, "server must not be null");
			Assert.notNull(inputStream, "inputStream must not be null");
			Assert.notNull(outputStream, "outputStream must not be null");
			this.server = server;
			this.inputStream = inputStream;
			this.outputStream = outputStream;
		}

		/**
		 * Sets the JSON mapper. Defaults to the one found by
		 * {@link McpJsonDefaults#getMapper()}. It must not indent its output: a message
		 * must be written on a single line.
		 * @param jsonMapper the JSON mapper
		 * @return this builder
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the factory creating the thread each request is handled on. Defaults to
		 * virtual threads. A thread is interrupted when its request is cancelled. When
		 * the factory creates no thread, the request is answered with an
		 * {@link ErrorCodes#INTERNAL_ERROR} error.
		 * @param requestThreadFactory the thread factory
		 * @return this builder
		 */
		public Builder requestThreadFactory(ThreadFactory requestThreadFactory) {
			Assert.notNull(requestThreadFactory, "requestThreadFactory must not be null");
			this.requestThreadFactory = requestThreadFactory;
			return this;
		}

		/**
		 * Sets the factory creating the thread that writes every message to the output
		 * stream. Defaults to virtual threads. The thread is created by {@link #run()},
		 * and lives until it returns. The transport never interrupts it. When the factory
		 * creates no thread, {@link #run()} throws an {@link IllegalStateException}.
		 * @param writerThreadFactory the thread factory
		 * @return this builder
		 */
		public Builder writerThreadFactory(ThreadFactory writerThreadFactory) {
			Assert.notNull(writerThreadFactory, "writerThreadFactory must not be null");
			this.writerThreadFactory = writerThreadFactory;
			return this;
		}

		/**
		 * Sets the maximum size of an incoming message, in characters. A longer message
		 * is rejected with an {@link ErrorCodes#INVALID_REQUEST} error instead of being
		 * buffered in memory. Defaults to 16M characters.
		 * @param maxMessageSize the maximum message size
		 * @return this builder
		 */
		public Builder maxMessageSize(int maxMessageSize) {
			Assert.isTrue(maxMessageSize > 0, "maxMessageSize must be positive");
			this.maxMessageSize = maxMessageSize;
			return this;
		}

		public StdioServerTransport build() {
			return new StdioServerTransport(this);
		}

	}

}
