package wavecat.assistant.openai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import wavecat.assistant.openai.models.CompletionsInput
import wavecat.assistant.openai.models.CompletionsResult
import wavecat.assistant.openai.models.ImageGenerationInput
import wavecat.assistant.openai.models.ImageGenerationOutput
import java.io.ByteArrayInputStream

class OpenAIClient(var apiKey: String) {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    suspend fun makeImageGeneration(input: ImageGenerationInput): Bitmap? {
        val imageGeneration = client.post {
            url("https://api.openai.com/v1/images/generations")
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(input)
        }.body<ImageGenerationOutput>()


        val byteArray = client.get(imageGeneration.data[0].url).readBytes()

        return withContext(Dispatchers.Default) {
            ByteArrayInputStream(byteArray).use {
                val option = BitmapFactory.Options()
                option.inPreferredConfig = Bitmap.Config.ARGB_8888
                BitmapFactory.decodeStream(it, null, option)
            }
        }
    }

    suspend fun makeCompletion(input: CompletionsInput): CompletionsResult =
        client.post {
            url("https://api.openai.com/v1/chat/completions")
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(input)
        }.body()

}