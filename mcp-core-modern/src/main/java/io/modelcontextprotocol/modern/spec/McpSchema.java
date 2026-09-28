/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.util.Assert;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Wire types of the Model Context Protocol, revision {@value #LATEST_PROTOCOL_VERSION},
 * as needed by a server.
 * <p>
 * Records follow the rules in the "Evolving wire-serialized records" section of
 * {@code CONTRIBUTING.md}: spec-required fields are rejected when {@code null} at
 * construction, and replaced with a documented default (logged at {@code WARN}) when a
 * peer omits them on the wire.
 * <p>
 * Fields managed by the SDK are not part of the records: {@code resultType} is stamped
 * from {@link Result#resultType()}, and {@code _meta.io.modelcontextprotocol/serverInfo}
 * is added by the server. Cache hints ({@code ttlMs}, {@code cacheScope}) may be left
 * {@code null}, the server then applies its configured defaults.
 *
 * @see <a href=
 * "https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts">schema.ts</a>
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

	public static final String LATEST_PROTOCOL_VERSION = "2026-07-28";

	public static final String JSONRPC_VERSION = "2.0";

	public static final String RESULT_TYPE_COMPLETE = "complete";

	public static final String RESULT_TYPE_INPUT_REQUIRED = "input_required";

	// ---------------------------
	// Method names
	// ---------------------------

	public static final String METHOD_SERVER_DISCOVER = "server/discover";

	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_PROMPTS_LIST = "prompts/list";

	public static final String METHOD_PROMPTS_GET = "prompts/get";

	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	public static final String METHOD_NOTIFICATION_CANCELLED = "notifications/cancelled";

	public static final String METHOD_NOTIFICATION_PROGRESS = "notifications/progress";

	public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

	public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

	/**
	 * Well-known {@code _meta} keys.
	 */
	public static final class MetaKeys {

		private MetaKeys() {
		}

		public static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

		public static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

		public static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

		public static final String LOG_LEVEL = "io.modelcontextprotocol/logLevel";

		public static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";

		public static final String PROGRESS_TOKEN = "progressToken";

	}

	/**
	 * JSON-RPC and MCP error codes.
	 */
	public static final class ErrorCodes {

		private ErrorCodes() {
		}

		public static final int PARSE_ERROR = -32700;

		public static final int INVALID_REQUEST = -32600;

		public static final int METHOD_NOT_FOUND = -32601;

		public static final int INVALID_PARAMS = -32602;

		public static final int INTERNAL_ERROR = -32603;

		public static final int HEADER_MISMATCH = -32020;

		public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;

		public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

	}

	private static final TypeRef<Map<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Deserializes a JSON-RPC message, using the presence of {@code method}, {@code id},
	 * {@code result} and {@code error} to pick the message type.
	 * @param jsonMapper the mapper to use
	 * @param jsonText the JSON text
	 * @return the message
	 * @throws IOException if the text is not valid JSON
	 * @throws IllegalArgumentException if the text is not a JSON-RPC message
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {
		var map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);
		if (map.containsKey("method") && map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method")) {
			return jsonMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("error")) {
			return jsonMapper.convertValue(map, JSONRPCErrorResponse.class);
		}
		else if (map.containsKey("result")) {
			return jsonMapper.convertValue(map, JSONRPCResultResponse.class);
		}
		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	private static <T> T orDefault(@Nullable T value, T defaultValue, String field, List<String> missing) {
		if (value == null) {
			missing.add(field + " -> " + defaultValue);
			return defaultValue;
		}
		return value;
	}

	private static void warnIfMissing(String type, List<String> missing) {
		if (!missing.isEmpty()) {
			logger.warn("{}: missing required fields during deserialization: {}", type, String.join(", ", missing));
		}
	}

	// ---------------------------
	// JSON-RPC envelope
	// ---------------------------

	/**
	 * A JSON-RPC message. The envelope types fail fast on missing fields: a malformed
	 * envelope cannot be recovered from.
	 */
	public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

		String jsonrpc();

	}

	/**
	 * The id of a JSON-RPC request: a string or an integer. Ids are equal when they have
	 * the same type and value, whatever the integer type they were created or parsed
	 * with, and are sent back exactly as received.
	 */
	public sealed interface RequestId permits StringRequestId, LongRequestId {

		/**
		 * The id as it is on the wire.
		 * @return a {@link String} or a {@link Long}
		 */
		@JsonValue
		Object value();

		static RequestId of(String value) {
			return new StringRequestId(value);
		}

		static RequestId of(long value) {
			return new LongRequestId(value);
		}

		/**
		 * Creates an id from its JSON value.
		 * @param value a {@link String}, an {@link Integer} or a {@link Long}
		 * @return the id
		 * @throws IllegalArgumentException if the value is not a string or an integer
		 */
		@JsonCreator
		static RequestId fromJson(Object value) {
			if (value instanceof String string) {
				return of(string);
			}
			if (value instanceof Integer || value instanceof Long) {
				return of(((Number) value).longValue());
			}
			throw new IllegalArgumentException("A request id must be a string or an integer: " + value);
		}

	}

	/**
	 * A string {@link RequestId}.
	 *
	 * @param value the id
	 */
	public record StringRequestId(String value) implements RequestId {

		public StringRequestId {
			Assert.notNull(value, "value must not be null");
		}

	}

	/**
	 * An integer {@link RequestId}.
	 *
	 * @param value the id
	 */
	public record LongRequestId(Long value) implements RequestId {

		public LongRequestId {
			Assert.notNull(value, "value must not be null");
		}

	}

	/**
	 * A request that expects a response.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value #JSONRPC_VERSION}
	 * @param method the method to invoke
	 * @param id the request id
	 * @param params the method parameters
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCRequest( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("method") String method,
		@JsonProperty("id") RequestId id,
		@JsonProperty("params") @Nullable Object params) implements JSONRPCMessage { // @formatter:on

		public JSONRPCRequest {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be " + JSONRPC_VERSION);
			Assert.hasText(method, "method must not be empty");
			Assert.notNull(id, "id must not be null");
		}

		public JSONRPCRequest(String method, RequestId id, @Nullable Object params) {
			this(JSONRPC_VERSION, method, id, params);
		}

	}

	/**
	 * A notification, which does not expect a response.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value #JSONRPC_VERSION}
	 * @param method the notification method
	 * @param params the notification parameters
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCNotification( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("method") String method,
		@JsonProperty("params") @Nullable Object params) implements JSONRPCMessage { // @formatter:on

		public JSONRPCNotification {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be " + JSONRPC_VERSION);
			Assert.hasText(method, "method must not be empty");
		}

		public JSONRPCNotification(String method, @Nullable Object params) {
			this(JSONRPC_VERSION, method, params);
		}

	}

	/**
	 * A response to a request: either a result or an error.
	 */
	public sealed interface JSONRPCResponse extends JSONRPCMessage permits JSONRPCResultResponse, JSONRPCErrorResponse {

		@Nullable RequestId id();

	}

	/**
	 * A successful response.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value #JSONRPC_VERSION}
	 * @param id the id of the request this responds to
	 * @param result the result
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCResultResponse( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("id") RequestId id,
		@JsonProperty("result") Object result) implements JSONRPCResponse { // @formatter:on

		public JSONRPCResultResponse {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be " + JSONRPC_VERSION);
			Assert.notNull(id, "id must not be null");
			Assert.notNull(result, "result must not be null");
		}

		public JSONRPCResultResponse(RequestId id, Object result) {
			this(JSONRPC_VERSION, id, result);
		}

	}

	/**
	 * An error response.
	 *
	 * @param jsonrpc the JSON-RPC version, {@value #JSONRPC_VERSION}
	 * @param id the id of the request this responds to, {@code null} if it could not be
	 * determined
	 * @param error the error
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCErrorResponse( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("id") @Nullable RequestId id,
		@JsonProperty("error") JSONRPCError error) implements JSONRPCResponse { // @formatter:on

		public JSONRPCErrorResponse {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be " + JSONRPC_VERSION);
			Assert.notNull(error, "error must not be null");
		}

		public JSONRPCErrorResponse(@Nullable RequestId id, JSONRPCError error) {
			this(JSONRPC_VERSION, id, error);
		}

	}

	/**
	 * A JSON-RPC error.
	 *
	 * @param code the error code, see {@link ErrorCodes}
	 * @param message a short description of the error
	 * @param data additional information about the error
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCError( // @formatter:off
		@JsonProperty("code") Integer code,
		@JsonProperty("message") String message,
		@JsonProperty("data") @Nullable Object data) { // @formatter:on

		public JSONRPCError {
			Assert.notNull(code, "code must not be null");
			Assert.notNull(message, "message must not be null");
		}

		public JSONRPCError(Integer code, String message) {
			this(code, message, null);
		}

	}

	/**
	 * Checks that a value, such as a progress token, is a string or an integer, and
	 * returns integers as a {@link Long}: values compare equal whatever the integer type
	 * they were created or parsed with. JSON has a single number type, so the value is
	 * still sent back exactly as received.
	 */
	private static Object stringOrLong(Object value, String name) {
		Assert.notNull(value, name + " must not be null");
		if (value instanceof Integer integer) {
			return integer.longValue();
		}
		Assert.isTrue(value instanceof String || value instanceof Long, name + " must be a string or an integer");
		return value;
	}

	/**
	 * Data of an {@link ErrorCodes#UNSUPPORTED_PROTOCOL_VERSION} error.
	 *
	 * @param supported the protocol versions the server supports
	 * @param requested the protocol version the client requested
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record UnsupportedProtocolVersionData( // @formatter:off
		@JsonProperty("supported") List<String> supported,
		@JsonProperty("requested") String requested) { // @formatter:on

		public UnsupportedProtocolVersionData {
			Assert.notNull(supported, "supported must not be null");
			Assert.notNull(requested, "requested must not be null");
		}

		@JsonCreator
		static UnsupportedProtocolVersionData fromJson(@JsonProperty("supported") @Nullable List<String> supported,
				@JsonProperty("requested") @Nullable String requested) {
			List<String> missing = new ArrayList<>();
			supported = orDefault(supported, List.of(), "supported", missing);
			requested = orDefault(requested, "", "requested", missing);
			warnIfMissing("UnsupportedProtocolVersionData", missing);
			return new UnsupportedProtocolVersionData(supported, requested);
		}

	}

	/**
	 * Data of an {@link ErrorCodes#MISSING_REQUIRED_CLIENT_CAPABILITY} error.
	 *
	 * @param requiredCapabilities the capabilities the client must declare
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record MissingRequiredClientCapabilityData( // @formatter:off
		@JsonProperty("requiredCapabilities") ClientCapabilities requiredCapabilities) { // @formatter:on

		public MissingRequiredClientCapabilityData {
			Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		}

		@JsonCreator
		static MissingRequiredClientCapabilityData fromJson(
				@JsonProperty("requiredCapabilities") @Nullable ClientCapabilities requiredCapabilities) {
			List<String> missing = new ArrayList<>();
			requiredCapabilities = orDefault(requiredCapabilities, ClientCapabilities.builder().build(),
					"requiredCapabilities", missing);
			warnIfMissing("MissingRequiredClientCapabilityData", missing);
			return new MissingRequiredClientCapabilityData(requiredCapabilities);
		}

	}

	// ---------------------------
	// Base types
	// ---------------------------

	/**
	 * Parameters of a client request. On the wire, {@code _meta} is required and carries
	 * {@link MetaKeys#PROTOCOL_VERSION} and {@link MetaKeys#CLIENT_CAPABILITIES}; the
	 * server validates it and rejects the request with {@link ErrorCodes#INVALID_PARAMS}
	 * when it is missing.
	 */
	public interface Request {

		@Nullable Map<String, Object> meta();

	}

	/**
	 * Parameters of a request that can be retried with input responses, see
	 * {@link InputRequiredResult}.
	 */
	public interface InputResponseRequest extends Request {

		/**
		 * The client's responses to the {@link InputRequiredResult#inputRequests()} of a
		 * previous round, by key. Kept as raw JSON: a response carries no type
		 * discriminator, convert it with the expected type, e.g. {@link ElicitResult}.
		 * @return the input responses, {@code null} on the first round
		 */
		@Nullable Map<String, Object> inputResponses();

		/**
		 * The {@link InputRequiredResult#requestState()} of the previous round, echoed by
		 * the client. Attacker-controlled: do not trust it without verification.
		 * @return the request state, {@code null} on the first round
		 */
		@Nullable String requestState();

	}

	/**
	 * A result. {@code resultType} is not a record component: the SDK stamps it on the
	 * wire from {@link #resultType()}.
	 */
	public interface Result {

		@Nullable Map<String, Object> meta();

		/**
		 * The {@code resultType} written on the wire for this result.
		 * @return {@value #RESULT_TYPE_COMPLETE}, unless overridden
		 */
		default String resultType() {
			return RESULT_TYPE_COMPLETE;
		}

	}

	/**
	 * A result carrying caching hints. Leave them {@code null} to use the server's
	 * defaults.
	 */
	public interface CacheableResult extends Result {

		/**
		 * How long, in milliseconds, the client may consider the result fresh.
		 * @return the time-to-live, {@code >= 0}
		 */
		@Nullable Long ttlMs();

		@Nullable CacheScope cacheScope();

	}

	/**
	 * Who may cache a {@link CacheableResult}.
	 */
	public enum CacheScope {

		/**
		 * No user-specific data: any client or intermediary may cache and share it.
		 */
		PUBLIC("public"),

		/**
		 * May be cached only within the same authorization context.
		 */
		PRIVATE("private");

		private final String value;

		CacheScope(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return this.value;
		}

	}

	/**
	 * Outcome of a {@code tools/call} handler. Open: extensions contribute their own
	 * result types (design.md, D4).
	 */
	public interface CallToolOutcome extends Result {

	}

	/**
	 * Outcome of a {@code prompts/get} handler. Open: extensions contribute their own
	 * result types (design.md, D4).
	 */
	public interface GetPromptOutcome extends Result {

	}

	/**
	 * Outcome of a {@code resources/read} handler. Open: extensions contribute their own
	 * result types (design.md, D4).
	 */
	public interface ReadResourceOutcome extends Result {

	}

	/**
	 * Parameters of a request that only carries {@code _meta}, e.g.
	 * {@code server/discover}.
	 *
	 * @param meta the request metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmptyRequest( // @formatter:off
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Request { // @formatter:on
	}

	/**
	 * Parameters of a paginated list request.
	 *
	 * @param cursor an opaque token from a previous {@code nextCursor}
	 * @param meta the request metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PaginatedRequest( // @formatter:off
		@JsonProperty("cursor") @Nullable String cursor,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Request { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private @Nullable String cursor;

			private @Nullable Map<String, Object> meta;

			public Builder cursor(@Nullable String cursor) {
				this.cursor = cursor;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public PaginatedRequest build() {
				return new PaginatedRequest(this.cursor, this.meta);
			}

		}

	}

	// ---------------------------
	// Metadata, identity, capabilities
	// ---------------------------

	/**
	 * The role of a message sender.
	 */
	public enum Role {

		USER("user"), ASSISTANT("assistant");

		private final String value;

		Role(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return this.value;
		}

	}

	/**
	 * Log message severity, from RFC 5424.
	 */
	public enum LoggingLevel {

		DEBUG("debug"), INFO("info"), NOTICE("notice"), WARNING("warning"), ERROR("error"), CRITICAL("critical"),
		ALERT("alert"), EMERGENCY("emergency");

		private final String value;

		LoggingLevel(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return this.value;
		}

	}

	/**
	 * An icon for display in a user interface.
	 *
	 * @param src a URI pointing to the icon, either {@code https:} or {@code data:}
	 * @param mimeType the MIME type, if {@code src} does not make it clear
	 * @param sizes the sizes the icon can be used at, e.g. {@code 48x48} or {@code any}
	 * @param theme the theme the icon is designed for
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Icon( // @formatter:off
		@JsonProperty("src") String src,
		@JsonProperty("mimeType") @Nullable String mimeType,
		@JsonProperty("sizes") @Nullable List<String> sizes,
		@JsonProperty("theme") @Nullable Theme theme) { // @formatter:on

		public Icon {
			Assert.notNull(src, "src must not be null");
		}

		@JsonCreator
		static Icon fromJson(@JsonProperty("src") @Nullable String src,
				@JsonProperty("mimeType") @Nullable String mimeType,
				@JsonProperty("sizes") @Nullable List<String> sizes, @JsonProperty("theme") @Nullable Theme theme) {
			List<String> missing = new ArrayList<>();
			src = orDefault(src, "", "src", missing);
			warnIfMissing("Icon", missing);
			return new Icon(src, mimeType, sizes, theme);
		}

		public Icon(String src) {
			this(src, null, null, null);
		}

		/**
		 * The theme an icon is designed for.
		 */
		public enum Theme {

			LIGHT("light"), DARK("dark");

			private final String value;

			Theme(String value) {
				this.value = value;
			}

			@JsonValue
			public String value() {
				return this.value;
			}

		}

		public static Builder builder(String src) {
			return new Builder(src);
		}

		public static class Builder {

			private final String src;

			private @Nullable String mimeType;

			private @Nullable List<String> sizes;

			private @Nullable Theme theme;

			private Builder(String src) {
				Assert.hasText(src, "src must not be empty");
				this.src = src;
			}

			public Builder mimeType(@Nullable String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder sizes(@Nullable List<String> sizes) {
				this.sizes = sizes;
				return this;
			}

			public Builder theme(@Nullable Theme theme) {
				this.theme = theme;
				return this;
			}

			public Icon build() {
				return new Icon(this.src, this.mimeType, this.sizes, this.theme);
			}

		}

	}

	/**
	 * Name and version of an MCP implementation, client or server.
	 *
	 * @param name the programmatic name
	 * @param title a human-readable name
	 * @param version the version
	 * @param description a human-readable description
	 * @param icons icons for display in a user interface
	 * @param websiteUrl the URL of the implementation's website
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Implementation( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("version") String version,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("icons") @Nullable List<Icon> icons,
		@JsonProperty("websiteUrl") @Nullable String websiteUrl) { // @formatter:on

		public Implementation {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(version, "version must not be null");
		}

		@JsonCreator
		static Implementation fromJson(@JsonProperty("name") @Nullable String name,
				@JsonProperty("title") @Nullable String title, @JsonProperty("version") @Nullable String version,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("icons") @Nullable List<Icon> icons,
				@JsonProperty("websiteUrl") @Nullable String websiteUrl) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			version = orDefault(version, "", "version", missing);
			warnIfMissing("Implementation", missing);
			return new Implementation(name, title, version, description, icons, websiteUrl);
		}

		public static Builder builder(String name, String version) {
			return new Builder(name, version);
		}

		public static class Builder {

			private final String name;

			private final String version;

			private @Nullable String title;

			private @Nullable String description;

			private @Nullable List<Icon> icons;

			private @Nullable String websiteUrl;

			private Builder(String name, String version) {
				Assert.hasText(name, "name must not be empty");
				Assert.hasText(version, "version must not be empty");
				this.name = name;
				this.version = version;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder icons(@Nullable List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder websiteUrl(@Nullable String websiteUrl) {
				this.websiteUrl = websiteUrl;
				return this;
			}

			public Implementation build() {
				return new Implementation(this.name, this.title, this.version, this.description, this.icons,
						this.websiteUrl);
			}

		}

	}

	/**
	 * Capabilities a client declares, on every request, in
	 * {@link MetaKeys#CLIENT_CAPABILITIES}.
	 *
	 * @param experimental non-standard experimental capabilities
	 * @param roots present if the client can list roots
	 * @param sampling present if the client supports sampling
	 * @param elicitation present if the client supports elicitation
	 * @param extensions supported extensions, by identifier
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ClientCapabilities( // @formatter:off
		@JsonProperty("experimental") @Nullable Map<String, Object> experimental,
		@JsonProperty("roots") @Nullable Map<String, Object> roots,
		@JsonProperty("sampling") @Nullable Sampling sampling,
		@JsonProperty("elicitation") @Nullable Elicitation elicitation,
		@JsonProperty("extensions") @Nullable Map<String, Object> extensions) { // @formatter:on

		/**
		 * Sampling support.
		 *
		 * @param context present if the client supports {@code includeContext}
		 * @param tools present if the client supports tool use in sampling
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Sampling( // @formatter:off
			@JsonProperty("context") @Nullable Map<String, Object> context,
			@JsonProperty("tools") @Nullable Map<String, Object> tools) { // @formatter:on
		}

		/**
		 * Elicitation support.
		 *
		 * @param form present if the client supports form mode
		 * @param url present if the client supports URL mode
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Elicitation( // @formatter:off
			@JsonProperty("form") @Nullable Map<String, Object> form,
			@JsonProperty("url") @Nullable Map<String, Object> url) { // @formatter:on
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private @Nullable Map<String, Object> experimental;

			private @Nullable Map<String, Object> roots;

			private @Nullable Sampling sampling;

			private @Nullable Elicitation elicitation;

			private @Nullable Map<String, Object> extensions;

			public Builder experimental(@Nullable Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder roots() {
				this.roots = Map.of();
				return this;
			}

			public Builder sampling() {
				this.sampling = new Sampling(null, null);
				return this;
			}

			public Builder elicitation(boolean form, boolean url) {
				this.elicitation = new Elicitation(form ? Map.of() : null, url ? Map.of() : null);
				return this;
			}

			public Builder extensions(@Nullable Map<String, Object> extensions) {
				this.extensions = extensions;
				return this;
			}

			public ClientCapabilities build() {
				return new ClientCapabilities(this.experimental, this.roots, this.sampling, this.elicitation,
						this.extensions);
			}

		}

	}

	/**
	 * Capabilities a server supports, returned by {@code server/discover}.
	 *
	 * @param experimental non-standard experimental capabilities
	 * @param logging present if the server can send log messages
	 * @param completions present if the server supports argument completion
	 * @param prompts present if the server offers prompts
	 * @param resources present if the server offers resources
	 * @param tools present if the server offers tools
	 * @param extensions supported extensions, by identifier
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ServerCapabilities( // @formatter:off
		@JsonProperty("experimental") @Nullable Map<String, Object> experimental,
		@JsonProperty("logging") @Nullable Map<String, Object> logging,
		@JsonProperty("completions") @Nullable Map<String, Object> completions,
		@JsonProperty("prompts") @Nullable PromptCapabilities prompts,
		@JsonProperty("resources") @Nullable ResourceCapabilities resources,
		@JsonProperty("tools") @Nullable ToolCapabilities tools,
		@JsonProperty("extensions") @Nullable Map<String, Object> extensions) { // @formatter:on

		/**
		 * Prompt capabilities of the server.
		 *
		 * @param listChanged whether the server notifies about prompt list changes
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record PromptCapabilities(@JsonProperty("listChanged") @Nullable Boolean listChanged) {
		}

		/**
		 * Resource capabilities of the server.
		 *
		 * @param subscribe whether the server supports resource subscriptions
		 * @param listChanged whether the server notifies about resource list changes
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ResourceCapabilities( // @formatter:off
			@JsonProperty("subscribe") @Nullable Boolean subscribe,
			@JsonProperty("listChanged") @Nullable Boolean listChanged) { // @formatter:on
		}

		/**
		 * Tool capabilities of the server.
		 *
		 * @param listChanged whether the server notifies about tool list changes
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCapabilities(@JsonProperty("listChanged") @Nullable Boolean listChanged) {
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private @Nullable Map<String, Object> experimental;

			private @Nullable Map<String, Object> logging;

			private @Nullable Map<String, Object> completions;

			private @Nullable PromptCapabilities prompts;

			private @Nullable ResourceCapabilities resources;

			private @Nullable ToolCapabilities tools;

			private @Nullable Map<String, Object> extensions;

			public Builder experimental(@Nullable Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder logging() {
				this.logging = Map.of();
				return this;
			}

			public Builder completions() {
				this.completions = Map.of();
				return this;
			}

			public Builder prompts(@Nullable Boolean listChanged) {
				this.prompts = new PromptCapabilities(listChanged);
				return this;
			}

			public Builder resources(@Nullable Boolean subscribe, @Nullable Boolean listChanged) {
				this.resources = new ResourceCapabilities(subscribe, listChanged);
				return this;
			}

			public Builder tools(@Nullable Boolean listChanged) {
				this.tools = new ToolCapabilities(listChanged);
				return this;
			}

			public Builder extensions(@Nullable Map<String, Object> extensions) {
				this.extensions = extensions;
				return this;
			}

			public ServerCapabilities build() {
				return new ServerCapabilities(this.experimental, this.logging, this.completions, this.prompts,
						this.resources, this.tools, this.extensions);
			}

		}

	}

	/**
	 * Optional annotations that inform how clients use or display an object.
	 *
	 * @param audience who the object is intended for
	 * @param priority importance, from 0 (least) to 1 (most)
	 * @param lastModified ISO 8601 timestamp of the last modification
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Annotations( // @formatter:off
		@JsonProperty("audience") @Nullable List<Role> audience,
		@JsonProperty("priority") @Nullable Double priority,
		@JsonProperty("lastModified") @Nullable String lastModified) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private @Nullable List<Role> audience;

			private @Nullable Double priority;

			private @Nullable String lastModified;

			public Builder audience(@Nullable List<Role> audience) {
				this.audience = audience;
				return this;
			}

			public Builder priority(@Nullable Double priority) {
				this.priority = priority;
				return this;
			}

			public Builder lastModified(@Nullable String lastModified) {
				this.lastModified = lastModified;
				return this;
			}

			public Annotations build() {
				return new Annotations(this.audience, this.priority, this.lastModified);
			}

		}

	}

	// ---------------------------
	// Discovery
	// ---------------------------

	/**
	 * Result of {@code server/discover}.
	 *
	 * @param supportedVersions the protocol versions the server supports
	 * @param capabilities the server capabilities
	 * @param instructions guidance for LLMs on how to use the server
	 * @param ttlMs caching hint, see {@link CacheableResult}
	 * @param cacheScope caching hint, see {@link CacheableResult}
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DiscoverResult( // @formatter:off
		@JsonProperty("supportedVersions") List<String> supportedVersions,
		@JsonProperty("capabilities") ServerCapabilities capabilities,
		@JsonProperty("instructions") @Nullable String instructions,
		@JsonProperty("ttlMs") @Nullable Long ttlMs,
		@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public DiscoverResult {
			Assert.notNull(supportedVersions, "supportedVersions must not be null");
			Assert.notNull(capabilities, "capabilities must not be null");
		}

		@JsonCreator
		static DiscoverResult fromJson(@JsonProperty("supportedVersions") @Nullable List<String> supportedVersions,
				@JsonProperty("capabilities") @Nullable ServerCapabilities capabilities,
				@JsonProperty("instructions") @Nullable String instructions,
				@JsonProperty("ttlMs") @Nullable Long ttlMs,
				@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			supportedVersions = orDefault(supportedVersions, List.of(), "supportedVersions", missing);
			capabilities = orDefault(capabilities, ServerCapabilities.builder().build(), "capabilities", missing);
			warnIfMissing("DiscoverResult", missing);
			return new DiscoverResult(supportedVersions, capabilities, instructions, ttlMs, cacheScope, meta);
		}

		public static Builder builder(List<String> supportedVersions, ServerCapabilities capabilities) {
			return new Builder(supportedVersions, capabilities);
		}

		public static class Builder {

			private final List<String> supportedVersions;

			private final ServerCapabilities capabilities;

			private @Nullable String instructions;

			private @Nullable Long ttlMs;

			private @Nullable CacheScope cacheScope;

			private @Nullable Map<String, Object> meta;

			private Builder(List<String> supportedVersions, ServerCapabilities capabilities) {
				Assert.notNull(supportedVersions, "supportedVersions must not be null");
				Assert.notNull(capabilities, "capabilities must not be null");
				this.supportedVersions = supportedVersions;
				this.capabilities = capabilities;
			}

			public Builder instructions(@Nullable String instructions) {
				this.instructions = instructions;
				return this;
			}

			public Builder ttlMs(@Nullable Long ttlMs) {
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(@Nullable CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public DiscoverResult build() {
				return new DiscoverResult(this.supportedVersions, this.capabilities, this.instructions, this.ttlMs,
						this.cacheScope, this.meta);
			}

		}

	}

	// ---------------------------
	// Tools
	// ---------------------------

	/**
	 * A tool the client can call.
	 *
	 * @param name the programmatic name, unique within the server
	 * @param title a human-readable name
	 * @param description a human-readable description, a hint to the model
	 * @param inputSchema JSON Schema of the tool arguments, of {@code type: object}
	 * @param outputSchema JSON Schema of the tool's {@code structuredContent}
	 * @param annotations hints about the tool's behavior
	 * @param icons icons for display in a user interface
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Tool( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("inputSchema") Map<String, Object> inputSchema,
		@JsonProperty("outputSchema") @Nullable Map<String, Object> outputSchema,
		@JsonProperty("annotations") @Nullable ToolAnnotations annotations,
		@JsonProperty("icons") @Nullable List<Icon> icons,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		private static final Map<String, Object> EMPTY_OBJECT_SCHEMA = Map.of("type", "object");

		public Tool {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(inputSchema, "inputSchema must not be null");
		}

		@JsonCreator
		static Tool fromJson(@JsonProperty("name") @Nullable String name, @JsonProperty("title") @Nullable String title,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("inputSchema") @Nullable Map<String, Object> inputSchema,
				@JsonProperty("outputSchema") @Nullable Map<String, Object> outputSchema,
				@JsonProperty("annotations") @Nullable ToolAnnotations annotations,
				@JsonProperty("icons") @Nullable List<Icon> icons,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			inputSchema = orDefault(inputSchema, EMPTY_OBJECT_SCHEMA, "inputSchema", missing);
			warnIfMissing("Tool", missing);
			return new Tool(name, title, description, inputSchema, outputSchema, annotations, icons, meta);
		}

		/**
		 * A builder for a tool. The input schema defaults to an empty object schema,
		 * {@code {"type": "object"}}.
		 * @param name the tool name
		 * @return the builder
		 */
		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private @Nullable String title;

			private @Nullable String description;

			private Map<String, Object> inputSchema = EMPTY_OBJECT_SCHEMA;

			private @Nullable Map<String, Object> outputSchema;

			private @Nullable ToolAnnotations annotations;

			private @Nullable List<Icon> icons;

			private @Nullable Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder inputSchema(Map<String, Object> inputSchema) {
				Assert.notNull(inputSchema, "inputSchema must not be null");
				this.inputSchema = inputSchema;
				return this;
			}

			public Builder outputSchema(@Nullable Map<String, Object> outputSchema) {
				this.outputSchema = outputSchema;
				return this;
			}

			public Builder annotations(@Nullable ToolAnnotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(@Nullable List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Tool build() {
				return new Tool(this.name, this.title, this.description, this.inputSchema, this.outputSchema,
						this.annotations, this.icons, this.meta);
			}

		}

	}

	/**
	 * Hints about a tool's behavior. Not guaranteed to be accurate: clients must not make
	 * trust decisions based on annotations from untrusted servers.
	 *
	 * @param title a human-readable name
	 * @param readOnlyHint whether the tool does not modify its environment
	 * @param destructiveHint whether the tool may perform destructive updates
	 * @param idempotentHint whether repeated calls with the same arguments have no
	 * additional effect
	 * @param openWorldHint whether the tool interacts with external entities
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ToolAnnotations( // @formatter:off
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("readOnlyHint") @Nullable Boolean readOnlyHint,
		@JsonProperty("destructiveHint") @Nullable Boolean destructiveHint,
		@JsonProperty("idempotentHint") @Nullable Boolean idempotentHint,
		@JsonProperty("openWorldHint") @Nullable Boolean openWorldHint) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private @Nullable String title;

			private @Nullable Boolean readOnlyHint;

			private @Nullable Boolean destructiveHint;

			private @Nullable Boolean idempotentHint;

			private @Nullable Boolean openWorldHint;

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder readOnlyHint(@Nullable Boolean readOnlyHint) {
				this.readOnlyHint = readOnlyHint;
				return this;
			}

			public Builder destructiveHint(@Nullable Boolean destructiveHint) {
				this.destructiveHint = destructiveHint;
				return this;
			}

			public Builder idempotentHint(@Nullable Boolean idempotentHint) {
				this.idempotentHint = idempotentHint;
				return this;
			}

			public Builder openWorldHint(@Nullable Boolean openWorldHint) {
				this.openWorldHint = openWorldHint;
				return this;
			}

			public ToolAnnotations build() {
				return new ToolAnnotations(this.title, this.readOnlyHint, this.destructiveHint, this.idempotentHint,
						this.openWorldHint);
			}

		}

	}

	/**
	 * Result of {@code tools/list}.
	 *
	 * @param tools the tools
	 * @param nextCursor the cursor of the next page, {@code null} on the last page
	 * @param ttlMs caching hint, see {@link CacheableResult}
	 * @param cacheScope caching hint, see {@link CacheableResult}
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListToolsResult( // @formatter:off
		@JsonProperty("tools") List<Tool> tools,
		@JsonProperty("nextCursor") @Nullable String nextCursor,
		@JsonProperty("ttlMs") @Nullable Long ttlMs,
		@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListToolsResult {
			Assert.notNull(tools, "tools must not be null");
		}

		@JsonCreator
		static ListToolsResult fromJson(@JsonProperty("tools") @Nullable List<Tool> tools,
				@JsonProperty("nextCursor") @Nullable String nextCursor, @JsonProperty("ttlMs") @Nullable Long ttlMs,
				@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			tools = orDefault(tools, List.of(), "tools", missing);
			warnIfMissing("ListToolsResult", missing);
			return new ListToolsResult(tools, nextCursor, ttlMs, cacheScope, meta);
		}

		public static Builder builder(List<Tool> tools) {
			return new Builder(tools);
		}

		public static class Builder {

			private final List<Tool> tools;

			private @Nullable String nextCursor;

			private @Nullable Long ttlMs;

			private @Nullable CacheScope cacheScope;

			private @Nullable Map<String, Object> meta;

			private Builder(List<Tool> tools) {
				Assert.notNull(tools, "tools must not be null");
				this.tools = tools;
			}

			public Builder nextCursor(@Nullable String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(@Nullable Long ttlMs) {
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(@Nullable CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListToolsResult build() {
				return new ListToolsResult(this.tools, this.nextCursor, this.ttlMs, this.cacheScope, this.meta);
			}

		}

	}

	/**
	 * Parameters of {@code tools/call}.
	 *
	 * @param name the name of the tool to call
	 * @param arguments the tool arguments
	 * @param inputResponses see {@link InputResponseRequest#inputResponses()}
	 * @param requestState see {@link InputResponseRequest#requestState()}
	 * @param meta the request metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolRequest( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("arguments") @Nullable Map<String, Object> arguments,
		@JsonProperty("inputResponses") @Nullable Map<String, Object> inputResponses,
		@JsonProperty("requestState") @Nullable String requestState,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements InputResponseRequest { // @formatter:on

		public CallToolRequest {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static CallToolRequest fromJson(@JsonProperty("name") @Nullable String name,
				@JsonProperty("arguments") @Nullable Map<String, Object> arguments,
				@JsonProperty("inputResponses") @Nullable Map<String, Object> inputResponses,
				@JsonProperty("requestState") @Nullable String requestState,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			warnIfMissing("CallToolRequest", missing);
			return new CallToolRequest(name, arguments, inputResponses, requestState, meta);
		}

		public CallToolRequest(String name, @Nullable Map<String, Object> arguments) {
			this(name, arguments, null, null, null);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private @Nullable Map<String, Object> arguments;

			private @Nullable Map<String, Object> inputResponses;

			private @Nullable String requestState;

			private @Nullable Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(@Nullable Map<String, Object> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder inputResponses(@Nullable Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(@Nullable String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CallToolRequest build() {
				return new CallToolRequest(this.name, this.arguments, this.inputResponses, this.requestState,
						this.meta);
			}

		}

	}

	/**
	 * Result of a completed {@code tools/call}.
	 *
	 * @param content the unstructured result of the call
	 * @param structuredContent the structured result, matching the tool's
	 * {@code outputSchema}
	 * @param isError whether the call ended in a tool execution error
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolResult( // @formatter:off
		@JsonProperty("content") List<Content> content,
		@JsonProperty("structuredContent") @Nullable Object structuredContent,
		@JsonProperty("isError") @Nullable Boolean isError,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements CallToolOutcome { // @formatter:on

		public CallToolResult {
			Assert.notNull(content, "content must not be null");
		}

		@JsonCreator
		static CallToolResult fromJson(@JsonProperty("content") @Nullable List<Content> content,
				@JsonProperty("structuredContent") @Nullable Object structuredContent,
				@JsonProperty("isError") @Nullable Boolean isError,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			content = orDefault(content, List.of(), "content", missing);
			warnIfMissing("CallToolResult", missing);
			return new CallToolResult(content, structuredContent, isError, meta);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private final List<Content> content = new ArrayList<>();

			private @Nullable Object structuredContent;

			private @Nullable Boolean isError;

			private @Nullable Map<String, Object> meta;

			public Builder content(List<Content> content) {
				Assert.notNull(content, "content must not be null");
				this.content.addAll(content);
				return this;
			}

			public Builder addContent(Content content) {
				Assert.notNull(content, "content must not be null");
				this.content.add(content);
				return this;
			}

			public Builder addTextContent(String text) {
				return addContent(new TextContent(text));
			}

			public Builder structuredContent(@Nullable Object structuredContent) {
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder isError(@Nullable Boolean isError) {
				this.isError = isError;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CallToolResult build() {
				return new CallToolResult(List.copyOf(this.content), this.structuredContent, this.isError, this.meta);
			}

		}

	}

	// ---------------------------
	// Resources
	// ---------------------------

	/**
	 * A resource the server can read.
	 *
	 * @param uri the URI of the resource
	 * @param name the programmatic name
	 * @param title a human-readable name
	 * @param description a human-readable description
	 * @param mimeType the MIME type, if known
	 * @param annotations optional annotations
	 * @param size the size in bytes of the raw content, if known
	 * @param icons icons for display in a user interface
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Resource( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("mimeType") @Nullable String mimeType,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("size") @Nullable Long size,
		@JsonProperty("icons") @Nullable List<Icon> icons,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		public Resource {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static Resource fromJson(@JsonProperty("uri") @Nullable String uri, @JsonProperty("name") @Nullable String name,
				@JsonProperty("title") @Nullable String title,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("mimeType") @Nullable String mimeType,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("size") @Nullable Long size, @JsonProperty("icons") @Nullable List<Icon> icons,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			uri = orDefault(uri, "", "uri", missing);
			name = orDefault(name, "", "name", missing);
			warnIfMissing("Resource", missing);
			return new Resource(uri, name, title, description, mimeType, annotations, size, icons, meta);
		}

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		public static class Builder {

			private final String uri;

			private final String name;

			private @Nullable String title;

			private @Nullable String description;

			private @Nullable String mimeType;

			private @Nullable Annotations annotations;

			private @Nullable Long size;

			private @Nullable List<Icon> icons;

			private @Nullable Map<String, Object> meta;

			private Builder(String uri, String name) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uri = uri;
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(@Nullable String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder size(@Nullable Long size) {
				this.size = size;
				return this;
			}

			public Builder icons(@Nullable List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Resource build() {
				return new Resource(this.uri, this.name, this.title, this.description, this.mimeType, this.annotations,
						this.size, this.icons, this.meta);
			}

		}

	}

	/**
	 * A template for resources, with an RFC 6570 URI template.
	 *
	 * @param uriTemplate the URI template
	 * @param name the programmatic name
	 * @param title a human-readable name
	 * @param description a human-readable description
	 * @param mimeType the MIME type of all matching resources, if they share one
	 * @param annotations optional annotations
	 * @param icons icons for display in a user interface
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceTemplate( // @formatter:off
		@JsonProperty("uriTemplate") String uriTemplate,
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("mimeType") @Nullable String mimeType,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("icons") @Nullable List<Icon> icons,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		public ResourceTemplate {
			Assert.notNull(uriTemplate, "uriTemplate must not be null");
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static ResourceTemplate fromJson(@JsonProperty("uriTemplate") @Nullable String uriTemplate,
				@JsonProperty("name") @Nullable String name, @JsonProperty("title") @Nullable String title,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("mimeType") @Nullable String mimeType,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("icons") @Nullable List<Icon> icons,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			uriTemplate = orDefault(uriTemplate, "", "uriTemplate", missing);
			name = orDefault(name, "", "name", missing);
			warnIfMissing("ResourceTemplate", missing);
			return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, icons, meta);
		}

		public static Builder builder(String uriTemplate, String name) {
			return new Builder(uriTemplate, name);
		}

		public static class Builder {

			private final String uriTemplate;

			private final String name;

			private @Nullable String title;

			private @Nullable String description;

			private @Nullable String mimeType;

			private @Nullable Annotations annotations;

			private @Nullable List<Icon> icons;

			private @Nullable Map<String, Object> meta;

			private Builder(String uriTemplate, String name) {
				Assert.hasText(uriTemplate, "uriTemplate must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uriTemplate = uriTemplate;
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(@Nullable String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(@Nullable List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceTemplate build() {
				return new ResourceTemplate(this.uriTemplate, this.name, this.title, this.description, this.mimeType,
						this.annotations, this.icons, this.meta);
			}

		}

	}

	/**
	 * The contents of a resource: {@link TextResourceContents} or
	 * {@link BlobResourceContents}, told apart by their fields.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public sealed interface ResourceContents permits TextResourceContents, BlobResourceContents {

		String uri();

		@Nullable String mimeType();

		@Nullable Map<String, Object> meta();

	}

	/**
	 * Text contents of a resource.
	 *
	 * @param uri the URI of the resource
	 * @param mimeType the MIME type, if known
	 * @param text the text
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextResourceContents( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("mimeType") @Nullable String mimeType,
		@JsonProperty("text") String text,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ResourceContents { // @formatter:on

		public TextResourceContents {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(text, "text must not be null");
		}

		@JsonCreator
		static TextResourceContents fromJson(@JsonProperty("uri") @Nullable String uri,
				@JsonProperty("mimeType") @Nullable String mimeType, @JsonProperty("text") @Nullable String text,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			uri = orDefault(uri, "", "uri", missing);
			text = orDefault(text, "", "text", missing);
			warnIfMissing("TextResourceContents", missing);
			return new TextResourceContents(uri, mimeType, text, meta);
		}

		public TextResourceContents(String uri, @Nullable String mimeType, String text) {
			this(uri, mimeType, text, null);
		}

		public static Builder builder(String uri, String text) {
			return new Builder(uri, text);
		}

		public static class Builder {

			private final String uri;

			private final String text;

			private @Nullable String mimeType;

			private @Nullable Map<String, Object> meta;

			private Builder(String uri, String text) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(text, "text must not be null");
				this.uri = uri;
				this.text = text;
			}

			public Builder mimeType(@Nullable String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TextResourceContents build() {
				return new TextResourceContents(this.uri, this.mimeType, this.text, this.meta);
			}

		}

	}

	/**
	 * Binary contents of a resource.
	 *
	 * @param uri the URI of the resource
	 * @param mimeType the MIME type, if known
	 * @param blob the base64-encoded data
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BlobResourceContents( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("mimeType") @Nullable String mimeType,
		@JsonProperty("blob") String blob,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ResourceContents { // @formatter:on

		public BlobResourceContents {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(blob, "blob must not be null");
		}

		@JsonCreator
		static BlobResourceContents fromJson(@JsonProperty("uri") @Nullable String uri,
				@JsonProperty("mimeType") @Nullable String mimeType, @JsonProperty("blob") @Nullable String blob,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			uri = orDefault(uri, "", "uri", missing);
			blob = orDefault(blob, "", "blob", missing);
			warnIfMissing("BlobResourceContents", missing);
			return new BlobResourceContents(uri, mimeType, blob, meta);
		}

		public BlobResourceContents(String uri, @Nullable String mimeType, String blob) {
			this(uri, mimeType, blob, null);
		}

		public static Builder builder(String uri, String blob) {
			return new Builder(uri, blob);
		}

		public static class Builder {

			private final String uri;

			private final String blob;

			private @Nullable String mimeType;

			private @Nullable Map<String, Object> meta;

			private Builder(String uri, String blob) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(blob, "blob must not be null");
				this.uri = uri;
				this.blob = blob;
			}

			public Builder mimeType(@Nullable String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public BlobResourceContents build() {
				return new BlobResourceContents(this.uri, this.mimeType, this.blob, this.meta);
			}

		}

	}

	/**
	 * Result of {@code resources/list}.
	 *
	 * @param resources the resources
	 * @param nextCursor the cursor of the next page, {@code null} on the last page
	 * @param ttlMs caching hint, see {@link CacheableResult}
	 * @param cacheScope caching hint, see {@link CacheableResult}
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourcesResult( // @formatter:off
		@JsonProperty("resources") List<Resource> resources,
		@JsonProperty("nextCursor") @Nullable String nextCursor,
		@JsonProperty("ttlMs") @Nullable Long ttlMs,
		@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListResourcesResult {
			Assert.notNull(resources, "resources must not be null");
		}

		@JsonCreator
		static ListResourcesResult fromJson(@JsonProperty("resources") @Nullable List<Resource> resources,
				@JsonProperty("nextCursor") @Nullable String nextCursor, @JsonProperty("ttlMs") @Nullable Long ttlMs,
				@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			resources = orDefault(resources, List.of(), "resources", missing);
			warnIfMissing("ListResourcesResult", missing);
			return new ListResourcesResult(resources, nextCursor, ttlMs, cacheScope, meta);
		}

		public static Builder builder(List<Resource> resources) {
			return new Builder(resources);
		}

		public static class Builder {

			private final List<Resource> resources;

			private @Nullable String nextCursor;

			private @Nullable Long ttlMs;

			private @Nullable CacheScope cacheScope;

			private @Nullable Map<String, Object> meta;

			private Builder(List<Resource> resources) {
				Assert.notNull(resources, "resources must not be null");
				this.resources = resources;
			}

			public Builder nextCursor(@Nullable String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(@Nullable Long ttlMs) {
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(@Nullable CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourcesResult build() {
				return new ListResourcesResult(this.resources, this.nextCursor, this.ttlMs, this.cacheScope, this.meta);
			}

		}

	}

	/**
	 * Result of {@code resources/templates/list}.
	 *
	 * @param resourceTemplates the resource templates
	 * @param nextCursor the cursor of the next page, {@code null} on the last page
	 * @param ttlMs caching hint, see {@link CacheableResult}
	 * @param cacheScope caching hint, see {@link CacheableResult}
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourceTemplatesResult( // @formatter:off
		@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
		@JsonProperty("nextCursor") @Nullable String nextCursor,
		@JsonProperty("ttlMs") @Nullable Long ttlMs,
		@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListResourceTemplatesResult {
			Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
		}

		@JsonCreator
		static ListResourceTemplatesResult fromJson(
				@JsonProperty("resourceTemplates") @Nullable List<ResourceTemplate> resourceTemplates,
				@JsonProperty("nextCursor") @Nullable String nextCursor, @JsonProperty("ttlMs") @Nullable Long ttlMs,
				@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			resourceTemplates = orDefault(resourceTemplates, List.of(), "resourceTemplates", missing);
			warnIfMissing("ListResourceTemplatesResult", missing);
			return new ListResourceTemplatesResult(resourceTemplates, nextCursor, ttlMs, cacheScope, meta);
		}

		public static Builder builder(List<ResourceTemplate> resourceTemplates) {
			return new Builder(resourceTemplates);
		}

		public static class Builder {

			private final List<ResourceTemplate> resourceTemplates;

			private @Nullable String nextCursor;

			private @Nullable Long ttlMs;

			private @Nullable CacheScope cacheScope;

			private @Nullable Map<String, Object> meta;

			private Builder(List<ResourceTemplate> resourceTemplates) {
				Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
				this.resourceTemplates = resourceTemplates;
			}

			public Builder nextCursor(@Nullable String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(@Nullable Long ttlMs) {
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(@Nullable CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourceTemplatesResult build() {
				return new ListResourceTemplatesResult(this.resourceTemplates, this.nextCursor, this.ttlMs,
						this.cacheScope, this.meta);
			}

		}

	}

	/**
	 * Parameters of {@code resources/read}.
	 *
	 * @param uri the URI of the resource to read
	 * @param inputResponses see {@link InputResponseRequest#inputResponses()}
	 * @param requestState see {@link InputResponseRequest#requestState()}
	 * @param meta the request metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("inputResponses") @Nullable Map<String, Object> inputResponses,
		@JsonProperty("requestState") @Nullable String requestState,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements InputResponseRequest { // @formatter:on

		public ReadResourceRequest {
			Assert.notNull(uri, "uri must not be null");
		}

		@JsonCreator
		static ReadResourceRequest fromJson(@JsonProperty("uri") @Nullable String uri,
				@JsonProperty("inputResponses") @Nullable Map<String, Object> inputResponses,
				@JsonProperty("requestState") @Nullable String requestState,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			uri = orDefault(uri, "", "uri", missing);
			warnIfMissing("ReadResourceRequest", missing);
			return new ReadResourceRequest(uri, inputResponses, requestState, meta);
		}

		public ReadResourceRequest(String uri) {
			this(uri, null, null, null);
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static class Builder {

			private final String uri;

			private @Nullable Map<String, Object> inputResponses;

			private @Nullable String requestState;

			private @Nullable Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder inputResponses(@Nullable Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(@Nullable String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceRequest build() {
				return new ReadResourceRequest(this.uri, this.inputResponses, this.requestState, this.meta);
			}

		}

	}

	/**
	 * Result of a completed {@code resources/read}.
	 *
	 * @param contents the contents of the resource
	 * @param ttlMs caching hint, see {@link CacheableResult}
	 * @param cacheScope caching hint, see {@link CacheableResult}
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceResult( // @formatter:off
		@JsonProperty("contents") List<ResourceContents> contents,
		@JsonProperty("ttlMs") @Nullable Long ttlMs,
		@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements ReadResourceOutcome, CacheableResult { // @formatter:on

		public ReadResourceResult {
			Assert.notNull(contents, "contents must not be null");
		}

		@JsonCreator
		static ReadResourceResult fromJson(@JsonProperty("contents") @Nullable List<ResourceContents> contents,
				@JsonProperty("ttlMs") @Nullable Long ttlMs,
				@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			contents = orDefault(contents, List.of(), "contents", missing);
			warnIfMissing("ReadResourceResult", missing);
			return new ReadResourceResult(contents, ttlMs, cacheScope, meta);
		}

		public ReadResourceResult(List<ResourceContents> contents) {
			this(contents, null, null, null);
		}

		public static Builder builder(List<ResourceContents> contents) {
			return new Builder(contents);
		}

		public static class Builder {

			private final List<ResourceContents> contents;

			private @Nullable Long ttlMs;

			private @Nullable CacheScope cacheScope;

			private @Nullable Map<String, Object> meta;

			private Builder(List<ResourceContents> contents) {
				Assert.notNull(contents, "contents must not be null");
				this.contents = contents;
			}

			public Builder ttlMs(@Nullable Long ttlMs) {
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(@Nullable CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceResult build() {
				return new ReadResourceResult(this.contents, this.ttlMs, this.cacheScope, this.meta);
			}

		}

	}

	// ---------------------------
	// Prompts
	// ---------------------------

	/**
	 * A prompt or prompt template the server offers.
	 *
	 * @param name the programmatic name
	 * @param title a human-readable name
	 * @param description a human-readable description
	 * @param arguments the arguments the prompt accepts
	 * @param icons icons for display in a user interface
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Prompt( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("arguments") @Nullable List<PromptArgument> arguments,
		@JsonProperty("icons") @Nullable List<Icon> icons,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		public Prompt {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static Prompt fromJson(@JsonProperty("name") @Nullable String name,
				@JsonProperty("title") @Nullable String title,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("arguments") @Nullable List<PromptArgument> arguments,
				@JsonProperty("icons") @Nullable List<Icon> icons,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			warnIfMissing("Prompt", missing);
			return new Prompt(name, title, description, arguments, icons, meta);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private @Nullable String title;

			private @Nullable String description;

			private @Nullable List<PromptArgument> arguments;

			private @Nullable List<Icon> icons;

			private @Nullable Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder arguments(@Nullable List<PromptArgument> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder icons(@Nullable List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Prompt build() {
				return new Prompt(this.name, this.title, this.description, this.arguments, this.icons, this.meta);
			}

		}

	}

	/**
	 * An argument a prompt accepts.
	 *
	 * @param name the programmatic name
	 * @param title a human-readable name
	 * @param description a human-readable description
	 * @param required whether the argument must be provided
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptArgument( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("required") @Nullable Boolean required) { // @formatter:on

		public PromptArgument {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static PromptArgument fromJson(@JsonProperty("name") @Nullable String name,
				@JsonProperty("title") @Nullable String title,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("required") @Nullable Boolean required) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			warnIfMissing("PromptArgument", missing);
			return new PromptArgument(name, title, description, required);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private @Nullable String title;

			private @Nullable String description;

			private @Nullable Boolean required;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder required(@Nullable Boolean required) {
				this.required = required;
				return this;
			}

			public PromptArgument build() {
				return new PromptArgument(this.name, this.title, this.description, this.required);
			}

		}

	}

	/**
	 * A message returned as part of a prompt.
	 *
	 * @param role the sender of the message
	 * @param content the content of the message
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptMessage( // @formatter:off
		@JsonProperty("role") Role role,
		@JsonProperty("content") Content content) { // @formatter:on

		public PromptMessage {
			Assert.notNull(role, "role must not be null");
			Assert.notNull(content, "content must not be null");
		}

		@JsonCreator
		static PromptMessage fromJson(@JsonProperty("role") @Nullable Role role,
				@JsonProperty("content") @Nullable Content content) {
			List<String> missing = new ArrayList<>();
			role = orDefault(role, Role.USER, "role", missing);
			content = orDefault(content, new TextContent(""), "content", missing);
			warnIfMissing("PromptMessage", missing);
			return new PromptMessage(role, content);
		}

	}

	/**
	 * Parameters of {@code prompts/get}.
	 *
	 * @param name the name of the prompt
	 * @param arguments arguments for templating the prompt
	 * @param inputResponses see {@link InputResponseRequest#inputResponses()}
	 * @param requestState see {@link InputResponseRequest#requestState()}
	 * @param meta the request metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptRequest( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("arguments") @Nullable Map<String, String> arguments,
		@JsonProperty("inputResponses") @Nullable Map<String, Object> inputResponses,
		@JsonProperty("requestState") @Nullable String requestState,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements InputResponseRequest { // @formatter:on

		public GetPromptRequest {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static GetPromptRequest fromJson(@JsonProperty("name") @Nullable String name,
				@JsonProperty("arguments") @Nullable Map<String, String> arguments,
				@JsonProperty("inputResponses") @Nullable Map<String, Object> inputResponses,
				@JsonProperty("requestState") @Nullable String requestState,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			warnIfMissing("GetPromptRequest", missing);
			return new GetPromptRequest(name, arguments, inputResponses, requestState, meta);
		}

		public GetPromptRequest(String name, @Nullable Map<String, String> arguments) {
			this(name, arguments, null, null, null);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private @Nullable Map<String, String> arguments;

			private @Nullable Map<String, Object> inputResponses;

			private @Nullable String requestState;

			private @Nullable Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(@Nullable Map<String, String> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder inputResponses(@Nullable Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(@Nullable String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptRequest build() {
				return new GetPromptRequest(this.name, this.arguments, this.inputResponses, this.requestState,
						this.meta);
			}

		}

	}

	/**
	 * Result of a completed {@code prompts/get}.
	 *
	 * @param description a description of the prompt
	 * @param messages the prompt messages
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptResult( // @formatter:off
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("messages") List<PromptMessage> messages,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements GetPromptOutcome { // @formatter:on

		public GetPromptResult {
			Assert.notNull(messages, "messages must not be null");
		}

		@JsonCreator
		static GetPromptResult fromJson(@JsonProperty("description") @Nullable String description,
				@JsonProperty("messages") @Nullable List<PromptMessage> messages,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			messages = orDefault(messages, List.of(), "messages", missing);
			warnIfMissing("GetPromptResult", missing);
			return new GetPromptResult(description, messages, meta);
		}

		public GetPromptResult(@Nullable String description, List<PromptMessage> messages) {
			this(description, messages, null);
		}

		public static Builder builder(List<PromptMessage> messages) {
			return new Builder(messages);
		}

		public static class Builder {

			private final List<PromptMessage> messages;

			private @Nullable String description;

			private @Nullable Map<String, Object> meta;

			private Builder(List<PromptMessage> messages) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptResult build() {
				return new GetPromptResult(this.description, this.messages, this.meta);
			}

		}

	}

	/**
	 * Result of {@code prompts/list}.
	 *
	 * @param prompts the prompts
	 * @param nextCursor the cursor of the next page, {@code null} on the last page
	 * @param ttlMs caching hint, see {@link CacheableResult}
	 * @param cacheScope caching hint, see {@link CacheableResult}
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListPromptsResult( // @formatter:off
		@JsonProperty("prompts") List<Prompt> prompts,
		@JsonProperty("nextCursor") @Nullable String nextCursor,
		@JsonProperty("ttlMs") @Nullable Long ttlMs,
		@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListPromptsResult {
			Assert.notNull(prompts, "prompts must not be null");
		}

		@JsonCreator
		static ListPromptsResult fromJson(@JsonProperty("prompts") @Nullable List<Prompt> prompts,
				@JsonProperty("nextCursor") @Nullable String nextCursor, @JsonProperty("ttlMs") @Nullable Long ttlMs,
				@JsonProperty("cacheScope") @Nullable CacheScope cacheScope,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			prompts = orDefault(prompts, List.of(), "prompts", missing);
			warnIfMissing("ListPromptsResult", missing);
			return new ListPromptsResult(prompts, nextCursor, ttlMs, cacheScope, meta);
		}

		public static Builder builder(List<Prompt> prompts) {
			return new Builder(prompts);
		}

		public static class Builder {

			private final List<Prompt> prompts;

			private @Nullable String nextCursor;

			private @Nullable Long ttlMs;

			private @Nullable CacheScope cacheScope;

			private @Nullable Map<String, Object> meta;

			private Builder(List<Prompt> prompts) {
				Assert.notNull(prompts, "prompts must not be null");
				this.prompts = prompts;
			}

			public Builder nextCursor(@Nullable String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(@Nullable Long ttlMs) {
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(@Nullable CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListPromptsResult build() {
				return new ListPromptsResult(this.prompts, this.nextCursor, this.ttlMs, this.cacheScope, this.meta);
			}

		}

	}

	// ---------------------------
	// Content
	// ---------------------------

	/**
	 * A content block, identified on the wire by its {@code type}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = TextContent.TYPE),
			@JsonSubTypes.Type(value = ImageContent.class, name = ImageContent.TYPE),
			@JsonSubTypes.Type(value = AudioContent.class, name = AudioContent.TYPE),
			@JsonSubTypes.Type(value = ResourceLink.class, name = ResourceLink.TYPE),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = EmbeddedResource.TYPE) })
	public sealed interface Content permits TextContent, ImageContent, AudioContent, ResourceLink, EmbeddedResource {

		String type();

		@Nullable Map<String, Object> meta();

	}

	/**
	 * Text content.
	 *
	 * @param text the text
	 * @param annotations optional annotations
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextContent( // @formatter:off
		@JsonProperty("text") String text,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "text";

		public TextContent {
			Assert.notNull(text, "text must not be null");
		}

		@JsonCreator
		static TextContent fromJson(@JsonProperty("text") @Nullable String text,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			text = orDefault(text, "", "text", missing);
			warnIfMissing("TextContent", missing);
			return new TextContent(text, annotations, meta);
		}

		public TextContent(String text) {
			this(text, null, null);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String text) {
			return new Builder(text);
		}

		public static class Builder {

			private final String text;

			private @Nullable Annotations annotations;

			private @Nullable Map<String, Object> meta;

			private Builder(String text) {
				Assert.notNull(text, "text must not be null");
				this.text = text;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TextContent build() {
				return new TextContent(this.text, this.annotations, this.meta);
			}

		}

	}

	/**
	 * Image content.
	 *
	 * @param data the base64-encoded image data
	 * @param mimeType the MIME type of the image
	 * @param annotations optional annotations
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ImageContent( // @formatter:off
		@JsonProperty("data") String data,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "image";

		public ImageContent {
			Assert.notNull(data, "data must not be null");
			Assert.notNull(mimeType, "mimeType must not be null");
		}

		@JsonCreator
		static ImageContent fromJson(@JsonProperty("data") @Nullable String data,
				@JsonProperty("mimeType") @Nullable String mimeType,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			data = orDefault(data, "", "data", missing);
			mimeType = orDefault(mimeType, "", "mimeType", missing);
			warnIfMissing("ImageContent", missing);
			return new ImageContent(data, mimeType, annotations, meta);
		}

		public ImageContent(String data, String mimeType) {
			this(data, mimeType, null, null);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static class Builder {

			private final String data;

			private final String mimeType;

			private @Nullable Annotations annotations;

			private @Nullable Map<String, Object> meta;

			private Builder(String data, String mimeType) {
				Assert.notNull(data, "data must not be null");
				Assert.notNull(mimeType, "mimeType must not be null");
				this.data = data;
				this.mimeType = mimeType;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ImageContent build() {
				return new ImageContent(this.data, this.mimeType, this.annotations, this.meta);
			}

		}

	}

	/**
	 * Audio content.
	 *
	 * @param data the base64-encoded audio data
	 * @param mimeType the MIME type of the audio
	 * @param annotations optional annotations
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AudioContent( // @formatter:off
		@JsonProperty("data") String data,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "audio";

		public AudioContent {
			Assert.notNull(data, "data must not be null");
			Assert.notNull(mimeType, "mimeType must not be null");
		}

		@JsonCreator
		static AudioContent fromJson(@JsonProperty("data") @Nullable String data,
				@JsonProperty("mimeType") @Nullable String mimeType,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			data = orDefault(data, "", "data", missing);
			mimeType = orDefault(mimeType, "", "mimeType", missing);
			warnIfMissing("AudioContent", missing);
			return new AudioContent(data, mimeType, annotations, meta);
		}

		public AudioContent(String data, String mimeType) {
			this(data, mimeType, null, null);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static class Builder {

			private final String data;

			private final String mimeType;

			private @Nullable Annotations annotations;

			private @Nullable Map<String, Object> meta;

			private Builder(String data, String mimeType) {
				Assert.notNull(data, "data must not be null");
				Assert.notNull(mimeType, "mimeType must not be null");
				this.data = data;
				this.mimeType = mimeType;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public AudioContent build() {
				return new AudioContent(this.data, this.mimeType, this.annotations, this.meta);
			}

		}

	}

	/**
	 * A link to a resource the client may read. Not guaranteed to appear in
	 * {@code resources/list}.
	 *
	 * @param uri the URI of the resource
	 * @param name the programmatic name
	 * @param title a human-readable name
	 * @param description a human-readable description
	 * @param mimeType the MIME type, if known
	 * @param annotations optional annotations
	 * @param size the size in bytes of the raw content, if known
	 * @param icons icons for display in a user interface
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceLink( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title,
		@JsonProperty("description") @Nullable String description,
		@JsonProperty("mimeType") @Nullable String mimeType,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("size") @Nullable Long size,
		@JsonProperty("icons") @Nullable List<Icon> icons,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "resource_link";

		public ResourceLink {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static ResourceLink fromJson(@JsonProperty("uri") @Nullable String uri,
				@JsonProperty("name") @Nullable String name, @JsonProperty("title") @Nullable String title,
				@JsonProperty("description") @Nullable String description,
				@JsonProperty("mimeType") @Nullable String mimeType,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("size") @Nullable Long size, @JsonProperty("icons") @Nullable List<Icon> icons,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			uri = orDefault(uri, "", "uri", missing);
			name = orDefault(name, "", "name", missing);
			warnIfMissing("ResourceLink", missing);
			return new ResourceLink(uri, name, title, description, mimeType, annotations, size, icons, meta);
		}

		/**
		 * A link to the given resource.
		 * @param resource the resource
		 * @return the link
		 */
		public static ResourceLink of(Resource resource) {
			return new ResourceLink(resource.uri(), resource.name(), resource.title(), resource.description(),
					resource.mimeType(), resource.annotations(), resource.size(), resource.icons(), resource.meta());
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		public static class Builder {

			private final String uri;

			private final String name;

			private @Nullable String title;

			private @Nullable String description;

			private @Nullable String mimeType;

			private @Nullable Annotations annotations;

			private @Nullable Long size;

			private @Nullable List<Icon> icons;

			private @Nullable Map<String, Object> meta;

			private Builder(String uri, String name) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uri = uri;
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public Builder description(@Nullable String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(@Nullable String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder size(@Nullable Long size) {
				this.size = size;
				return this;
			}

			public Builder icons(@Nullable List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceLink build() {
				return new ResourceLink(this.uri, this.name, this.title, this.description, this.mimeType,
						this.annotations, this.size, this.icons, this.meta);
			}

		}

	}

	/**
	 * The contents of a resource, embedded in a message.
	 *
	 * @param resource the contents of the resource
	 * @param annotations optional annotations
	 * @param meta additional metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddedResource( // @formatter:off
		@JsonProperty("resource") ResourceContents resource,
		@JsonProperty("annotations") @Nullable Annotations annotations,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "resource";

		public EmbeddedResource {
			Assert.notNull(resource, "resource must not be null");
		}

		@JsonCreator
		static EmbeddedResource fromJson(@JsonProperty("resource") @Nullable ResourceContents resource,
				@JsonProperty("annotations") @Nullable Annotations annotations,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			resource = orDefault(resource, new TextResourceContents("", null, ""), "resource", missing);
			warnIfMissing("EmbeddedResource", missing);
			return new EmbeddedResource(resource, annotations, meta);
		}

		public EmbeddedResource(ResourceContents resource) {
			this(resource, null, null);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(ResourceContents resource) {
			return new Builder(resource);
		}

		public static class Builder {

			private final ResourceContents resource;

			private @Nullable Annotations annotations;

			private @Nullable Map<String, Object> meta;

			private Builder(ResourceContents resource) {
				Assert.notNull(resource, "resource must not be null");
				this.resource = resource;
			}

			public Builder annotations(@Nullable Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public EmbeddedResource build() {
				return new EmbeddedResource(this.resource, this.annotations, this.meta);
			}

		}

	}

	// ---------------------------
	// Completion
	// ---------------------------

	/**
	 * What to complete arguments for: a prompt or a resource template.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = PromptReference.class, name = PromptReference.TYPE),
			@JsonSubTypes.Type(value = ResourceTemplateReference.class, name = ResourceTemplateReference.TYPE) })
	public sealed interface CompleteReference permits PromptReference, ResourceTemplateReference {

		String type();

	}

	/**
	 * A reference to a prompt.
	 *
	 * @param name the name of the prompt
	 * @param title a human-readable name
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptReference( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") @Nullable String title) implements CompleteReference { // @formatter:on

		public static final String TYPE = "ref/prompt";

		public PromptReference {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static PromptReference fromJson(@JsonProperty("name") @Nullable String name,
				@JsonProperty("title") @Nullable String title) {
			List<String> missing = new ArrayList<>();
			name = orDefault(name, "", "name", missing);
			warnIfMissing("PromptReference", missing);
			return new PromptReference(name, title);
		}

		public PromptReference(String name) {
			this(name, null);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private @Nullable String title;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(@Nullable String title) {
				this.title = title;
				return this;
			}

			public PromptReference build() {
				return new PromptReference(this.name, this.title);
			}

		}

	}

	/**
	 * A reference to a resource template.
	 *
	 * @param uri the URI template
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceTemplateReference( // @formatter:off
		@JsonProperty("uri") String uri) implements CompleteReference { // @formatter:on

		public static final String TYPE = "ref/resource";

		public ResourceTemplateReference {
			Assert.notNull(uri, "uri must not be null");
		}

		@JsonCreator
		static ResourceTemplateReference fromJson(@JsonProperty("uri") @Nullable String uri) {
			List<String> missing = new ArrayList<>();
			uri = orDefault(uri, "", "uri", missing);
			warnIfMissing("ResourceTemplateReference", missing);
			return new ResourceTemplateReference(uri);
		}

		@Override
		public String type() {
			return TYPE;
		}

	}

	/**
	 * Parameters of {@code completion/complete}.
	 *
	 * @param ref what to complete arguments for
	 * @param argument the argument being completed
	 * @param context previously resolved arguments
	 * @param meta the request metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteRequest( // @formatter:off
		@JsonProperty("ref") CompleteReference ref,
		@JsonProperty("argument") CompleteArgument argument,
		@JsonProperty("context") @Nullable CompleteContext context,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Request { // @formatter:on

		public CompleteRequest {
			Assert.notNull(ref, "ref must not be null");
			Assert.notNull(argument, "argument must not be null");
		}

		@JsonCreator
		static CompleteRequest fromJson(@JsonProperty("ref") @Nullable CompleteReference ref,
				@JsonProperty("argument") @Nullable CompleteArgument argument,
				@JsonProperty("context") @Nullable CompleteContext context,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			ref = orDefault(ref, new PromptReference(""), "ref", missing);
			argument = orDefault(argument, new CompleteArgument("", ""), "argument", missing);
			warnIfMissing("CompleteRequest", missing);
			return new CompleteRequest(ref, argument, context, meta);
		}

		public CompleteRequest(CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}

		/**
		 * The argument being completed.
		 *
		 * @param name the name of the argument
		 * @param value the current value of the argument
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompleteArgument( // @formatter:off
			@JsonProperty("name") String name,
			@JsonProperty("value") String value) { // @formatter:on

			public CompleteArgument {
				Assert.notNull(name, "name must not be null");
				Assert.notNull(value, "value must not be null");
			}

			@JsonCreator
			static CompleteArgument fromJson(@JsonProperty("name") @Nullable String name,
					@JsonProperty("value") @Nullable String value) {
				List<String> missing = new ArrayList<>();
				name = orDefault(name, "", "name", missing);
				value = orDefault(value, "", "value", missing);
				warnIfMissing("CompleteArgument", missing);
				return new CompleteArgument(name, value);
			}

		}

		/**
		 * Context for the completion.
		 *
		 * @param arguments previously resolved arguments, by name
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompleteContext( // @formatter:off
			@JsonProperty("arguments") @Nullable Map<String, String> arguments) { // @formatter:on
		}

		public static Builder builder(CompleteReference ref, CompleteArgument argument) {
			return new Builder(ref, argument);
		}

		public static class Builder {

			private final CompleteReference ref;

			private final CompleteArgument argument;

			private @Nullable CompleteContext context;

			private @Nullable Map<String, Object> meta;

			private Builder(CompleteReference ref, CompleteArgument argument) {
				Assert.notNull(ref, "ref must not be null");
				Assert.notNull(argument, "argument must not be null");
				this.ref = ref;
				this.argument = argument;
			}

			public Builder context(@Nullable CompleteContext context) {
				this.context = context;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CompleteRequest build() {
				return new CompleteRequest(this.ref, this.argument, this.context, this.meta);
			}

		}

	}

	/**
	 * Result of {@code completion/complete}.
	 *
	 * @param completion the completion values
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteResult( // @formatter:off
		@JsonProperty("completion") CompleteCompletion completion,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) implements Result { // @formatter:on

		public CompleteResult {
			Assert.notNull(completion, "completion must not be null");
		}

		@JsonCreator
		static CompleteResult fromJson(@JsonProperty("completion") @Nullable CompleteCompletion completion,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			completion = orDefault(completion, new CompleteCompletion(List.of(), null, null), "completion", missing);
			warnIfMissing("CompleteResult", missing);
			return new CompleteResult(completion, meta);
		}

		public CompleteResult(CompleteCompletion completion) {
			this(completion, null);
		}

		/**
		 * Completion values.
		 *
		 * @param values the values, at most 100
		 * @param total the total number of available values, if known
		 * @param hasMore whether more values exist beyond {@code values}
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompleteCompletion( // @formatter:off
			@JsonProperty("values") List<String> values,
			@JsonProperty("total") @Nullable Integer total,
			@JsonProperty("hasMore") @Nullable Boolean hasMore) { // @formatter:on

			public CompleteCompletion {
				Assert.notNull(values, "values must not be null");
			}

			@JsonCreator
			static CompleteCompletion fromJson(@JsonProperty("values") @Nullable List<String> values,
					@JsonProperty("total") @Nullable Integer total,
					@JsonProperty("hasMore") @Nullable Boolean hasMore) {
				List<String> missing = new ArrayList<>();
				values = orDefault(values, List.of(), "values", missing);
				warnIfMissing("CompleteCompletion", missing);
				return new CompleteCompletion(values, total, hasMore);
			}

			public static Builder builder(List<String> values) {
				return new Builder(values);
			}

			public static class Builder {

				private final List<String> values;

				private @Nullable Integer total;

				private @Nullable Boolean hasMore;

				private Builder(List<String> values) {
					Assert.notNull(values, "values must not be null");
					this.values = values;
				}

				public Builder total(@Nullable Integer total) {
					this.total = total;
					return this;
				}

				public Builder hasMore(@Nullable Boolean hasMore) {
					this.hasMore = hasMore;
					return this;
				}

				public CompleteCompletion build() {
					return new CompleteCompletion(this.values, this.total, this.hasMore);
				}

			}

		}

	}

	// ---------------------------
	// Multi round-trip requests (MRTR)
	// ---------------------------

	/**
	 * A request the server needs the client to fulfill before it retries the original
	 * request, identified on the wire by its {@code method}.
	 * <p>
	 * Only elicitation is supported so far. Sampling ({@code sampling/createMessage}) and
	 * roots ({@code roots/list}) are deprecated in this revision, see the open questions
	 * in design.md.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "method")
	@JsonSubTypes({ @JsonSubTypes.Type(value = ElicitRequest.class, name = METHOD_ELICITATION_CREATE) })
	public sealed interface InputRequest permits ElicitRequest {

		String method();

	}

	/**
	 * Result of a request that needs input from the client, or that must be retried
	 * later. The client retries the original request with the responses to
	 * {@code inputRequests} and the {@code requestState}, see
	 * {@link InputResponseRequest}.
	 *
	 * @param inputRequests the requests the client must fulfill, by key
	 * @param requestState opaque state the client echoes back when retrying
	 * @param meta the result metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InputRequiredResult( // @formatter:off
		@JsonProperty("inputRequests") @Nullable Map<String, InputRequest> inputRequests,
		@JsonProperty("requestState") @Nullable String requestState,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta)
			implements CallToolOutcome, GetPromptOutcome, ReadResourceOutcome { // @formatter:on

		public InputRequiredResult {
			Assert.isTrue(inputRequests != null || requestState != null,
					"at least one of inputRequests or requestState must not be null");
		}

		public InputRequiredResult(@Nullable Map<String, InputRequest> inputRequests, @Nullable String requestState) {
			this(inputRequests, requestState, null);
		}

		@Override
		public String resultType() {
			return RESULT_TYPE_INPUT_REQUIRED;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private @Nullable Map<String, InputRequest> inputRequests;

			private @Nullable String requestState;

			private @Nullable Map<String, Object> meta;

			public Builder inputRequests(Map<String, InputRequest> inputRequests) {
				Assert.notNull(inputRequests, "inputRequests must not be null");
				if (this.inputRequests == null) {
					this.inputRequests = new LinkedHashMap<>();
				}
				this.inputRequests.putAll(inputRequests);
				return this;
			}

			public Builder addInputRequest(String key, InputRequest inputRequest) {
				Assert.hasText(key, "key must not be empty");
				Assert.notNull(inputRequest, "inputRequest must not be null");
				return inputRequests(Map.of(key, inputRequest));
			}

			public Builder requestState(@Nullable String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public InputRequiredResult build() {
				return new InputRequiredResult(this.inputRequests != null ? Map.copyOf(this.inputRequests) : null,
						this.requestState, this.meta);
			}

		}

	}

	/**
	 * A request to elicit information from the user.
	 *
	 * @param params what to elicit, in form or URL mode
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitRequest( // @formatter:off
		@JsonProperty("params") ElicitRequestParams params) implements InputRequest { // @formatter:on

		public ElicitRequest {
			Assert.notNull(params, "params must not be null");
		}

		@JsonCreator
		static ElicitRequest fromJson(@JsonProperty("params") @Nullable ElicitRequestParams params) {
			List<String> missing = new ArrayList<>();
			params = orDefault(params, new ElicitFormParams("", Map.of("type", "object", "properties", Map.of())),
					"params", missing);
			warnIfMissing("ElicitRequest", missing);
			return new ElicitRequest(params);
		}

		/**
		 * A form mode elicitation.
		 * @param message the message to show the user
		 * @param requestedSchema the schema of the form, a flat object of primitive
		 * properties
		 * @return the request
		 */
		public static ElicitRequest form(String message, Map<String, Object> requestedSchema) {
			return new ElicitRequest(new ElicitFormParams(message, requestedSchema));
		}

		/**
		 * A URL mode elicitation.
		 * @param message the message to show the user
		 * @param url the URL the user should navigate to
		 * @return the request
		 */
		public static ElicitRequest url(String message, String url) {
			return new ElicitRequest(new ElicitUrlParams(message, url));
		}

		@Override
		public String method() {
			return METHOD_ELICITATION_CREATE;
		}

	}

	/**
	 * Parameters of an elicitation, identified on the wire by its {@code mode}. An absent
	 * {@code mode} means form mode.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode",
			defaultImpl = ElicitFormParams.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = ElicitFormParams.class, name = ElicitFormParams.MODE),
			@JsonSubTypes.Type(value = ElicitUrlParams.class, name = ElicitUrlParams.MODE) })
	public sealed interface ElicitRequestParams permits ElicitFormParams, ElicitUrlParams {

		String mode();

		String message();

	}

	/**
	 * Form mode elicitation, for non-sensitive information.
	 *
	 * @param message the message to show the user
	 * @param requestedSchema the schema of the form, a flat object of primitive
	 * properties
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitFormParams( // @formatter:off
		@JsonProperty("message") String message,
		@JsonProperty("requestedSchema") Map<String, Object> requestedSchema) implements ElicitRequestParams { // @formatter:on

		public static final String MODE = "form";

		public ElicitFormParams {
			Assert.notNull(message, "message must not be null");
			Assert.notNull(requestedSchema, "requestedSchema must not be null");
		}

		@JsonCreator
		static ElicitFormParams fromJson(@JsonProperty("message") @Nullable String message,
				@JsonProperty("requestedSchema") @Nullable Map<String, Object> requestedSchema) {
			List<String> missing = new ArrayList<>();
			message = orDefault(message, "", "message", missing);
			requestedSchema = orDefault(requestedSchema, Map.of("type", "object", "properties", Map.of()),
					"requestedSchema", missing);
			warnIfMissing("ElicitFormParams", missing);
			return new ElicitFormParams(message, requestedSchema);
		}

		@Override
		public String mode() {
			return MODE;
		}

	}

	/**
	 * URL mode elicitation, for interactions that must not go through the client, e.g.
	 * entering credentials.
	 *
	 * @param message the message explaining why the interaction is needed
	 * @param url the URL the user should navigate to
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitUrlParams( // @formatter:off
		@JsonProperty("message") String message,
		@JsonProperty("url") String url) implements ElicitRequestParams { // @formatter:on

		public static final String MODE = "url";

		public ElicitUrlParams {
			Assert.notNull(message, "message must not be null");
			Assert.notNull(url, "url must not be null");
		}

		@JsonCreator
		static ElicitUrlParams fromJson(@JsonProperty("message") @Nullable String message,
				@JsonProperty("url") @Nullable String url) {
			List<String> missing = new ArrayList<>();
			message = orDefault(message, "", "message", missing);
			url = orDefault(url, "", "url", missing);
			warnIfMissing("ElicitUrlParams", missing);
			return new ElicitUrlParams(message, url);
		}

		@Override
		public String mode() {
			return MODE;
		}

	}

	/**
	 * The client's response to an {@link ElicitRequest}.
	 *
	 * @param action what the user did
	 * @param content the submitted form data, present when the user accepted a form
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitResult( // @formatter:off
		@JsonProperty("action") Action action,
		@JsonProperty("content") @Nullable Map<String, Object> content) { // @formatter:on

		public ElicitResult {
			Assert.notNull(action, "action must not be null");
		}

		@JsonCreator
		static ElicitResult fromJson(@JsonProperty("action") @Nullable Action action,
				@JsonProperty("content") @Nullable Map<String, Object> content) {
			List<String> missing = new ArrayList<>();
			action = orDefault(action, Action.CANCEL, "action", missing);
			warnIfMissing("ElicitResult", missing);
			return new ElicitResult(action, content);
		}

		/**
		 * What the user did with an elicitation.
		 */
		public enum Action {

			/**
			 * The user submitted the form or confirmed the action.
			 */
			ACCEPT("accept"),

			/**
			 * The user explicitly declined.
			 */
			DECLINE("decline"),

			/**
			 * The user dismissed the request without an explicit choice.
			 */
			CANCEL("cancel");

			private final String value;

			Action(String value) {
				this.value = value;
			}

			@JsonValue
			public String value() {
				return this.value;
			}

		}

		public static Builder builder(Action action) {
			return new Builder(action);
		}

		public static class Builder {

			private final Action action;

			private @Nullable Map<String, Object> content;

			private Builder(Action action) {
				Assert.notNull(action, "action must not be null");
				this.action = action;
			}

			public Builder content(@Nullable Map<String, Object> content) {
				this.content = content;
				return this;
			}

			public ElicitResult build() {
				return new ElicitResult(this.action, this.content);
			}

		}

	}

	// ---------------------------
	// Notifications
	// ---------------------------

	/**
	 * Parameters of {@code notifications/cancelled}.
	 *
	 * @param requestId the id of the request to cancel
	 * @param reason why the request is cancelled
	 * @param meta the notification metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CancelledNotification( // @formatter:off
		@JsonProperty("requestId") RequestId requestId,
		@JsonProperty("reason") @Nullable String reason,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		public CancelledNotification {
			Assert.notNull(requestId, "requestId must not be null");
		}

		@JsonCreator
		static CancelledNotification fromJson(@JsonProperty("requestId") @Nullable RequestId requestId,
				@JsonProperty("reason") @Nullable String reason,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			requestId = orDefault(requestId, RequestId.of(""), "requestId", missing);
			warnIfMissing("CancelledNotification", missing);
			return new CancelledNotification(requestId, reason, meta);
		}

		public static Builder builder(RequestId requestId) {
			return new Builder(requestId);
		}

		public static class Builder {

			private final RequestId requestId;

			private @Nullable String reason;

			private @Nullable Map<String, Object> meta;

			private Builder(RequestId requestId) {
				Assert.notNull(requestId, "requestId must not be null");
				this.requestId = requestId;
			}

			public Builder reason(@Nullable String reason) {
				this.reason = reason;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CancelledNotification build() {
				return new CancelledNotification(this.requestId, this.reason, this.meta);
			}

		}

	}

	/**
	 * Parameters of {@code notifications/progress}.
	 *
	 * @param progressToken the token from the request's {@code _meta.progressToken}, a
	 * string or an integer. Integers are held as a {@link Long}.
	 * @param progress the progress so far, increasing with each notification
	 * @param total the total, if known
	 * @param message a human-readable progress message
	 * @param meta the notification metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ProgressNotification( // @formatter:off
		@JsonProperty("progressToken") Object progressToken,
		@JsonProperty("progress") Double progress,
		@JsonProperty("total") @Nullable Double total,
		@JsonProperty("message") @Nullable String message,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		public ProgressNotification {
			progressToken = stringOrLong(progressToken, "progressToken");
			Assert.notNull(progress, "progress must not be null");
		}

		@JsonCreator
		static ProgressNotification fromJson(@JsonProperty("progressToken") @Nullable Object progressToken,
				@JsonProperty("progress") @Nullable Double progress, @JsonProperty("total") @Nullable Double total,
				@JsonProperty("message") @Nullable String message,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			progressToken = orDefault(progressToken, "", "progressToken", missing);
			progress = orDefault(progress, 0.0, "progress", missing);
			warnIfMissing("ProgressNotification", missing);
			return new ProgressNotification(progressToken, progress, total, message, meta);
		}

		public static Builder builder(Object progressToken, Double progress) {
			return new Builder(progressToken, progress);
		}

		public static class Builder {

			private final Object progressToken;

			private final Double progress;

			private @Nullable Double total;

			private @Nullable String message;

			private @Nullable Map<String, Object> meta;

			private Builder(Object progressToken, Double progress) {
				Assert.notNull(progress, "progress must not be null");
				this.progressToken = stringOrLong(progressToken, "progressToken");
				this.progress = progress;
			}

			public Builder total(@Nullable Double total) {
				this.total = total;
				return this;
			}

			public Builder message(@Nullable String message) {
				this.message = message;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ProgressNotification build() {
				return new ProgressNotification(this.progressToken, this.progress, this.total, this.message, this.meta);
			}

		}

	}

	/**
	 * Parameters of {@code notifications/message}, a log message.
	 *
	 * @param level the severity
	 * @param logger the name of the logger
	 * @param data the data to log, any JSON value
	 * @param meta the notification metadata
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LoggingMessageNotification( // @formatter:off
		@JsonProperty("level") LoggingLevel level,
		@JsonProperty("logger") @Nullable String logger,
		@JsonProperty("data") Object data,
		@JsonProperty("_meta") @Nullable Map<String, Object> meta) { // @formatter:on

		public LoggingMessageNotification {
			Assert.notNull(level, "level must not be null");
			Assert.notNull(data, "data must not be null");
		}

		@JsonCreator
		static LoggingMessageNotification fromJson(@JsonProperty("level") @Nullable LoggingLevel level,
				@JsonProperty("logger") @Nullable String logger, @JsonProperty("data") @Nullable Object data,
				@JsonProperty("_meta") @Nullable Map<String, Object> meta) {
			List<String> missing = new ArrayList<>();
			level = orDefault(level, LoggingLevel.INFO, "level", missing);
			data = orDefault(data, "", "data", missing);
			warnIfMissing("LoggingMessageNotification", missing);
			return new LoggingMessageNotification(level, logger, data, meta);
		}

		public static Builder builder(LoggingLevel level, Object data) {
			return new Builder(level, data);
		}

		public static class Builder {

			private final LoggingLevel level;

			private final Object data;

			private @Nullable String logger;

			private @Nullable Map<String, Object> meta;

			private Builder(LoggingLevel level, Object data) {
				Assert.notNull(level, "level must not be null");
				Assert.notNull(data, "data must not be null");
				this.level = level;
				this.data = data;
			}

			public Builder logger(@Nullable String logger) {
				this.logger = logger;
				return this;
			}

			public Builder meta(@Nullable Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public LoggingMessageNotification build() {
				return new LoggingMessageNotification(this.level, this.logger, this.data, this.meta);
			}

		}

	}

}
