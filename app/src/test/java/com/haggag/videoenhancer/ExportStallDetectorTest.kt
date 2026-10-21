package com.haggag.videoenhancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPolicyTest {

    @Test
    fun slow_devices_get_a_large_muxer_sample_window() {
        assertEquals(120_000L, ExportPolicy.maxDelayBetweenMuxerSamplesMs)
    }

    @Test
    fun failure_text_exposes_error_code_and_root_cause() {
        val text = ExportPolicy.formatFailure(
            errorCodeName = "ERROR_CODE_MUXING_TIMEOUT",
            message = "Muxer error",
            causeMessage = "No output sample written for 10000 ms"
        )

        assertTrue(text.contains("ERROR_CODE_MUXING_TIMEOUT"))
        assertTrue(text.contains("No output sample written for 10000 ms"))
    }
}
