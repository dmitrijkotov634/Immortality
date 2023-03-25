package wavecat.assistant.openai.models

import kotlinx.serialization.Serializable

@Serializable
data class Image(
    val url: String
)