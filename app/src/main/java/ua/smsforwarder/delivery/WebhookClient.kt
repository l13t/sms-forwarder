package ua.smsforwarder.delivery

import ua.smsforwarder.core.Message
import ua.smsforwarder.core.tr

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SendResult(val success: Boolean, val retry: Boolean, val message: String?, val retryAfterMs: Long? = null)
class WebhookClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS).build()) {
    companion object {
        private val reserved = setOf("content-type", "content-length", "host", "connection", "transfer-encoding", "idempotency-key")
        fun validate(url: String, headers: Map<String, String>) {
            val parsed = url.toHttpUrlOrNull() ?: error(tr(Message.ENTER_A_VALID_HTTP_HTTPS_URL))
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null) { tr(Message.DO_NOT_PUT_CREDENTIALS_OR_A_FRAGMENT) }
            headers.forEach { (key, value) ->
                require(key.lowercase() !in reserved) { tr(Message.THE_APP_SETS_THE_HEADER , key) }
                Request.Builder().url(parsed).header(key, value).build()
            }
        }
    }
    fun send(url: String, headers: Map<String, String>, payload: String, deliveryId: String): SendResult {
        return try {
            validate(url, headers)
            val builder = Request.Builder().url(url).post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Idempotency-Key", deliveryId)
            headers.forEach { (k,v) -> builder.header(k,v) }
            client.newCall(builder.build()).execute().use { response ->
                val code = response.code
                SendResult(code in 200..299, code == 408 || code == 429 || code in 500..599,
                    if (code in 200..299) null else "HTTP $code",
                    RetryPolicy.retryAfter(response.header("Retry-After"), System.currentTimeMillis()))
            }
        } catch (_: IOException) { SendResult(false, true, tr(Message.NETWORK_OR_TLS_ERROR)) }
        catch (_: IllegalArgumentException) { SendResult(false, false, tr(Message.INVALID_URL_OR_HEADERS)) }
    }
}
