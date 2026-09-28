/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCError;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCErrorResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.spec.McpSchema.RequestId;
import io.modelcontextprotocol.modern.util.Assert;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Streamable HTTP transport for an {@link McpServer}, as a servlet to map to the MCP
 * endpoint, e.g. {@code /mcp}.
 *
 * <pre>{@code
 * McpServer server = McpServer.builder()...build();
 * HttpServlet servlet = HttpServletServerTransport.builder(server).build();
 * }</pre>
 * <p>
 * Every JSON-RPC message is its own {@code POST}. There are no sessions, and no
 * {@code GET} stream: every other HTTP method is answered with
 * {@code 405 Method Not Allowed}. A request is answered with a single JSON object, unless
 * its handler emits a notification, such as progress, before completing: the response is
 * then a Server-Sent Events stream carrying the notifications, then the response.
 * Notifications from the client are accepted with {@code 202 Accepted} and ignored: none
 * is defined over Streamable HTTP.
 * <p>
 * Before handing a request to the server, the transport checks:
 * <ul>
 * <li>the {@code Origin} header, see {@link Builder#allowedOrigins(List)}, and the
 * {@code Host} header, see {@link Builder#allowedHosts(List)}, answering
 * {@code 403 Forbidden};</li>
 * <li>the {@code Accept} header, which must list both {@code application/json} and
 * {@code text/event-stream}, answering {@code 406 Not Acceptable};</li>
 * <li>the {@code Content-Type} header, which must be {@code application/json}, answering
 * {@code 415 Unsupported Media Type};</li>
 * <li>the size of the body, see {@link Builder#maxRequestSize(int)}, answering
 * {@code 413 Content Too Large};</li>
 * <li>that the body is a single JSON-RPC request or notification, answering
 * {@link ErrorCodes#PARSE_ERROR} or {@link ErrorCodes#INVALID_REQUEST};</li>
 * <li>that the {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name}
 * headers are present, and match the body, answering {@link ErrorCodes#HEADER_MISMATCH}.
 * An {@code Mcp-Name} in the {@code =?base64?...?=} form is decoded first. The
 * {@code Mcp-Param-*} headers of {@code x-mcp-header} tool parameters are not
 * checked.</li>
 * </ul>
 * A JSON-RPC error answered as a JSON object has the HTTP status of its code,
 * {@code 404 Not Found} for {@link ErrorCodes#METHOD_NOT_FOUND}, {@code 400 Bad Request}
 * for the other protocol errors, and {@code 200 OK} otherwise. Once a stream has started,
 * errors are sent on it.
 * <p>
 * Each request is handled on its own thread, created by the
 * {@link Builder#requestThreadFactory(ThreadFactory) request thread factory}, virtual by
 * default, not on the thread of the container. The container thread writes the messages
 * of the request, so that the thread of a handler is never interrupted while writing.
 * When a write fails, e.g. because the client closed the response stream, which is how
 * the client cancels a request, the thread of the handler is interrupted, and nothing
 * more is written for the request. The servlet API cannot tell that the client is gone
 * before a write fails: a handler that emits nothing learns it is cancelled when its
 * response is written.
 */
public final class HttpServletServerTransport extends HttpServlet {

	private static final Logger logger = LoggerFactory.getLogger(HttpServletServerTransport.class);

	/**
	 * The {@code MCP-Protocol-Version} header.
	 */
	public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

	/**
	 * The {@code Mcp-Method} header.
	 */
	public static final String METHOD_HEADER = "Mcp-Method";

	/**
	 * The {@code Mcp-Name} header.
	 */
	public static final String NAME_HEADER = "Mcp-Name";

	private static final String APPLICATION_JSON = "application/json";

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String BASE64_PREFIX = "=?base64?";

	private static final String BASE64_SUFFIX = "?=";

	private static final int DEFAULT_MAX_REQUEST_SIZE = 16 * 1024 * 1024;

	private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of("http://localhost:*", "http://127.0.0.1:*",
			"http://[::1]:*");

	/**
	 * The notifications of a request queued for the container thread. Beyond, the thread
	 * sending a notification waits.
	 */
	private static final int MAX_PENDING_NOTIFICATIONS = 64;

	private static final byte[] SSE_DATA = "event: message\ndata: ".getBytes(StandardCharsets.UTF_8);

	private static final byte[] SSE_END = "\n\n".getBytes(StandardCharsets.UTF_8);

	private final transient McpServer server;

	private final transient McpJsonMapper jsonMapper;

	private final transient ThreadFactory requestThreadFactory;

	private final transient Function<HttpServletRequest, McpTransportContext> contextExtractor;

	private final List<String> allowedOrigins;

	private final List<String> allowedHosts;

	private final int maxRequestSize;

	private HttpServletServerTransport(Builder builder) {
		this.server = builder.server;
		this.jsonMapper = builder.jsonMapper != null ? builder.jsonMapper : McpJsonDefaults.getMapper();
		this.requestThreadFactory = builder.requestThreadFactory;
		this.contextExtractor = builder.contextExtractor;
		this.allowedOrigins = List.copyOf(builder.allowedOrigins);
		this.allowedHosts = List.copyOf(builder.allowedHosts);
		this.maxRequestSize = builder.maxRequestSize;
	}

	/**
	 * Creates a builder for a transport serving the given server.
	 * @param server the server handling the requests
	 * @return the builder
	 */
	public static Builder builder(McpServer server) {
		return new Builder(server);
	}

	/**
	 * Answers {@code POST}s, and {@code 405 Method Not Allowed} to every other method.
	 */
	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!"POST".equals(request.getMethod())) {
			response.setHeader("Allow", "POST");
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		doPost(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String rejection = checkHosts(request);
		if (rejection != null) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, rejection);
			return;
		}
		if (!acceptsJsonAndEventStream(request)) {
			reject(response, HttpServletResponse.SC_NOT_ACCEPTABLE,
					"Accept must list both " + APPLICATION_JSON + " and " + TEXT_EVENT_STREAM);
			return;
		}
		if (!isMediaType(request.getContentType(), APPLICATION_JSON)) {
			reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be " + APPLICATION_JSON);
			return;
		}
		byte[] body = readBody(request);
		if (body == null) {
			reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
					"Request body exceeds the maximum size of " + this.maxRequestSize + " bytes");
			return;
		}
		JSONRPCMessage message;
		try {
			message = parse(body);
		}
		catch (IOException ex) {
			logger.debug("Received invalid JSON", ex);
			writeJson(response, errorResponse(null, ErrorCodes.PARSE_ERROR, "Parse error"));
			return;
		}
		catch (RuntimeException ex) {
			logger.debug("Received an invalid JSON-RPC message", ex);
			writeJson(response, errorResponse(null, ErrorCodes.INVALID_REQUEST,
					"The body must be a single JSON-RPC request or notification"));
			return;
		}
		if (message instanceof JSONRPCRequest jsonRpcRequest) {
			String mismatch = checkHeaders(request, jsonRpcRequest);
			if (mismatch != null) {
				writeJson(response,
						errorResponse(jsonRpcRequest.id(), ErrorCodes.HEADER_MISMATCH, "Header mismatch: " + mismatch));
				return;
			}
			handleRequest(request, response, jsonRpcRequest);
		}
		else {
			logger.debug("Ignoring notification {}", ((JSONRPCNotification) message).method());
			response.setStatus(HttpServletResponse.SC_ACCEPTED);
		}
	}

	private void handleRequest(HttpServletRequest request, HttpServletResponse response, JSONRPCRequest jsonRpcRequest)
			throws IOException {
		McpTransportContext transportContext = this.contextExtractor.apply(request);
		Assert.notNull(transportContext, "The context extractor must not return null");
		ResponseStream stream = new ResponseStream();
		Thread thread = this.requestThreadFactory.newThread(() -> {
			JSONRPCResponse jsonRpcResponse;
			try {
				jsonRpcResponse = this.server.handleRequest(transportContext, jsonRpcRequest, stream::notification);
			}
			catch (RuntimeException | Error ex) {
				// Not expected, failures are returned as errors, but e.g. an Error thrown
				// by a handler propagates. Answered, so that the request completes.
				logger.error("Failed to handle request {} ({})", jsonRpcRequest.id(), jsonRpcRequest.method(), ex);
				jsonRpcResponse = errorResponse(jsonRpcRequest.id(), ErrorCodes.INTERNAL_ERROR, "Internal error");
			}
			stream.response(jsonRpcResponse);
		});
		if (thread == null) {
			logger.error("The request thread factory created no thread for request {}", jsonRpcRequest.id());
			writeJson(response, errorResponse(jsonRpcRequest.id(), ErrorCodes.INTERNAL_ERROR, "Internal error"));
			return;
		}
		thread.start();
		try {
			writeMessages(response, stream);
		}
		catch (IOException | RuntimeException ex) {
			logger.debug("Failed to write to the response of request {}, cancelling it", jsonRpcRequest.id(), ex);
			stream.close();
			thread.interrupt();
		}
		catch (InterruptedException ex) {
			logger.debug("Interrupted while waiting for request {}, cancelling it", jsonRpcRequest.id());
			stream.close();
			thread.interrupt();
			Thread.currentThread().interrupt();
		}
		finally {
			// Once the response is written, a handler thread still sending must not wait
			// for a reader that is gone
			stream.close();
		}
	}

	/**
	 * Writes the messages of a request until its response: as a single JSON object when
	 * the response comes first, as a stream otherwise.
	 */
	private void writeMessages(HttpServletResponse response, ResponseStream stream)
			throws IOException, InterruptedException {
		JSONRPCMessage message = stream.take();
		if (message instanceof JSONRPCResponse jsonRpcResponse) {
			writeJson(response, jsonRpcResponse);
			return;
		}
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(TEXT_EVENT_STREAM);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("X-Accel-Buffering", "no");
		OutputStream out = response.getOutputStream();
		while (true) {
			byte[] json = serialize(message);
			if (json != null) {
				out.write(SSE_DATA);
				out.write(json);
				out.write(SSE_END);
				out.flush();
			}
			if (message instanceof JSONRPCResponse) {
				return;
			}
			message = stream.take();
		}
	}

	private void writeJson(HttpServletResponse response, JSONRPCResponse jsonRpcResponse) throws IOException {
		byte[] json = serialize(jsonRpcResponse);
		if (json == null) {
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			return;
		}
		response.setStatus(httpStatus(jsonRpcResponse));
		response.setContentType(APPLICATION_JSON);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(json.length);
		OutputStream out = response.getOutputStream();
		out.write(json);
		out.flush();
	}

	/**
	 * Answers a request rejected before its body is parsed, with a JSON-RPC error without
	 * id.
	 */
	private void reject(HttpServletResponse response, int status, String message) throws IOException {
		logger.debug("Rejecting request with {}: {}", status, message);
		byte[] json = serialize(errorResponse(null, ErrorCodes.INVALID_REQUEST, message));
		if (json == null) {
			response.sendError(status);
			return;
		}
		response.setStatus(status);
		response.setContentType(APPLICATION_JSON);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(json.length);
		response.getOutputStream().write(json);
	}

	/**
	 * The HTTP status of a response sent as a single JSON object.
	 */
	private static int httpStatus(JSONRPCResponse response) {
		if (!(response instanceof JSONRPCErrorResponse error)) {
			return HttpServletResponse.SC_OK;
		}
		return switch (error.error().code()) {
			case ErrorCodes.METHOD_NOT_FOUND -> HttpServletResponse.SC_NOT_FOUND;
			case ErrorCodes.PARSE_ERROR, ErrorCodes.INVALID_REQUEST, ErrorCodes.INVALID_PARAMS,
					ErrorCodes.HEADER_MISMATCH, ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY,
					ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION ->
				HttpServletResponse.SC_BAD_REQUEST;
			default -> HttpServletResponse.SC_OK;
		};
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
	 * Parses a body holding a single JSON-RPC request or notification.
	 * @throws IOException if the body is not valid JSON
	 * @throws IllegalArgumentException if the body is not a JSON-RPC request or
	 * notification, e.g. a batch or a response
	 */
	private JSONRPCMessage parse(byte[] body) throws IOException {
		Object json = this.jsonMapper.readValue(body, Object.class);
		if (!(json instanceof Map<?, ?> map) || !map.containsKey("method")) {
			throw new IllegalArgumentException("Not a JSON-RPC request or notification");
		}
		if (map.containsKey("id")) {
			return this.jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		return this.jsonMapper.convertValue(map, JSONRPCNotification.class);
	}

	/**
	 * Reads the body, up to the maximum request size.
	 * @return the body, or {@code null} if it is larger than the maximum request size
	 */
	private byte @Nullable [] readBody(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > this.maxRequestSize) {
			return null;
		}
		try (InputStream in = request.getInputStream()) {
			byte[] body = in.readNBytes(this.maxRequestSize + 1);
			return body.length > this.maxRequestSize ? null : body;
		}
	}

	/**
	 * Checks the {@code Origin} and {@code Host} headers, against DNS rebinding attacks.
	 * @return why the request is rejected, or {@code null} if it is accepted
	 */
	private @Nullable String checkHosts(HttpServletRequest request) {
		String origin = request.getHeader("Origin");
		if (origin != null && !matches(this.allowedOrigins, origin)) {
			return "Origin not allowed: " + origin;
		}
		if (!this.allowedHosts.isEmpty()) {
			String host = request.getHeader("Host");
			if (host == null || !matches(this.allowedHosts, host)) {
				return "Host not allowed: " + host;
			}
		}
		return null;
	}

	/**
	 * Matches a header value, case-insensitively, against patterns that may end with a
	 * {@code :*} port wildcard.
	 */
	private static boolean matches(List<String> patterns, String value) {
		for (String pattern : patterns) {
			if (pattern.equalsIgnoreCase(value)) {
				return true;
			}
			if (pattern.endsWith(":*")) {
				String base = pattern.substring(0, pattern.length() - 2);
				if (value.equalsIgnoreCase(base) || value.regionMatches(true, 0, base + ":", 0, base.length() + 1)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks that the headers mirroring the body are present, and match it. A body
	 * missing a value is not checked here: the server rejects it with
	 * {@link ErrorCodes#INVALID_PARAMS}, so that a request missing the required
	 * {@code _meta} fields is told so rather than told its headers do not match.
	 * @return the mismatch, or {@code null} if the headers match
	 */
	private static @Nullable String checkHeaders(HttpServletRequest request, JSONRPCRequest jsonRpcRequest) {
		if (!(jsonRpcRequest.params() instanceof Map<?, ?> params) || !(params.get("_meta") instanceof Map<?, ?> meta)
				|| !(meta.get(MetaKeys.PROTOCOL_VERSION) instanceof String protocolVersion)
				|| !(meta.get(MetaKeys.CLIENT_CAPABILITIES) instanceof Map<?, ?>)) {
			return null;
		}
		String mismatch = checkHeader(request, PROTOCOL_VERSION_HEADER, protocolVersion, false);
		if (mismatch != null) {
			return mismatch;
		}
		mismatch = checkHeader(request, METHOD_HEADER, jsonRpcRequest.method(), false);
		if (mismatch != null) {
			return mismatch;
		}
		String nameParam = switch (jsonRpcRequest.method()) {
			case McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_PROMPTS_GET -> "name";
			case McpSchema.METHOD_RESOURCES_READ -> "uri";
			default -> null;
		};
		if (nameParam != null && params.get(nameParam) instanceof String bodyValue) {
			return checkHeader(request, NAME_HEADER, bodyValue, true);
		}
		return null;
	}

	/**
	 * Checks that a header is present once, and matches the value in the body.
	 * @param encoded whether the header may be Base64-encoded, as {@code =?base64?...?=}
	 * @return the mismatch, or {@code null} if the header matches
	 */
	private static @Nullable String checkHeader(HttpServletRequest request, String name, String bodyValue,
			boolean encoded) {
		List<String> values = Collections.list(request.getHeaders(name));
		if (values.isEmpty()) {
			return "missing " + name + " header";
		}
		if (values.size() > 1) {
			return name + " header appears more than once";
		}
		// Containers may keep the whitespace around the value, which is not part of it
		String value = values.get(0).strip();
		String decoded = encoded ? decodeHeaderValue(value) : value;
		if (decoded == null) {
			return name + " header value '" + value + "' is not valid Base64";
		}
		if (!decoded.equals(bodyValue)) {
			return name + " header value '" + value + "' does not match body value '" + bodyValue + "'";
		}
		return null;
	}

	/**
	 * Decodes a header value in the {@code =?base64?...?=} form.
	 * @return the decoded value, the value itself if it is not encoded, or {@code null}
	 * if it is not valid Base64
	 */
	private static @Nullable String decodeHeaderValue(String value) {
		if (value.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length() || !value.startsWith(BASE64_PREFIX)
				|| !value.endsWith(BASE64_SUFFIX)) {
			return value;
		}
		String base64 = value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
		try {
			return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static boolean acceptsJsonAndEventStream(HttpServletRequest request) {
		List<String> mediaRanges = new ArrayList<>();
		for (String accept : Collections.list(request.getHeaders("Accept"))) {
			for (String mediaRange : accept.split(",")) {
				mediaRanges.add(mediaType(mediaRange));
			}
		}
		return accepts(mediaRanges, APPLICATION_JSON) && accepts(mediaRanges, TEXT_EVENT_STREAM);
	}

	private static boolean accepts(List<String> mediaRanges, String mediaType) {
		String wildcard = mediaType.substring(0, mediaType.indexOf('/')) + "/*";
		return mediaRanges.contains(mediaType) || mediaRanges.contains(wildcard) || mediaRanges.contains("*/*");
	}

	private static boolean isMediaType(@Nullable String contentType, String mediaType) {
		return contentType != null && mediaType(contentType).equals(mediaType);
	}

	/**
	 * The media type of a header value, without parameters, in lower case.
	 */
	private static String mediaType(String value) {
		int parameters = value.indexOf(';');
		return (parameters >= 0 ? value.substring(0, parameters) : value).trim().toLowerCase(Locale.ROOT);
	}

	private static JSONRPCErrorResponse errorResponse(@Nullable RequestId id, int code, String message) {
		return new JSONRPCErrorResponse(id, new JSONRPCError(code, message));
	}

	/**
	 * The messages of a request, handed from the threads of its handler to the container
	 * thread writing them. Once closed, because the response is written or the client is
	 * gone, messages are dropped.
	 */
	private static final class ResponseStream {

		private final Lock lock = new ReentrantLock();

		private final Condition changed = this.lock.newCondition();

		private final Queue<JSONRPCNotification> notifications = new ArrayDeque<>();

		private @Nullable JSONRPCResponse response;

		private boolean closed;

		/**
		 * Queues a notification, waiting while too many are queued. May be called from
		 * any thread the handler starts.
		 */
		void notification(JSONRPCNotification notification) {
			this.lock.lock();
			try {
				while (!this.closed && this.notifications.size() >= MAX_PENDING_NOTIFICATIONS) {
					this.changed.await();
				}
				if (!this.closed) {
					this.notifications.add(notification);
					this.changed.signalAll();
				}
			}
			catch (InterruptedException ex) {
				// Cancelled while waiting: the notification is dropped
				Thread.currentThread().interrupt();
			}
			finally {
				this.lock.unlock();
			}
		}

		void response(JSONRPCResponse response) {
			this.lock.lock();
			try {
				if (!this.closed) {
					this.response = response;
					this.changed.signalAll();
				}
			}
			finally {
				this.lock.unlock();
			}
		}

		/**
		 * Waits for the next message: the queued notifications, then the response.
		 */
		JSONRPCMessage take() throws InterruptedException {
			this.lock.lock();
			try {
				while (this.notifications.isEmpty() && this.response == null) {
					this.changed.await();
				}
				JSONRPCNotification notification = this.notifications.poll();
				if (notification != null) {
					this.changed.signalAll();
					return notification;
				}
				return Objects.requireNonNull(this.response);
			}
			finally {
				this.lock.unlock();
			}
		}

		void close() {
			this.lock.lock();
			try {
				this.closed = true;
				this.notifications.clear();
				this.changed.signalAll();
			}
			finally {
				this.lock.unlock();
			}
		}

	}

	/**
	 * Builder for {@link HttpServletServerTransport}.
	 */
	public static final class Builder {

		private final McpServer server;

		private @Nullable McpJsonMapper jsonMapper;

		private ThreadFactory requestThreadFactory = Thread.ofVirtual().name("mcp-http-request-", 0).factory();

		private Function<HttpServletRequest, McpTransportContext> contextExtractor = request -> McpTransportContext.EMPTY;

		private List<String> allowedOrigins = DEFAULT_ALLOWED_ORIGINS;

		private List<String> allowedHosts = List.of();

		private int maxRequestSize = DEFAULT_MAX_REQUEST_SIZE;

		private Builder(McpServer server) {
			Assert.notNull(server, "server must not be null");
			this.server = server;
		}

		/**
		 * Sets the JSON mapper. Defaults to the one found by
		 * {@link McpJsonDefaults#getMapper()}. It must not indent its output: a message
		 * must be sent as a single line of a stream.
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
		 * Sets the function extracting the transport context of a request, e.g. from its
		 * headers or its authenticated principal, exposed to handlers as
		 * {@link io.modelcontextprotocol.modern.server.McpRequestContext#transportContext()}.
		 * Called on the container thread, before the request is handled. Defaults to
		 * {@link McpTransportContext#EMPTY}.
		 * @param contextExtractor the context extractor
		 * @return this builder
		 */
		public Builder contextExtractor(Function<HttpServletRequest, McpTransportContext> contextExtractor) {
			Assert.notNull(contextExtractor, "contextExtractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Sets the origins allowed to send requests, e.g. {@code https://example.com}, or
		 * {@code http://localhost:*} for any port. A request with an {@code Origin}
		 * header, as sent by browsers, is rejected with {@code 403 Forbidden} unless its
		 * origin is allowed. Requests without one are accepted. Defaults to the
		 * {@code http} origins of the local host, on any port: {@code localhost},
		 * {@code 127.0.0.1} and {@code [::1]}.
		 * @param allowedOrigins the allowed origins
		 * @return this builder
		 */
		public Builder allowedOrigins(List<String> allowedOrigins) {
			Assert.notNull(allowedOrigins, "allowedOrigins must not be null");
			this.allowedOrigins = List.copyOf(allowedOrigins);
			return this;
		}

		/**
		 * Sets the hosts allowed in the {@code Host} header, e.g. {@code localhost:8080},
		 * or {@code localhost:*} for any port. When set, other requests are rejected with
		 * {@code 403 Forbidden}. Defaults to none, meaning any host.
		 * @param allowedHosts the allowed hosts
		 * @return this builder
		 */
		public Builder allowedHosts(List<String> allowedHosts) {
			Assert.notNull(allowedHosts, "allowedHosts must not be null");
			this.allowedHosts = List.copyOf(allowedHosts);
			return this;
		}

		/**
		 * Sets the maximum size of a request body, in bytes. A larger request is rejected
		 * with {@code 413 Content Too Large} instead of being buffered in memory.
		 * Defaults to 16MB.
		 * @param maxRequestSize the maximum request size
		 * @return this builder
		 */
		public Builder maxRequestSize(int maxRequestSize) {
			Assert.isTrue(maxRequestSize > 0 && maxRequestSize < Integer.MAX_VALUE,
					"maxRequestSize must be positive, and less than Integer.MAX_VALUE");
			this.maxRequestSize = maxRequestSize;
			return this;
		}

		public HttpServletServerTransport build() {
			return new HttpServletServerTransport(this);
		}

	}

}
