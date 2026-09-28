/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.function.BiFunction;

import io.modelcontextprotocol.modern.server.StreamingHandler.StreamingPrompt;
import io.modelcontextprotocol.modern.server.StreamingHandler.StreamingResource;
import io.modelcontextprotocol.modern.server.StreamingHandler.StreamingTool;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteReference;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.Prompt;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.spec.McpSchema.Resource;
import io.modelcontextprotocol.modern.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.spec.McpSchema.Tool;
import io.modelcontextprotocol.modern.util.Assert;

/**
 * The features a server offers, each a definition paired with the handler that serves it.
 * Handlers are plain blocking functions, or {@link StreamingHandler streaming handlers}
 * that may report progress before completing.
 */
public final class McpServerFeatures {

	private McpServerFeatures() {
	}

	/**
	 * A tool and its {@code tools/call} handler.
	 *
	 * @param tool the tool definition
	 * @param callHandler the handler, returning a {@link CallToolOutcome}: the result, or
	 * an {@link io.modelcontextprotocol.modern.spec.McpSchema.InputRequiredResult}
	 */
	public record ToolSpecification(Tool tool,
			BiFunction<McpRequestContext, CallToolRequest, CallToolOutcome> callHandler) {

		public ToolSpecification {
			Assert.notNull(tool, "tool must not be null");
			Assert.notNull(callHandler, "callHandler must not be null");
		}

	}

	/**
	 * A tool and its streaming {@code tools/call} handler.
	 *
	 * @param tool the tool definition
	 * @param streamingHandler the handler, completing with a {@link CallToolOutcome}
	 */
	public record StreamingToolSpecification(Tool tool, StreamingTool streamingHandler) {

		public StreamingToolSpecification {
			Assert.notNull(tool, "tool must not be null");
			Assert.notNull(streamingHandler, "streamingHandler must not be null");
		}

	}

	/**
	 * A resource and its {@code resources/read} handler.
	 *
	 * @param resource the resource definition
	 * @param readHandler the handler
	 */
	public record ResourceSpecification(Resource resource,
			BiFunction<McpRequestContext, ReadResourceRequest, ReadResourceOutcome> readHandler) {

		public ResourceSpecification {
			Assert.notNull(resource, "resource must not be null");
			Assert.notNull(readHandler, "readHandler must not be null");
		}

	}

	/**
	 * A resource and its streaming {@code resources/read} handler.
	 *
	 * @param resource the resource definition
	 * @param streamingHandler the handler
	 */
	public record StreamingResourceSpecification(Resource resource, StreamingResource streamingHandler) {

		public StreamingResourceSpecification {
			Assert.notNull(resource, "resource must not be null");
			Assert.notNull(streamingHandler, "streamingHandler must not be null");
		}

	}

	/**
	 * A resource template and the {@code resources/read} handler for URIs matching it.
	 *
	 * @param resourceTemplate the resource template definition
	 * @param readHandler the handler
	 */
	public record ResourceTemplateSpecification(ResourceTemplate resourceTemplate,
			BiFunction<McpRequestContext, ReadResourceRequest, ReadResourceOutcome> readHandler) {

		public ResourceTemplateSpecification {
			Assert.notNull(resourceTemplate, "resourceTemplate must not be null");
			Assert.notNull(readHandler, "readHandler must not be null");
		}

	}

	/**
	 * A resource template and the streaming {@code resources/read} handler for URIs
	 * matching it.
	 *
	 * @param resourceTemplate the resource template definition
	 * @param streamingHandler the handler
	 */
	public record StreamingResourceTemplateSpecification(ResourceTemplate resourceTemplate,
			StreamingResource streamingHandler) {

		public StreamingResourceTemplateSpecification {
			Assert.notNull(resourceTemplate, "resourceTemplate must not be null");
			Assert.notNull(streamingHandler, "streamingHandler must not be null");
		}

	}

	/**
	 * A prompt and its {@code prompts/get} handler.
	 *
	 * @param prompt the prompt definition
	 * @param getHandler the handler
	 */
	public record PromptSpecification(Prompt prompt,
			BiFunction<McpRequestContext, GetPromptRequest, GetPromptOutcome> getHandler) {

		public PromptSpecification {
			Assert.notNull(prompt, "prompt must not be null");
			Assert.notNull(getHandler, "getHandler must not be null");
		}

	}

	/**
	 * A prompt and its streaming {@code prompts/get} handler.
	 *
	 * @param prompt the prompt definition
	 * @param streamingHandler the handler
	 */
	public record StreamingPromptSpecification(Prompt prompt, StreamingPrompt streamingHandler) {

		public StreamingPromptSpecification {
			Assert.notNull(prompt, "prompt must not be null");
			Assert.notNull(streamingHandler, "streamingHandler must not be null");
		}

	}

	/**
	 * A {@code completion/complete} handler for the arguments of a prompt or a resource
	 * template.
	 *
	 * @param reference the prompt or resource template
	 * @param completionHandler the handler
	 */
	public record CompletionSpecification(CompleteReference reference,
			BiFunction<McpRequestContext, CompleteRequest, CompleteResult> completionHandler) {

		public CompletionSpecification {
			Assert.notNull(reference, "reference must not be null");
			Assert.notNull(completionHandler, "completionHandler must not be null");
		}

	}

}
