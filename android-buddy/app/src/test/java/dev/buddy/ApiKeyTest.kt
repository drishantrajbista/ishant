package dev.buddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyTest {
    @Test fun keysAreStrippedOfWhitespaceAndNewlines() {
        assertEquals("sk-ant-abc123", cleanKey("  sk-ant-abc123\n"))
        assertEquals("sk-ant-abc123", cleanKey("sk-ant-\nabc 123"))
    }

    @Test fun keyShapeCheck() {
        assertTrue(looksLikeClaudeKey(" sk-ant-api03-xyz \n"))
        assertFalse(looksLikeClaudeKey("https://example.com"))
        assertFalse(looksLikeClaudeKey("AIzaSyGeminiKey"))
        assertFalse(looksLikeClaudeKey(""))
    }

    @Test fun aKeyPastedIntoTheOldProxyFieldMovesToTheKey() {
        assertEquals("sk-ant-api03-xyz", keyFromOldProxyField(" sk-ant-api03-xyz\n", ""))
    }

    @Test fun anExistingKeyIsNeverOverwrittenAndRealUrlsAreDropped() {
        assertNull(keyFromOldProxyField("sk-ant-new", "sk-ant-existing"))
        assertNull(keyFromOldProxyField("https://my-worker.example.workers.dev", ""))
        assertNull(keyFromOldProxyField("", ""))
    }

    @Test fun friendlyClaudeErrors() {
        assertEquals("claude rejected the api key. check it in settings", claudeErrorMessage(401, "{}"))
        assertEquals("claude rejected the api key. check it in settings", claudeErrorMessage(403, "{}"))
        assertEquals(
            "add credit at console.anthropic.com",
            claudeErrorMessage(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}"""),
        )
        assertNull(claudeErrorMessage(400, """{"error":{"message":"max_tokens: too large"}}"""))
        assertEquals("claude is rate limited, try again in a minute", claudeErrorMessage(429, "{}"))
        assertEquals("claude is having trouble right now, try again in a moment", claudeErrorMessage(529, "{}"))
        assertNull(claudeErrorMessage(404, "{}"))
    }
}
