package com.example.andrea.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class LocalAgentBrain(modelPath: String) {

    private val engine: Engine

    init {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU()
        )
        engine = Engine(config)
        engine.initialize()
    }

    suspend fun decideNextAction(
        userGoal: String,
        screenUI: String,
        onPartialText: (String) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(
                """
                You are an Android automation AI agent.
                Your task is to achieve the user's Goal by returning a single JSON action object.
                Output ONLY a single raw JSON object. Do NOT include markdown formatting, code blocks, conversational text, or explanations.

                Valid Action JSON Formats:
                {"action": "open_app", "name": "YouTube"}
                {"action": "click", "id": 5}
                {"action": "type", "id": 3, "text": "hello"}
                {"action": "scroll_down"}
                {"action": "scroll_up"}
                {"action": "home"}
                {"action": "back"}
                {"action": "done"}

                Few-Shot Examples:
                Goal: open YouTube
                Current Screen Elements:
                Screen empty
                JSON: {"action": "open_app", "name": "YouTube"}

                Goal: hi
                Current Screen Elements:
                Screen empty
                JSON: {"action": "done"}

                Goal: click search
                Current Screen Elements:
                [1] "Search" (clickable)
                JSON: {"action": "click", "id": 1}

                Goal: go home
                Current Screen Elements:
                [1] "App"
                JSON: {"action": "home"}
                """.trimIndent()
            ),
            samplerConfig = SamplerConfig(
                topK = 20,
                topP = 0.9,
                temperature = 0.0
            )
        )

        val userPrompt = """
            Goal: $userGoal
            Current Screen Elements:
            ${if (screenUI.isBlank()) "Screen empty" else screenUI}
            JSON:
        """.trimIndent()

        val conversation = engine.createConversation(conversationConfig)
        var finalResult = ""

        conversation.sendMessageAsync(userPrompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("\n") { it.text }
                    .trim()

                val cleanedText = if (text.contains("JSON:")) {
                    text.substringAfter("JSON:").trim()
                } else {
                    text
                }

                finalResult = cleanedText
                if (cleanedText.isNotEmpty()) {
                    onPartialText(cleanedText)
                }
            }

            override fun onDone() {
                try {
                    conversation.close()
                } catch (_: Exception) {}
                if (continuation.isActive) {
                    continuation.resume(finalResult)
                }
            }

            override fun onError(throwable: Throwable) {
                try {
                    conversation.close()
                } catch (_: Exception) {}
                if (continuation.isActive) {
                    continuation.resumeWithException(throwable)
                }
            }
        })
    }
}
