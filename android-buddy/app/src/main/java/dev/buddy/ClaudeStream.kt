package dev.buddy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.TreeMap

/** The stream isn't something we can read (bad JSON, ended early, restarted): the caller falls back to plain requests. */
class StreamFormatException(message: String) : IOException(message)

/** What a streamed assistant message tells the agent loop, in order. */
sealed interface StreamEvent {
    /** One content block is complete; for a `tool_use` its `input` is the parsed JSON object. */
    class BlockDone(val index: Int, val block: JSONObject) : StreamEvent

    /** The message is complete: its `content` exactly as a plain response carries it, and why it stopped. */
    class Done(val stopReason: String?, val content: JSONArray) : StreamEvent
}

/**
 * Rebuilds a streamed Messages API response the way the official SDKs do: each `content_block_start` block is copied as it
 * is, text, thinking and signature deltas are applied to it, and the `input_json_delta` pieces of a tool call are joined and
 * parsed when the block stops. The result is the same `content` array a non-streaming request returns, which matters because
 * the conversation history is append-only and has to carry thinking blocks and their signatures back unchanged.
 */
class StreamAccumulator {
    private val blocks = TreeMap<Int, JSONObject>()
    private val text = HashMap<Int, StringBuilder>()
    private val thinking = HashMap<Int, StringBuilder>()
    private val inputJson = HashMap<Int, StringBuilder>()
    private var started = false
    private var stopReason: String? = null

    /** True if an event carried something this does not rebuild (an unknown delta type), so the content may differ from a plain response. */
    var lossy = false
        private set

    fun accept(event: JSONObject): List<StreamEvent> {
        when (event.optString("type")) {
            "message_start" -> {
                if (started) throw StreamFormatException("the reply restarted part-way through")
                started = true
            }
            "content_block_start" -> {
                val i = event.getInt("index")
                val b = JSONObject(event.getJSONObject("content_block").toString())   // a copy: we edit it as deltas arrive
                if (b.optString("type") == "text" && !b.has("text")) b.put("text", "")
                if (b.optString("type") == "thinking" && !b.has("thinking")) b.put("thinking", "")
                blocks[i] = b
            }
            "content_block_delta" -> delta(event.getInt("index"), event.getJSONObject("delta"))
            "content_block_stop" -> {
                val i = event.getInt("index")
                val b = blocks[i] ?: throw StreamFormatException("a block stopped before it started")
                flush(i, b)
                return listOf(StreamEvent.BlockDone(i, b))
            }
            "message_delta" -> event.optJSONObject("delta")?.let { d ->
                if (d.has("stop_reason") && !d.isNull("stop_reason")) stopReason = d.getString("stop_reason")
            }
            "message_stop" -> {
                blocks.forEach { (i, b) -> flush(i, b) }   // a block that never saw its stop event still gets what arrived
                return listOf(StreamEvent.Done(stopReason, JSONArray().also { a -> blocks.values.forEach(a::put) }))
            }
            "error" -> throw streamError(event)
            // "ping" and anything new: nothing to rebuild
        }
        return emptyList()
    }

    private fun delta(index: Int, d: JSONObject) {
        val b = blocks[index] ?: throw StreamFormatException("a delta arrived before its block")
        when (d.optString("type")) {
            "text_delta" -> text.getOrPut(index) { StringBuilder(b.optString("text")) }.append(d.optString("text"))
            "thinking_delta" -> thinking.getOrPut(index) { StringBuilder(b.optString("thinking")) }.append(d.optString("thinking"))
            "signature_delta" -> b.put("signature", d.optString("signature"))
            "input_json_delta" -> inputJson.getOrPut(index) { StringBuilder() }.append(d.optString("partial_json"))
            else -> lossy = true
        }
    }

    /** Writes what has been collected for block [i] into [b]. */
    private fun flush(i: Int, b: JSONObject) {
        text.remove(i)?.let { b.put("text", it.toString()) }
        thinking.remove(i)?.let { b.put("thinking", it.toString()) }
        inputJson.remove(i)?.let { json ->
            val s = json.toString()
            try {
                b.put("input", if (s.isBlank()) JSONObject() else JSONObject(s))
            } catch (e: JSONException) {
                throw StreamFormatException("a tool call was cut off")
            }
        }
    }
}

/** An `error` event in the middle of a stream, as the same exception an HTTP error would have been. */
private fun streamError(event: JSONObject): ApiException {
    val err = event.optJSONObject("error")
    val code = when (err?.optString("type")) {
        "overloaded_error" -> 529
        "rate_limit_error" -> 429
        "authentication_error" -> 401
        "permission_error" -> 403
        "not_found_error" -> 404
        "request_too_large" -> 413
        "invalid_request_error" -> 400
        else -> 500
    }
    val detail = err?.optString("message").orEmpty().replace(Regex("\\s+"), " ").trim().take(150)
    return ApiException(code, "API error $code: $detail", event.toString())
}

/** The SSE `data:` payloads of one response as [StreamEvent]s. */
fun streamEvents(data: Flow<String>): Flow<StreamEvent> = flow {
    val acc = StreamAccumulator()
    data.collect { line ->
        val json = try { JSONObject(line) } catch (e: JSONException) { throw StreamFormatException("unreadable data in the stream") }
        for (event in acc.accept(json)) emit(event)
    }
}

/** A complete (non-streaming) response played as the same events, so one loop runs both. */
fun messageEvents(response: JSONObject): Flow<StreamEvent> = flow {
    val content = response.getJSONArray("content")
    for (i in 0 until content.length()) {
        val block = content.getJSONObject(i)
        if (block.optString("type") == "tool_use") emit(StreamEvent.BlockDone(i, block))
    }
    emit(StreamEvent.Done(response.str("stop_reason"), content))
}

/** How many tool calls a turn has started, so a stream that breaks afterwards isn't replayed (the actions already happened). */
class TurnProgress { var toolsRun = 0 }

/** What one assistant turn produced: its content, and a `tool_result` for each tool call in it, in order. */
class Turn(
    val content: JSONArray,
    val stopReason: String?,
    val uses: List<ToolUse>,
    val results: JSONArray,
    /** A tool failed: the later ones were not run. */
    val failed: Boolean,
    /** Something other than looking was attempted. */
    val acted: Boolean,
    /** Time spent inside tools, which overlapped with the stream still arriving. */
    val toolNanos: Long,
)

/**
 * Reads one assistant turn and runs each tool call the moment its block is complete, while later blocks may still be
 * arriving. Tools run in order and the first failure stops the rest (they get [AgentJson.HALT]); every call gets exactly one
 * result. The turn's `content` is only known at the end, so the caller appends it to the history then, whole.
 */
suspend fun consumeTurn(events: Flow<StreamEvent>, phone: Phone, status: (String) -> Unit, progress: TurnProgress = TurnProgress()): Turn {
    val uses = ArrayList<ToolUse>()
    val results = JSONArray()
    var failed = false
    var acted = false
    var toolNanos = 0L
    var done: StreamEvent.Done? = null
    events.collect { event ->
        when (event) {
            is StreamEvent.BlockDone -> if (event.block.optString("type") == "tool_use") {
                val use = try { AgentJson.toolUse(event.block) } catch (e: JSONException) { throw StreamFormatException("a tool call had no name") }
                uses += use
                if (failed) {
                    results.put(AgentJson.result(use, null, AgentJson.HALT))
                } else {
                    if (isActionTool(use.name)) acted = true
                    progress.toolsRun++
                    val t0 = System.nanoTime()
                    try {
                        results.put(AgentJson.result(use, phone.run(use, status), null))
                    } catch (e: ToolError) {
                        failed = true
                        results.put(AgentJson.result(use, null, e.message ?: "failed"))
                    } finally {
                        toolNanos += System.nanoTime() - t0
                    }
                }
            }
            is StreamEvent.Done -> done = event
        }
    }
    val end = done ?: throw StreamFormatException("the reply was cut short")
    // Every tool call in the content must have been answered above, or the next request would be rejected.
    val promised = AgentJson.toolUses(end.content).map { it.id }
    if (promised != uses.map { it.id }) throw StreamFormatException("a tool call never finished")
    return Turn(end.content, end.stopReason, uses, results, failed, acted, toolNanos)
}
