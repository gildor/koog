package io.github.gildor.koog.nativeagents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/**
 * Koog currently requires executor/model configuration even for a graph containing
 * only custom nodes. Fail explicitly if such a graph accidentally calls its LLM API.
 * This placeholder neither authenticates nor makes model requests.
 */
object NativeOnlyExecutor : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        error("This workflow uses native SDK nodes only")
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        error("This workflow uses native SDK nodes only")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        error("This workflow uses native SDK nodes only")
    override fun close() = Unit
}

/** Metadata placeholder required by Koog, never sent to a provider. */
val nativeOnlyModel = LLModel(
    provider = object : LLMProvider("native-session", "Host-owned native session") {},
    id = "native-session-placeholder",
    capabilities = listOf(LLMCapability.Completion),
    contextLength = 1,
)
