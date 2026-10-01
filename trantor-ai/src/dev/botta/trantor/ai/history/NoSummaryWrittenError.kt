package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.errors.AIError
import dev.botta.trantor.ai.models.chat.ChatResponse

/**
 * The model asked to summarize a conversation wrote no summary, or one cut short by the tokens it was given. The
 * conversation was not touched; [response] is what the model answered.
 */
class NoSummaryWrittenError(message: String, val response: ChatResponse): AIError(message)
