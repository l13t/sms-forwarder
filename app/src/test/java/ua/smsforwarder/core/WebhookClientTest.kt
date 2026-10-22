package ua.smsforwarder.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import ua.smsforwarder.delivery.WebhookClient

class WebhookClientTest {
    @Test fun sendsJsonAndAuthenticationAndKeepsStableIdempotencyKey() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val result=WebhookClient().send(server.url("/webhook").toString(),mapOf("X-Secret" to "token"),"{\"type\":\"test\"}","delivery-1")
            assertTrue(result.success)
            val request=server.takeRequest()
            assertEquals("POST",request.method)
            assertEquals("token",request.getHeader("X-Secret"))
            assertEquals("delivery-1",request.getHeader("Idempotency-Key"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("{\"type\":\"test\"}",request.body.readUtf8())
        }
    }
    @Test fun classifiesHttpFailuresAndDoesNotFollowRedirects() {
        MockWebServer().use { server ->
            val client=WebhookClient()
            for(code in listOf(400,401,404,408,429,500,503,302)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","120").setHeader("Location","/other"))
                val result=client.send(server.url("/webhook").toString(),emptyMap(),"{}","id")
                assertFalse(result.success)
                assertEquals(code in listOf(408,429,500,503),result.retry)
                assertEquals("HTTP $code",result.message)
                assertEquals(120_000L,result.retryAfterMs)
            }
            assertEquals(8,server.requestCount)
        }
    }
    @Test fun rejectsCredentialsFragmentsAndReservedHeaders() {
        for(url in listOf("ftp://example.com", "https://user:pass@example.com", "https://example.com/#secret")) {
            assertTrue(runCatching { WebhookClient.validate(url,emptyMap()) }.isFailure)
        }
        assertTrue(runCatching { WebhookClient.validate("http://192.168.1.2:5678/webhook",mapOf("Content-Type" to "text/plain")) }.isFailure)
        WebhookClient.validate("http://192.168.1.2:5678/webhook",mapOf("Authorization" to "Bearer token"))
    }
}
