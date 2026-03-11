package ma.general.ai.service.llm.impl

import ma.general.ai.constants.MimeType
import ma.general.ai.dto.FileDTO
import ma.general.ai.service.llm.ChatService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service
import org.springframework.util.MimeTypeUtils
import java.util.Base64

@Service
class SpringAIChatService(
    chatModel: GoogleGenAiChatModel
) : ChatService {
    private val chatClient: ChatClient = ChatClient.builder(chatModel).build()

    // ── Simple text call ──────────────────────────────────────────────────────

    override fun call(text: String): String =
        chatClient.prompt()
            .user(text)
            .call()
            .content() ?: ""

    // ── Call with raw bytes (converts to base64 then delegates) ───────────────

    override fun call(text: String, fileBytes: ByteArray, mimeType: MimeType): String =
        call(text, Base64.getEncoder().encodeToString(fileBytes), mimeType)

    // ── Call with base64 + mimeType ───────────────────────────────────────────

    override fun call(text: String, base64EncodedFile: String, mimeType: MimeType): String {
        val message = buildUserMessageWithMedia(text, base64EncodedFile, mimeType)
        return chatClient.prompt(Prompt(message))
            .call()
            .content() ?: ""
    }

    // ── getJson via FileDTO ───────────────────────────────────────────────────

    override fun getJson(fileDTO: FileDTO): String =
        getJson(fileDTO.text, fileDTO.b64EFile, fileDTO.mimeType, fileDTO.schema)

    // ── getJson: flexible – prompt-injected schema + structured output ────────

    override fun getJson(
        text: String,
        base64EncodedFile: String,
        mimeType: MimeType,
        schema: String?
    ): String {
        // Build prompt text: append schema instructions if a schema was provided
        val enrichedText = if (!schema.isNullOrBlank()) {
            """
            $text
            
            Respond ONLY with a valid JSON object that strictly follows this JSON Schema:
            $schema
            Do not include any explanation or markdown — raw JSON only.
            """.trimIndent()
        } else {
            "$text\n\nRespond ONLY with a valid JSON object. No explanation or markdown."
        }

        val message = buildUserMessageWithMedia(enrichedText, base64EncodedFile, mimeType)

        return chatClient.prompt(Prompt(message))
            .call()
            .content()
            ?.trim()
            ?.removePrefix("```json")
            ?.removePrefix("```")
            ?.removeSuffix("```")
            ?.trim()
            ?: "{}"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a UserMessage that carries both the prompt text and an inline
     * media attachment (image, PDF, audio, video, …).
     */
    private fun buildUserMessageWithMedia(
        text: String,
        base64EncodedFile: String,
        mimeType: MimeType
    ): UserMessage {
        val bytes = Base64.getDecoder().decode(base64EncodedFile)
        val springMimeType = MimeTypeUtils.parseMimeType(mimeType.mimeString)
        val media = Media(springMimeType, ByteArrayResource(bytes))  // ← wrap in ByteArrayResource
        return UserMessage.builder()
            .text(text)
            .media(media)
            .build()
    }
}