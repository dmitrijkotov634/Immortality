package wavecat.assistant.openai.models

import kotlinx.serialization.Serializable

@Serializable
data class CompletionsChoices(
    val message: Message
)