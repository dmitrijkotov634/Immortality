package wavecat.assistant.openai.models

import kotlinx.serialization.Serializable

@Serializable
data class CompletionsResult(
    val choices: List<CompletionsChoices>
)