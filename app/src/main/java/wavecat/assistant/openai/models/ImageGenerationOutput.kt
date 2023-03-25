package wavecat.assistant.openai.models

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationOutput(
    val created: Long,
    val data: List<Image>
)