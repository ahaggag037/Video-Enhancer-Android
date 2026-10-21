package com.haggag.videoenhancer

object ExportPolicy {
    // Media3 defaults to only 10 seconds on physical devices. On slower Android encoders,
    // especially while upscaling, valid output samples can arrive farther apart. Keep a finite
    // watchdog so a genuinely wedged codec still fails instead of spinning forever.
    const val maxDelayBetweenMuxerSamplesMs: Long = 120_000L

    fun formatFailure(
        errorCodeName: String,
        message: String?,
        causeMessage: String?
    ): String {
        val base = message?.takeIf { it.isNotBlank() } ?: "unknown error"
        val cause = causeMessage?.takeIf { it.isNotBlank() }
        return if (cause == null) {
            "Export failed [$errorCodeName]: $base"
        } else {
            "Export failed [$errorCodeName]: $base — $cause"
        }
    }
}
