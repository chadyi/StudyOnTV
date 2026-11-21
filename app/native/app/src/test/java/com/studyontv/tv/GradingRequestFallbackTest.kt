package com.studyontv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingRequestFallbackTest {
    @Test
    fun keepsConfiguredTemperatureAndRetriesOtherNamedParameters() {
        val body = JSONObject()
            .put("model", "gpt-6-luna")
            .put("temperature", 0)
            .put("max_tokens", 200)
            .put("response_format", JSONObject().put("type", "json_object"))

        assertNull(
            GradingService.RequestFallback.apply(
                body,
                "服务返回 HTTP 400：Unsupported value: 'temperature' does not support 0 with this model. Only the default (1) value is supported."
            )
        )
        assertEquals(0, body.getInt("temperature"))
        assertTrue(body.has("response_format"))
        assertTrue(body.has("max_tokens"))

        assertEquals(
            "max_tokens → max_completion_tokens",
            GradingService.RequestFallback.apply(
                body,
                "服务返回 HTTP 400：Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."
            )
        )
        assertFalse(body.has("max_tokens"))
        assertEquals(200, body.getInt("max_completion_tokens"))
    }

    @Test
    fun removesResponseFormatAndTopPOnlyWhenTheProviderReportsThoseFields() {
        val body = JSONObject()
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("top_p", 1)

        assertEquals(
            "without response_format",
            GradingService.RequestFallback.apply(body, "HTTP 422: response_format is not supported")
        )
        assertFalse(body.has("response_format"))
        assertTrue(body.has("top_p"))

        assertEquals(
            "without top_p",
            GradingService.RequestFallback.apply(body, "HTTP 400: top_p is not supported by this model")
        )
        assertFalse(body.has("top_p"))
    }

    @Test
    fun doesNotRetryUnrelatedErrorsOrRepeatAnAlreadyRemovedField() {
        val body = JSONObject().put("model", "missing-model").put("temperature", 0)

        assertNull(GradingService.RequestFallback.apply(body, "服务返回 HTTP 400：模型不存在"))
        assertTrue(body.has("temperature"))
        assertNull(GradingService.RequestFallback.apply(body, "服务返回 HTTP 400：temperature is invalid"))
        assertNull(GradingService.RequestFallback.apply(body, "服务返回 HTTP 401：temperature is invalid"))
    }

    @Test
    fun validatesTemperatureInTheOpenAiCompatibleRange() {
        assertEquals(0.0, GradingService.validateTemperature(0.0), 0.0)
        assertEquals(1.0, GradingService.validateTemperature(1.0), 0.0)
        assertEquals(2.0, GradingService.validateTemperature(2.0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTemperatureBelowZero() {
        GradingService.validateTemperature(-0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTemperatureAboveTwo() {
        GradingService.validateTemperature(2.1)
    }
}
