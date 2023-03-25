package wavecat.assistant.openai.models

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationInput(
    val prompt: String,
    val n: Int,
    val size: String
)