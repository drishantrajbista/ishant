package dev.buddy

/** Keys pasted from a browser often carry spaces or a trailing newline. */
fun cleanKey(raw: String): String = raw.filterNot { it.isWhitespace() }

fun looksLikeClaudeKey(raw: String): Boolean = cleanKey(raw).startsWith("sk-ant-")

/** Versions before 1.2.1 had a proxy URL field; people pasted their API key into it. Returns the key to adopt, if any. */
fun keyFromOldProxyField(proxy: String, claudeKey: String): String? =
    cleanKey(proxy).takeIf { claudeKey.isBlank() && it.startsWith("sk-ant") }

/** A friendlier message for the Claude API errors people actually hit, or null to show the API's own message. */
fun claudeErrorMessage(code: Int, body: String): String? = when {
    code == 401 || code == 403 -> "claude rejected the api key. check it in settings"
    code == 400 && Regex("credit|billing", RegexOption.IGNORE_CASE).containsMatchIn(body) ->
        "add credit at console.anthropic.com"
    code == 429 -> "claude is rate limited, try again in a minute"
    code >= 500 -> "claude is having trouble right now, try again in a moment"
    else -> null
}
