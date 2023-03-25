package wavecat.assistant.openai.models

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val role: String,
    var content: String
)