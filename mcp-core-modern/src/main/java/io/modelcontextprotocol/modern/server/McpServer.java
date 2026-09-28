/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.common.McpTransportContext;
import io.modelcontextprotocol.modern.server.McpServerFeatures.CompletionSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.PromptSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ResourceSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ResourceTemplateSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.StreamingPromptSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.StreamingResourceSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.StreamingResourceTemplateSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.StreamingToolSpecification;
import io.modelcontextprotocol.modern.server.McpServerFeatures.ToolSpecification;
import io.modelcontextprotocol.modern.server.StreamingHandler.StreamingPrompt;
import io.modelcontextprotocol.modern.server.StreamingHandler.StreamingResource;
import io.modelcontextprotocol.modern.server.StreamingHandler.StreamingTool;
import io.modelcontextprotocol.modern.spec.McpError;
import io.modelcontextprotocol.modern.spec.McpSchema;
import io.modelcontextprotocol.modern.spec.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.spec.McpSchema.CacheableResult;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.spec.McpSchema.ClientCapabilities.Elicitation;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteReference;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.spec.McpSchema.DiscoverResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ElicitUrlParams;
import io.modelcontextprotocol.modern.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.Implementation;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCError;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCErrorResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.JSONRPCResultResponse;
import io.modelcontextprotocol.modern.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.modern.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.modern.spec.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.spec.McpSchema.MissingRequiredClientCapabilityData;
import io.modelcontextprotocol.modern.spec.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.modern.spec.McpSchema.Prompt;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.RequestId;
import io.modelcontextprotocol.modern.spec.McpSchema.Resource;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.spec.McpSchema.Result;
import io.modelcontextprotocol.modern.spec.McpSchema.TextContent;
import io.modelcontextprotocol.modern.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import io.modelcontextprotocol.modern.spec.McpSchema.UnsupportedProtocolVersionData;
import io.modelcontextprotocol.modern.util.Assert;
import io.modelcontextprotocol.modern.util.DefaultMcpUriTemplateManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An MCP server for protocol revision {@value McpSchema#LATEST_PROTOCOL_VERSION}.
 * <p>
 * The server is stateless and transport-agnostic: a transport hands each incoming
 * JSON-RPC request to {@link #handleRequest(JSONRPCRequest, Consumer)}, which blocks
 * until the handler completes and returns the response. Notifications the handler emits
 * in the meantime, such as progress, are handed to the transport for the response stream
 * of the request. It is safe to call from many threads, and well suited to one virtual
 * thread per request.
 * <p>
 * Cancellation is the transport's job, since the cancellation signal depends on the
 * transport: {@code notifications/cancelled} on stdio, a closed response stream on
 * Streamable HTTP. The transport cancels a request by interrupting the thread that calls
 * {@code handleRequest}, and then sends neither the response nor any further notification
 * for it. Handlers see the interrupt like any blocking code does, e.g. as an
 * {@link InterruptedException}. A cancelled request returns an
 * {@link ErrorCodes#INTERNAL_ERROR} response, with the interrupt status of the thread
 * set.
 * <p>
 * For every request, the server:
 * <ul>
 * <li>validates the per-request {@code _meta}: protocol version and client
 * capabilities;</li>
 * <li>dispatches to the handler registered for the method;</li>
 * <li>replaces a tool result whose {@code structuredContent} does not conform to the
 * {@code outputSchema} of the tool with a tool error, and adds a text content block
 * holding the {@code structuredContent} of a tool result that has no content;</li>
 * <li>rejects an {@link InputRequiredResult} whose input requests the client did not
 * declare support for, with {@link ErrorCodes#MISSING_REQUIRED_CLIENT_CAPABILITY};</li>
 * <li>stamps {@code resultType}, {@code _meta.io.modelcontextprotocol/serverInfo} and,
 * for cacheable results, default caching hints onto the result.</li>
 * </ul>
 *
 * <pre>{@code
 * McpServer server = McpServer.builder()
 *     .serverInfo("weather", "1.0.0")
 *     .tool(Tool.builder("get_forecast").description("Get the forecast").build(),
 *             (ctx, request) -> CallToolResult.builder().addTextContent("Sunny").build())
 *     .build();
 * }</pre>
 * <p>
 * Tools are checked when registered: names must be 1 to 128 characters among {@code A-Z},
 * {@code a-z}, {@code 0-9}, {@code _}, {@code -} and {@code .}, and {@code inputSchema}
 * and {@code outputSchema} must be valid JSON Schemas, with an {@code "object"} input
 * schema.
 */
public final class McpServer {

	private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

	/**
	 * The protocol versions this server supports.
	 */
	public static final List<String> SUPPORTED_VERSIONS = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

	private static final Implementation DEFAULT_SERVER_INFO = Implementation.builder("mcp-server", "1.0.0").build();

	private static final TypeRef<Map<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

	private final Implementation serverInfo;

	private final Map<String, Object> serverInfoWire;

	private final ServerCapabilities capabilities;

	private final @Nullable String instructions;

	private final Map<String, StreamingToolSpecification> tools;

	private final List<Tool> toolList;

	private final Map<String, StreamingResourceSpecification> resources;

	private final List<Resource> resourceList;

	private final List<StreamingResourceTemplateSpecification> resourceTemplates;

	private final List<DefaultMcpUriTemplateManager> resourceTemplateMatchers;

	private final Map<String, StreamingPromptSpecification> prompts;

	private final List<Prompt> promptList;

	private final Map<CompleteReference, CompletionSpecification> completions;

	private final long ttlMs;

	private final CacheScope cacheScope;

	private final McpJsonMapper jsonMapper;

	private final JsonSchemaValidator jsonSchemaValidator;

	private McpServer(Builder builder) {
		this.serverInfo = builder.serverInfo;
		this.capabilities = builder.capabilities != null ? builder.capabilities : builder.deriveCapabilities();
		this.instructions = builder.instructions;
		this.tools = Map.copyOf(builder.tools);
		this.resources = Map.copyOf(builder.resources);
		this.resourceTemplates = List.copyOf(builder.resourceTemplates);
		this.resourceTemplateMatchers = this.resourceTemplates.stream()
			.map(spec -> new DefaultMcpUriTemplateManager(spec.resourceTemplate().uriTemplate()))
			.toList();
		this.prompts = Map.copyOf(builder.prompts);
		this.completions = Map.copyOf(builder.completions);
		this.ttlMs = builder.ttl.toMillis();
		this.cacheScope = builder.cacheScope;
		this.jsonMapper = builder.jsonMapper != null ? builder.jsonMapper : McpJsonDefaults.getMapper();
		this.jsonSchemaValidator = builder.jsonSchemaValidator != null ? builder.jsonSchemaValidator
				: McpJsonDefaults.getSchemaValidator();
		this.tools.values().forEach(spec -> assertValidSchemas(spec.tool()));
		this.serverInfoWire = this.jsonMapper.convertValue(this.serverInfo, MAP_TYPE_REF);
		this.toolList = builder.tools.values().stream().map(StreamingToolSpecification::tool).toList();
		this.resourceList = builder.resources.values().stream().map(StreamingResourceSpecification::resource).toList();
		this.promptList = builder.prompts.values().stream().map(StreamingPromptSpecification::prompt).toList();
	}

	public static Builder builder() {
		return new Builder();
	}

	public Implementation getServerInfo() {
		return this.serverInfo;
	}

	public ServerCapabilities getCapabilities() {
		return this.capabilities;
	}

	/**
	 * Handles a request and returns its response, dropping the notifications emitted
	 * while handling it. Never throws: failures are returned as JSON-RPC errors.
	 * @param request the request
	 * @return the response, a result or an error
	 */
	public JSONRPCResponse handleRequest(JSONRPCRequest request) {
		return handleRequest(request, notification -> {
		});
	}

	/**
	 * Handles a request and returns its response. Never throws: failures are returned as
	 * JSON-RPC errors.
	 * @param request the request
	 * @param notifications receives the notifications emitted while handling the request,
	 * such as {@code notifications/progress}, to send before the response. Called on the
	 * thread of the handler that emits them, which may not be the calling thread.
	 * @return the response, a result or an error
	 */
	public JSONRPCResponse handleRequest(JSONRPCRequest request, Consumer<JSONRPCNotification> notifications) {
		return handleRequest(McpTransportContext.EMPTY, request, notifications);
	}

	/**
	 * Handles a request and returns its response. Never throws: failures are returned as
	 * JSON-RPC errors.
	 * @param transportContext the transport-level metadata of the request, exposed to
	 * handlers as {@link McpRequestContext#transportContext()}
	 * @param request the request
	 * @param notifications receives the notifications emitted while handling the request,
	 * such as {@code notifications/progress}, to send before the response. Called on the
	 * thread of the handler that emits them, which may not be the calling thread.
	 * @return the response, a result or an error
	 */
	public JSONRPCResponse handleRequest(McpTransportContext transportContext, JSONRPCRequest request,
			Consumer<JSONRPCNotification> notifications) {
		Assert.notNull(transportContext, "transportContext must not be null");
		Assert.notNull(request, "request must not be null");
		Assert.notNull(notifications, "notifications must not be null");
		try {
			Map<String, Object> params = paramsAsMap(request.params());
			McpRequestContext context = createContext(transportContext, request.id(), params);
			// TODO: should validate context when coming from HTTP request
			Result result = dispatch(request.method(), context, params, notifications);
			if (result == null) {
				throw new IllegalStateException("Handler for " + request.method() + " returned null");
			}
			if (result instanceof InputRequiredResult inputRequired) {
				assertInputRequestsSupported(inputRequired, context.clientCapabilities());
			}
			return new JSONRPCResultResponse(request.id(), toWire(result));
		}
		catch (McpError error) {
			return new JSONRPCErrorResponse(request.id(), error.toJsonRpcError());
		}
		catch (Exception ex) {
			if (isCancellation(ex)) {
				logger.debug("Request {} ({}) was cancelled", request.id(), request.method(), ex);
				return new JSONRPCErrorResponse(request.id(),
						new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "Request cancelled"));
			}
			logger.error("Failed to handle request {} ({})", request.id(), request.method(), ex);
			return new JSONRPCErrorResponse(request.id(),
					new JSONRPCError(ErrorCodes.INTERNAL_ERROR, "Internal error"));
		}
	}

	/**
	 * A request is cancelled when the transport interrupts the thread handling it. A
	 * handler may have caught the {@link InterruptedException} and wrapped it without
	 * restoring the interrupt status: it is then restored, for the transport.
	 * {@link java.io.InterruptedIOException} is not a cancellation: it includes
	 * {@link java.net.SocketTimeoutException}.
	 */
	private static boolean isCancellation(Exception ex) {
		if (Thread.currentThread().isInterrupted()) {
			return true;
		}
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return true;
			}
		}
		return false;
	}

	private Result dispatch(String method, McpRequestContext context, Map<String, Object> params,
			Consumer<JSONRPCNotification> notifications) {
		return switch (method) {
			case McpSchema.METHOD_SERVER_DISCOVER -> new DiscoverResult(SUPPORTED_VERSIONS, this.capabilities,
					this.instructions, this.ttlMs, this.cacheScope, null);
			case McpSchema.METHOD_TOOLS_LIST -> {
				requireCapability(method, this.capabilities.tools());
				assertFirstPage(convertParams(params, PaginatedRequest.class));
				yield new ListToolsResult(this.toolList, null, this.ttlMs, this.cacheScope, null);
			}
			case McpSchema.METHOD_TOOLS_CALL -> {
				requireCapability(method, this.capabilities.tools());
				yield callTool(context, convertParams(params, CallToolRequest.class), notifications);
			}
			case McpSchema.METHOD_RESOURCES_LIST -> {
				requireCapability(method, this.capabilities.resources());
				assertFirstPage(convertParams(params, PaginatedRequest.class));
				yield new ListResourcesResult(this.resourceList, null, this.ttlMs, this.cacheScope, null);
			}
			case McpSchema.METHOD_RESOURCES_TEMPLATES_LIST -> {
				requireCapability(method, this.capabilities.resources());
				assertFirstPage(convertParams(params, PaginatedRequest.class));
				List<ResourceTemplate> templates = this.resourceTemplates.stream()
					.map(StreamingResourceTemplateSpecification::resourceTemplate)
					.toList();
				yield new ListResourceTemplatesResult(templates, null, this.ttlMs, this.cacheScope, null);
			}
			case McpSchema.METHOD_RESOURCES_READ -> {
				requireCapability(method, this.capabilities.resources());
				yield readResource(context, convertParams(params, ReadResourceRequest.class), notifications);
			}
			case McpSchema.METHOD_PROMPTS_LIST -> {
				requireCapability(method, this.capabilities.prompts());
				assertFirstPage(convertParams(params, PaginatedRequest.class));
				yield new ListPromptsResult(this.promptList, null, this.ttlMs, this.cacheScope, null);
			}
			case McpSchema.METHOD_PROMPTS_GET -> {
				requireCapability(method, this.capabilities.prompts());
				yield getPrompt(context, convertParams(params, GetPromptRequest.class), notifications);
			}
			case McpSchema.METHOD_COMPLETION_COMPLETE -> {
				requireCapability(method, this.capabilities.completions());
				yield complete(context, convertParams(params, CompleteRequest.class));
			}
			default -> throw new McpError(ErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method);
		};
	}

	private CallToolOutcome callTool(McpRequestContext context, CallToolRequest request,
			Consumer<JSONRPCNotification> notifications) {
		StreamingToolSpecification spec = this.tools.get(request.name());
		if (spec == null) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Unknown tool: " + request.name());
		}
		CallToolOutcome outcome = stream(spec.streamingHandler(), context, request, notifications);
		if (!(outcome instanceof CallToolResult result)) {
			return outcome;
		}
		Map<String, Object> outputSchema = spec.tool().outputSchema();
		if (outputSchema != null) {
			result = checkStructuredContent(request.name(), outputSchema, result);
		}
		return withTextContent(result);
	}

	/**
	 * For backwards compatibility, a tool that returns structured content should also
	 * return it serialized in a text content block. Added when the tool returned no
	 * content.
	 */
	private CallToolResult withTextContent(CallToolResult result) {
		if (result.structuredContent() == null || !result.content().isEmpty()) {
			return result;
		}
		return new CallToolResult(List.of(new TextContent(toJson(result.structuredContent()))),
				result.structuredContent(), result.isError(), result.meta());
	}

	/**
	 * Servers must return structured content that conforms to the output schema of the
	 * tool. A result that does not is replaced by a tool error, so the model sees what
	 * went wrong. Tool errors are returned as is.
	 */
	private CallToolResult checkStructuredContent(String toolName, Map<String, Object> outputSchema,
			CallToolResult result) {
		if (Boolean.TRUE.equals(result.isError())) {
			return result;
		}
		String error;
		if (result.structuredContent() == null) {
			error = "Tool '" + toolName + "' has an outputSchema but returned no structuredContent";
		}
		else {
			// Serialized, so that a String is validated as a JSON string, not parsed
			ValidationResponse validation = this.jsonSchemaValidator.validate(outputSchema,
					toJson(result.structuredContent()));
			if (validation.valid()) {
				return result;
			}
			error = "Tool '" + toolName + "' returned structuredContent that does not conform to its outputSchema: "
					+ validation.errorMessage();
		}
		logger.warn(error);
		return CallToolResult.builder().addTextContent(error).isError(true).build();
	}

	private void assertValidSchemas(Tool tool) {
		String context = "Tool '" + tool.name() + "' ";
		Assert.isTrue("object".equals(tool.inputSchema().get("type")),
				context + "inputSchema must have \"type\": \"object\"");
		this.jsonSchemaValidator.assertConforms(context + "inputSchema", tool.inputSchema());
		this.jsonSchemaValidator.assertConforms(context + "outputSchema", tool.outputSchema());
	}

	private String toJson(Object value) {
		try {
			return this.jsonMapper.writeValueAsString(value);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to serialize " + value.getClass().getName(), ex);
		}
	}

	private ReadResourceOutcome readResource(McpRequestContext context, ReadResourceRequest request,
			Consumer<JSONRPCNotification> notifications) {
		StreamingResourceSpecification spec = this.resources.get(request.uri());
		if (spec != null) {
			return stream(spec.streamingHandler(), context, request, notifications);
		}
		for (int i = 0; i < this.resourceTemplates.size(); i++) {
			if (this.resourceTemplateMatchers.get(i).matches(request.uri())) {
				return stream(this.resourceTemplates.get(i).streamingHandler(), context, request, notifications);
			}
		}
		throw new McpError(ErrorCodes.INVALID_PARAMS, "Resource not found", Map.of("uri", request.uri()));
	}

	private GetPromptOutcome getPrompt(McpRequestContext context, GetPromptRequest request,
			Consumer<JSONRPCNotification> notifications) {
		StreamingPromptSpecification spec = this.prompts.get(request.name());
		if (spec == null) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Unknown prompt: " + request.name());
		}
		assertRequiredArguments(spec.prompt(), request);
		return stream(spec.streamingHandler(), context, request, notifications);
	}

	/**
	 * Servers should reject a {@code prompts/get} missing required arguments with
	 * {@link ErrorCodes#INVALID_PARAMS}, before calling the handler. An argument with a
	 * {@code null} value is missing.
	 */
	private static void assertRequiredArguments(Prompt prompt, GetPromptRequest request) {
		List<PromptArgument> declared = prompt.arguments();
		if (declared == null) {
			return;
		}
		Map<String, String> requested = request.arguments();
		Map<String, String> arguments = requested != null ? requested : Map.of();
		List<String> missing = declared.stream()
			.filter(argument -> Boolean.TRUE.equals(argument.required()))
			.map(PromptArgument::name)
			.filter(name -> arguments.get(name) == null)
			.toList();
		if (!missing.isEmpty()) {
			throw new McpError(ErrorCodes.INVALID_PARAMS,
					"Missing required arguments for prompt '" + prompt.name() + "': " + String.join(", ", missing));
		}
	}

	/**
	 * Runs a streaming handler and returns the response it completed with. The sink is
	 * closed once the handler returns.
	 */
	private <Q, R> R stream(StreamingHandler<Q, R> handler, McpRequestContext context, Q request,
			Consumer<JSONRPCNotification> notifications) {
		RequestSink<R> sink = new RequestSink<>(context.progressToken(), notifications);
		try {
			handler.handle(context, request, sink);
		}
		finally {
			sink.close();
		}
		return sink.response();
	}

	private CompleteResult complete(McpRequestContext context, CompleteRequest request) {
		CompletionSpecification spec = this.completions.get(completionKey(request.ref()));
		if (spec == null) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "No completion for " + request.ref());
		}
		return spec.completionHandler().apply(context, request);
	}

	/**
	 * Completions are looked up by prompt name or template URI only: a
	 * {@link PromptReference} may carry a {@code title} that must not affect the match.
	 */
	private static CompleteReference completionKey(CompleteReference reference) {
		if (reference instanceof PromptReference prompt) {
			return new PromptReference(prompt.name());
		}
		return reference;
	}

	/**
	 * Servers must not send input requests the client has not declared support for: the
	 * request is rejected instead, listing the missing capabilities. An
	 * {@code elicitation} capability declaring no mode means form mode only.
	 */
	private static void assertInputRequestsSupported(InputRequiredResult result, ClientCapabilities capabilities) {
		if (result.inputRequests() == null) {
			return;
		}
		Elicitation elicitation = capabilities.elicitation();
		boolean formSupported = elicitation != null && (elicitation.form() != null || elicitation.url() == null);
		boolean urlSupported = elicitation != null && elicitation.url() != null;
		boolean formMissing = false;
		boolean urlMissing = false;
		for (InputRequest inputRequest : result.inputRequests().values()) {
			if (inputRequest instanceof ElicitRequest elicit) {
				if (elicit.params() instanceof ElicitUrlParams) {
					urlMissing |= !urlSupported;
				}
				else {
					formMissing |= !formSupported;
				}
			}
		}
		if (formMissing || urlMissing) {
			ClientCapabilities required = ClientCapabilities.builder().elicitation(formMissing, urlMissing).build();
			throw new McpError(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability",
					new MissingRequiredClientCapabilityData(required));
		}
	}

	private static void requireCapability(String method, @Nullable Object capability) {
		if (capability == null) {
			throw new McpError(ErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method);
		}
	}

	/**
	 * Lists are returned in a single page, so the server never issues a cursor and any
	 * cursor a client sends is invalid.
	 */
	private static void assertFirstPage(PaginatedRequest request) {
		if (request.cursor() != null) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid cursor");
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> paramsAsMap(@Nullable Object params) {
		if (params instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		throw new McpError(ErrorCodes.INVALID_PARAMS, "Missing required params with _meta");
	}

	private McpRequestContext createContext(McpTransportContext transportContext, RequestId requestId,
			Map<String, Object> params) {
		if (!(params.get("_meta") instanceof Map<?, ?> rawMeta)) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Missing required params._meta");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) rawMeta;
		if (!(meta.get(MetaKeys.PROTOCOL_VERSION) instanceof String protocolVersion)) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Missing required _meta." + MetaKeys.PROTOCOL_VERSION);
		}
		if (!SUPPORTED_VERSIONS.contains(protocolVersion)) {
			throw new McpError(ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
					new UnsupportedProtocolVersionData(SUPPORTED_VERSIONS, protocolVersion));
		}
		Object rawCapabilities = meta.get(MetaKeys.CLIENT_CAPABILITIES);
		if (!(rawCapabilities instanceof Map<?, ?>)) {
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Missing required _meta." + MetaKeys.CLIENT_CAPABILITIES);
		}
		ClientCapabilities clientCapabilities = convertMetaValue(rawCapabilities, MetaKeys.CLIENT_CAPABILITIES,
				ClientCapabilities.class);
		Implementation clientInfo = convertOptionalMeta(meta, MetaKeys.CLIENT_INFO, Implementation.class);
		LoggingLevel logLevel = convertOptionalMeta(meta, MetaKeys.LOG_LEVEL, LoggingLevel.class);
		Object progressToken = progressToken(meta);
		return new DefaultMcpRequestContext(requestId, protocolVersion, clientCapabilities, clientInfo, logLevel,
				progressToken, meta, transportContext);
	}

	/**
	 * Reads {@code _meta.progressToken}, a string or an integer, held as a {@link Long}
	 * like in {@link ProgressNotification}.
	 */
	private static @Nullable Object progressToken(Map<String, Object> meta) {
		Object progressToken = meta.get(MetaKeys.PROGRESS_TOKEN);
		if (progressToken instanceof Integer integer) {
			return integer.longValue();
		}
		if (progressToken == null || progressToken instanceof String || progressToken instanceof Long) {
			return progressToken;
		}
		throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid _meta." + MetaKeys.PROGRESS_TOKEN);
	}

	private <T> @Nullable T convertOptionalMeta(Map<String, Object> meta, String key, Class<T> type) {
		Object value = meta.get(key);
		return (value != null) ? convertMetaValue(value, key, type) : null;
	}

	private <T> T convertMetaValue(Object value, String key, Class<T> type) {
		try {
			return this.jsonMapper.convertValue(value, type);
		}
		catch (RuntimeException ex) {
			// The exception type depends on the JSON library
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid _meta." + key);
		}
	}

	private <T> T convertParams(Map<String, Object> params, Class<T> type) {
		try {
			return this.jsonMapper.convertValue(params, type);
		}
		catch (RuntimeException ex) {
			// The exception type depends on the JSON library
			throw new McpError(ErrorCodes.INVALID_PARAMS, "Invalid params");
		}
	}

	/**
	 * Converts a result to its wire form, stamping the fields the SDK manages.
	 */
	private Map<String, Object> toWire(Result result) {
		Map<String, Object> wire = new LinkedHashMap<>(this.jsonMapper.convertValue(result, MAP_TYPE_REF));
		wire.put("resultType", result.resultType());
		Map<String, Object> meta = new LinkedHashMap<>();
		if (wire.get("_meta") instanceof Map<?, ?> existing) {
			existing.forEach((key, value) -> meta.put((String) key, value));
		}
		meta.putIfAbsent(MetaKeys.SERVER_INFO, this.serverInfoWire);
		wire.put("_meta", meta);
		if (result instanceof CacheableResult) {
			wire.putIfAbsent("ttlMs", this.ttlMs);
			wire.putIfAbsent("cacheScope", this.cacheScope.value());
		}
		return wire;
	}

	/**
	 * The {@link Sink} of one request: forwards progress to the transport and holds the
	 * response until the handler returns.
	 */
	private final class RequestSink<R> implements Sink<R> {

		private final @Nullable Object progressToken;

		private final Consumer<JSONRPCNotification> notifications;

		private final AtomicReference<@Nullable R> response = new AtomicReference<>();

		private volatile boolean closed;

		/**
		 * Held while checking and sending progress, so that notifications from concurrent
		 * threads reach the transport in increasing order.
		 */
		private final Lock progressLock = new ReentrantLock();

		private @Nullable Double lastProgress;

		private RequestSink(@Nullable Object progressToken, Consumer<JSONRPCNotification> notifications) {
			this.progressToken = progressToken;
			this.notifications = notifications;
		}

		@Override
		public void progress(ProgressNotification notification) {
			Assert.notNull(notification, "notification must not be null");
			Assert.isTrue(this.progressToken != null, "The request carried no progress token");
			Assert.isTrue(notification.progressToken().equals(this.progressToken),
					"progressToken must be the one of the request: " + this.progressToken);
			if (this.closed || this.response.get() != null) {
				throw new IllegalStateException("The handler has already completed");
			}
			Map<String, Object> params = jsonMapper.convertValue(notification, MAP_TYPE_REF);
			this.progressLock.lock();
			try {
				Double last = this.lastProgress;
				// Written so that NaN is rejected
				Assert.isTrue(last == null ? !notification.progress().isNaN() : notification.progress() > last,
						"progress must increase with each notification, was " + last + ", got "
								+ notification.progress());
				this.notifications.accept(new JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_PROGRESS, params));
				this.lastProgress = notification.progress();
			}
			finally {
				this.progressLock.unlock();
			}
		}

		@Override
		public void complete(R response) {
			Assert.notNull(response, "response must not be null");
			if (this.closed || !this.response.compareAndSet(null, response)) {
				throw new IllegalStateException("The handler has already completed");
			}
		}

		private void close() {
			this.closed = true;
		}

		private R response() {
			R result = this.response.get();
			if (result == null) {
				throw new IllegalStateException("The handler returned without completing");
			}
			return result;
		}

	}

	/**
	 * Builds an {@link McpServer}.
	 */
	public static final class Builder {

		private Implementation serverInfo = DEFAULT_SERVER_INFO;

		private @Nullable ServerCapabilities capabilities;

		private @Nullable String instructions;

		private final Map<String, StreamingToolSpecification> tools = new LinkedHashMap<>();

		private final Map<String, StreamingResourceSpecification> resources = new LinkedHashMap<>();

		private final List<StreamingResourceTemplateSpecification> resourceTemplates = new ArrayList<>();

		private final Map<String, StreamingPromptSpecification> prompts = new LinkedHashMap<>();

		private final Map<CompleteReference, CompletionSpecification> completions = new LinkedHashMap<>();

		private Duration ttl = Duration.ZERO;

		private CacheScope cacheScope = CacheScope.PRIVATE;

		private @Nullable McpJsonMapper jsonMapper;

		private @Nullable JsonSchemaValidator jsonSchemaValidator;

		private Builder() {
		}

		/**
		 * Sets the server identity, returned in the {@code _meta} of every result.
		 * @param serverInfo the server identity
		 * @return this builder
		 */
		public Builder serverInfo(Implementation serverInfo) {
			Assert.notNull(serverInfo, "serverInfo must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * Sets the server identity, returned in the {@code _meta} of every result.
		 * @param name the server name
		 * @param version the server version
		 * @return this builder
		 */
		public Builder serverInfo(String name, String version) {
			return serverInfo(Implementation.builder(name, version).build());
		}

		/**
		 * Sets the instructions returned by {@code server/discover}: guidance for LLMs on
		 * how to use the server.
		 * @param instructions the instructions
		 * @return this builder
		 */
		public Builder instructions(@Nullable String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * Sets the server capabilities. When not set, they are derived from the
		 * registered features: {@code tools}, {@code resources}, {@code prompts} and
		 * {@code completions} are declared when at least one of each is registered.
		 * <p>
		 * Methods of an undeclared capability are rejected with
		 * {@link ErrorCodes#METHOD_NOT_FOUND}.
		 * @param capabilities the server capabilities
		 * @return this builder
		 */
		public Builder capabilities(ServerCapabilities capabilities) {
			Assert.notNull(capabilities, "capabilities must not be null");
			this.capabilities = capabilities;
			return this;
		}

		/**
		 * Registers a tool.
		 * @param tool the tool definition
		 * @param callHandler the {@code tools/call} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a tool with the same name is registered, or
		 * a tool name is invalid
		 */
		public Builder tool(Tool tool, BiFunction<McpRequestContext, CallToolRequest, CallToolOutcome> callHandler) {
			return tools(new ToolSpecification(tool, callHandler));
		}

		/**
		 * Registers tools.
		 * @param toolSpecifications the tools
		 * @return this builder
		 * @throws IllegalArgumentException if a tool with the same name is registered, or
		 * a tool name is invalid
		 */
		public Builder tools(ToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "toolSpecifications must not be null");
			return tools(List.of(toolSpecifications));
		}

		/**
		 * Registers tools.
		 * @param toolSpecifications the tools
		 * @return this builder
		 * @throws IllegalArgumentException if a tool with the same name is registered, or
		 * a tool name is invalid
		 */
		public Builder tools(List<ToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "toolSpecifications must not be null");
			List<StreamingToolSpecification> streamingSpecifications = new ArrayList<>();
			for (ToolSpecification spec : toolSpecifications) {
				Assert.notNull(spec, "toolSpecification must not be null");
				streamingSpecifications.add(new StreamingToolSpecification(spec.tool(),
						(ctx, request, sink) -> sink.complete(spec.callHandler().apply(ctx, request))));
			}
			return streamingTools(streamingSpecifications);
		}

		/**
		 * Registers a tool whose handler may report progress.
		 * @param tool the tool definition
		 * @param streamingHandler the {@code tools/call} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a tool with the same name is registered, or
		 * a tool name is invalid
		 */
		public Builder streamingTool(Tool tool, StreamingTool streamingHandler) {
			return streamingTools(new StreamingToolSpecification(tool, streamingHandler));
		}

		/**
		 * Registers tools whose handlers may report progress.
		 * @param toolSpecifications the tools
		 * @return this builder
		 * @throws IllegalArgumentException if a tool with the same name is registered, or
		 * a tool name is invalid
		 */
		public Builder streamingTools(StreamingToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "toolSpecifications must not be null");
			return streamingTools(List.of(toolSpecifications));
		}

		/**
		 * Registers tools whose handlers may report progress.
		 * @param toolSpecifications the tools
		 * @return this builder
		 * @throws IllegalArgumentException if a tool with the same name is registered, or
		 * a tool name is invalid
		 */
		public Builder streamingTools(List<StreamingToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "toolSpecifications must not be null");
			for (StreamingToolSpecification spec : toolSpecifications) {
				Assert.notNull(spec, "toolSpecification must not be null");
				String name = spec.tool().name();
				Assert.isTrue(TOOL_NAME.matcher(name).matches(), "Tool name '" + name
						+ "' must be 1 to 128 characters among A-Z, a-z, 0-9, underscore, hyphen and dot");
				Assert.isTrue(!this.tools.containsKey(name), "Tool '" + name + "' is already registered");
				this.tools.put(name, spec);
			}
			return this;
		}

		/**
		 * Registers a resource.
		 * @param resource the resource definition
		 * @param readHandler the {@code resources/read} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a resource with the same URI is registered
		 */
		public Builder resource(Resource resource,
				BiFunction<McpRequestContext, ReadResourceRequest, ReadResourceOutcome> readHandler) {
			return resources(new ResourceSpecification(resource, readHandler));
		}

		/**
		 * Registers resources.
		 * @param resourceSpecifications the resources
		 * @return this builder
		 * @throws IllegalArgumentException if a resource with the same URI is registered
		 */
		public Builder resources(ResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "resourceSpecifications must not be null");
			return resources(List.of(resourceSpecifications));
		}

		/**
		 * Registers resources.
		 * @param resourceSpecifications the resources
		 * @return this builder
		 * @throws IllegalArgumentException if a resource with the same URI is registered
		 */
		public Builder resources(List<ResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "resourceSpecifications must not be null");
			List<StreamingResourceSpecification> streamingSpecifications = new ArrayList<>();
			for (ResourceSpecification spec : resourceSpecifications) {
				Assert.notNull(spec, "resourceSpecification must not be null");
				streamingSpecifications.add(new StreamingResourceSpecification(spec.resource(),
						(ctx, request, sink) -> sink.complete(spec.readHandler().apply(ctx, request))));
			}
			return streamingResources(streamingSpecifications);
		}

		/**
		 * Registers a resource whose handler may report progress.
		 * @param resource the resource definition
		 * @param streamingHandler the {@code resources/read} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a resource with the same URI is registered
		 */
		public Builder streamingResource(Resource resource, StreamingResource streamingHandler) {
			return streamingResources(new StreamingResourceSpecification(resource, streamingHandler));
		}

		/**
		 * Registers resources whose handlers may report progress.
		 * @param resourceSpecifications the resources
		 * @return this builder
		 * @throws IllegalArgumentException if a resource with the same URI is registered
		 */
		public Builder streamingResources(StreamingResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "resourceSpecifications must not be null");
			return streamingResources(List.of(resourceSpecifications));
		}

		/**
		 * Registers resources whose handlers may report progress.
		 * @param resourceSpecifications the resources
		 * @return this builder
		 * @throws IllegalArgumentException if a resource with the same URI is registered
		 */
		public Builder streamingResources(List<StreamingResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "resourceSpecifications must not be null");
			for (StreamingResourceSpecification spec : resourceSpecifications) {
				Assert.notNull(spec, "resourceSpecification must not be null");
				String uri = spec.resource().uri();
				Assert.isTrue(!this.resources.containsKey(uri), "Resource '" + uri + "' is already registered");
				this.resources.put(uri, spec);
			}
			return this;
		}

		/**
		 * Registers a resource template. {@code resources/read} requests for a URI that
		 * matches no registered resource are served by the first matching template.
		 * @param resourceTemplate the resource template definition
		 * @param readHandler the {@code resources/read} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a template with the same URI template is
		 * registered
		 */
		public Builder resourceTemplate(ResourceTemplate resourceTemplate,
				BiFunction<McpRequestContext, ReadResourceRequest, ReadResourceOutcome> readHandler) {
			return resourceTemplates(new ResourceTemplateSpecification(resourceTemplate, readHandler));
		}

		/**
		 * Registers resource templates.
		 * @param resourceTemplateSpecifications the resource templates
		 * @return this builder
		 * @throws IllegalArgumentException if a template with the same URI template is
		 * registered
		 */
		public Builder resourceTemplates(ResourceTemplateSpecification... resourceTemplateSpecifications) {
			Assert.notNull(resourceTemplateSpecifications, "resourceTemplateSpecifications must not be null");
			return resourceTemplates(List.of(resourceTemplateSpecifications));
		}

		/**
		 * Registers resource templates.
		 * @param resourceTemplateSpecifications the resource templates
		 * @return this builder
		 * @throws IllegalArgumentException if a template with the same URI template is
		 * registered
		 */
		public Builder resourceTemplates(List<ResourceTemplateSpecification> resourceTemplateSpecifications) {
			Assert.notNull(resourceTemplateSpecifications, "resourceTemplateSpecifications must not be null");
			List<StreamingResourceTemplateSpecification> streamingSpecifications = new ArrayList<>();
			for (ResourceTemplateSpecification spec : resourceTemplateSpecifications) {
				Assert.notNull(spec, "resourceTemplateSpecification must not be null");
				streamingSpecifications.add(new StreamingResourceTemplateSpecification(spec.resourceTemplate(),
						(ctx, request, sink) -> sink.complete(spec.readHandler().apply(ctx, request))));
			}
			return streamingResourceTemplates(streamingSpecifications);
		}

		/**
		 * Registers a resource template whose handler may report progress.
		 * {@code resources/read} requests for a URI that matches no registered resource
		 * are served by the first matching template.
		 * @param resourceTemplate the resource template definition
		 * @param streamingHandler the {@code resources/read} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a template with the same URI template is
		 * registered
		 */
		public Builder streamingResourceTemplate(ResourceTemplate resourceTemplate,
				StreamingResource streamingHandler) {
			return streamingResourceTemplates(
					new StreamingResourceTemplateSpecification(resourceTemplate, streamingHandler));
		}

		/**
		 * Registers resource templates whose handlers may report progress.
		 * @param resourceTemplateSpecifications the resource templates
		 * @return this builder
		 * @throws IllegalArgumentException if a template with the same URI template is
		 * registered
		 */
		public Builder streamingResourceTemplates(
				StreamingResourceTemplateSpecification... resourceTemplateSpecifications) {
			Assert.notNull(resourceTemplateSpecifications, "resourceTemplateSpecifications must not be null");
			return streamingResourceTemplates(List.of(resourceTemplateSpecifications));
		}

		/**
		 * Registers resource templates whose handlers may report progress.
		 * @param resourceTemplateSpecifications the resource templates
		 * @return this builder
		 * @throws IllegalArgumentException if a template with the same URI template is
		 * registered
		 */
		public Builder streamingResourceTemplates(
				List<StreamingResourceTemplateSpecification> resourceTemplateSpecifications) {
			Assert.notNull(resourceTemplateSpecifications, "resourceTemplateSpecifications must not be null");
			for (StreamingResourceTemplateSpecification spec : resourceTemplateSpecifications) {
				Assert.notNull(spec, "resourceTemplateSpecification must not be null");
				String uriTemplate = spec.resourceTemplate().uriTemplate();
				Assert.isTrue(
						this.resourceTemplates.stream()
							.noneMatch(existing -> existing.resourceTemplate().uriTemplate().equals(uriTemplate)),
						"Resource template '" + uriTemplate + "' is already registered");
				this.resourceTemplates.add(spec);
			}
			return this;
		}

		/**
		 * Registers a prompt.
		 * @param prompt the prompt definition
		 * @param getHandler the {@code prompts/get} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a prompt with the same name is registered
		 */
		public Builder prompt(Prompt prompt,
				BiFunction<McpRequestContext, GetPromptRequest, GetPromptOutcome> getHandler) {
			return prompts(new PromptSpecification(prompt, getHandler));
		}

		/**
		 * Registers prompts.
		 * @param promptSpecifications the prompts
		 * @return this builder
		 * @throws IllegalArgumentException if a prompt with the same name is registered
		 */
		public Builder prompts(PromptSpecification... promptSpecifications) {
			Assert.notNull(promptSpecifications, "promptSpecifications must not be null");
			return prompts(List.of(promptSpecifications));
		}

		/**
		 * Registers prompts.
		 * @param promptSpecifications the prompts
		 * @return this builder
		 * @throws IllegalArgumentException if a prompt with the same name is registered
		 */
		public Builder prompts(List<PromptSpecification> promptSpecifications) {
			Assert.notNull(promptSpecifications, "promptSpecifications must not be null");
			List<StreamingPromptSpecification> streamingSpecifications = new ArrayList<>();
			for (PromptSpecification spec : promptSpecifications) {
				Assert.notNull(spec, "promptSpecification must not be null");
				streamingSpecifications.add(new StreamingPromptSpecification(spec.prompt(),
						(ctx, request, sink) -> sink.complete(spec.getHandler().apply(ctx, request))));
			}
			return streamingPrompts(streamingSpecifications);
		}

		/**
		 * Registers a prompt whose handler may report progress.
		 * @param prompt the prompt definition
		 * @param streamingHandler the {@code prompts/get} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a prompt with the same name is registered
		 */
		public Builder streamingPrompt(Prompt prompt, StreamingPrompt streamingHandler) {
			return streamingPrompts(new StreamingPromptSpecification(prompt, streamingHandler));
		}

		/**
		 * Registers prompts whose handlers may report progress.
		 * @param promptSpecifications the prompts
		 * @return this builder
		 * @throws IllegalArgumentException if a prompt with the same name is registered
		 */
		public Builder streamingPrompts(StreamingPromptSpecification... promptSpecifications) {
			Assert.notNull(promptSpecifications, "promptSpecifications must not be null");
			return streamingPrompts(List.of(promptSpecifications));
		}

		/**
		 * Registers prompts whose handlers may report progress.
		 * @param promptSpecifications the prompts
		 * @return this builder
		 * @throws IllegalArgumentException if a prompt with the same name is registered
		 */
		public Builder streamingPrompts(List<StreamingPromptSpecification> promptSpecifications) {
			Assert.notNull(promptSpecifications, "promptSpecifications must not be null");
			for (StreamingPromptSpecification spec : promptSpecifications) {
				Assert.notNull(spec, "promptSpecification must not be null");
				String name = spec.prompt().name();
				Assert.isTrue(!this.prompts.containsKey(name), "Prompt '" + name + "' is already registered");
				this.prompts.put(name, spec);
			}
			return this;
		}

		/**
		 * Registers a completion handler for the arguments of a prompt or a resource
		 * template.
		 * @param reference the prompt or resource template
		 * @param completionHandler the {@code completion/complete} handler
		 * @return this builder
		 * @throws IllegalArgumentException if a handler for the same reference is
		 * registered
		 */
		public Builder completion(CompleteReference reference,
				BiFunction<McpRequestContext, CompleteRequest, CompleteResult> completionHandler) {
			return completions(new CompletionSpecification(reference, completionHandler));
		}

		/**
		 * Registers completion handlers.
		 * @param completionSpecifications the completion handlers
		 * @return this builder
		 * @throws IllegalArgumentException if a handler for the same reference is
		 * registered
		 */
		public Builder completions(CompletionSpecification... completionSpecifications) {
			Assert.notNull(completionSpecifications, "completionSpecifications must not be null");
			return completions(List.of(completionSpecifications));
		}

		/**
		 * Registers completion handlers.
		 * @param completionSpecifications the completion handlers
		 * @return this builder
		 * @throws IllegalArgumentException if a handler for the same reference is
		 * registered
		 */
		public Builder completions(List<CompletionSpecification> completionSpecifications) {
			Assert.notNull(completionSpecifications, "completionSpecifications must not be null");
			for (CompletionSpecification spec : completionSpecifications) {
				Assert.notNull(spec, "completionSpecification must not be null");
				CompleteReference key = completionKey(spec.reference());
				Assert.isTrue(!this.completions.containsKey(key),
						"Completion for " + spec.reference() + " is already registered");
				this.completions.put(key, spec);
			}
			return this;
		}

		/**
		 * Sets the caching hints applied to cacheable results that do not set their own:
		 * {@code server/discover}, the list methods, and {@code resources/read}. Defaults
		 * to immediately stale ({@code 0}) and {@link CacheScope#PRIVATE}.
		 * @param ttl how long clients may consider results fresh
		 * @param cacheScope who may cache results
		 * @return this builder
		 */
		public Builder cacheHints(Duration ttl, CacheScope cacheScope) {
			Assert.notNull(ttl, "ttl must not be null");
			Assert.isTrue(!ttl.isNegative(), "ttl must not be negative");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.ttl = ttl;
			this.cacheScope = cacheScope;
			return this;
		}

		/**
		 * Sets the JSON mapper. Defaults to the one found by
		 * {@link McpJsonDefaults#getMapper()}.
		 * @param jsonMapper the JSON mapper
		 * @return this builder
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the JSON schema validator, used to check the schemas of tools when the
		 * server is built, and the {@code structuredContent} of tool results. Defaults to
		 * the one found by {@link McpJsonDefaults#getSchemaValidator()}.
		 * @param jsonSchemaValidator the JSON schema validator
		 * @return this builder
		 */
		public Builder jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
			Assert.notNull(jsonSchemaValidator, "jsonSchemaValidator must not be null");
			this.jsonSchemaValidator = jsonSchemaValidator;
			return this;
		}

		/**
		 * Builds the server.
		 * @return the server
		 * @throws IllegalArgumentException if the schemas of a tool are invalid
		 */
		public McpServer build() {
			return new McpServer(this);
		}

		private ServerCapabilities deriveCapabilities() {
			ServerCapabilities.Builder builder = ServerCapabilities.builder();
			if (!this.tools.isEmpty()) {
				builder.tools(null);
			}
			if (!this.resources.isEmpty() || !this.resourceTemplates.isEmpty()) {
				builder.resources(null, null);
			}
			if (!this.prompts.isEmpty()) {
				builder.prompts(null);
			}
			if (!this.completions.isEmpty()) {
				builder.completions();
			}
			return builder.build();
		}

	}

}
