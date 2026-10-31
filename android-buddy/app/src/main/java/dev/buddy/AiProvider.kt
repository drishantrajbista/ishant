package dev.buddy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val NOT_HEARD = "i didn't catch that"

class ApiException(val code: Int, message: String, val body: String = "") : IOException(message)

/** An error whose message is shown in the bubble and also spoken. */
open class SpokenException(message: String) : IOException(message)

/** The model finished without saying anything; [reason] is its finish/stop/block reason. */
class EmptyReplyException(reason: String?) :
    IOException("no answer came back" + (reason?.let { " ($it)" } ?: ""))

/** Claude refused. */
class DeclinedException : SpokenException("claude declined to answer that")

internal val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Keeps the provider's own message (first 150 chars) so the bubble shows what really went wrong. */
private fun apiError(r: Response): ApiException {
    val raw = r.body?.string().orEmpty()
    val detail = try { JSONObject(raw).getJSONObject("error").getString("message") } catch (e: Exception) { raw }
    return ApiException(r.code, "API error ${r.code}: ${detail.replace(Regex("\\s+"), " ").trim().take(150)}", raw)
}

/** A plain (non-streaming) request whose response is a JSON object. Cancellable. */
internal suspend fun httpJson(request: Request): JSONObject {
    val call = sharedHttp.newCall(request)
    val response = suspendCancellableCoroutine<Response> { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
    }
    return withContext(Dispatchers.IO) {
        response.use {
            if (!it.isSuccessful) throw apiError(it)
            JSONObject(it.body!!.string())
        }
    }
}

/** A plain GET (or any request) whose response is text. Cancellable. */
internal suspend fun httpText(request: Request): String {
    val call = sharedHttp.newCall(request)
    val response = suspendCancellableCoroutine<Response> { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
    }
    return withContext(Dispatchers.IO) { response.use { if (!it.isSuccessful) throw apiError(it); it.body!!.string() } }
}
