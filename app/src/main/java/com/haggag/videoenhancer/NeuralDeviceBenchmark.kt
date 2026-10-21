package com.haggag.videoenhancer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * Measures the *actual* current Real-ESRGAN tile path on this device instead of assuming
 * performance from chipset names or desktop/Jetson benchmarks. The result includes the
 * current Bitmap packing + LiteRT inference + output unpacking cost, which is intentionally
 * what Smart needs while V3 still uses that implementation.
 */
object NeuralDeviceBenchmark {
    private const val PREFS = "neural_device_benchmark_v1"
    private const val KEY_MS_PER_TILE = "ms_per_tile"
    private const val KEY_BACKEND = "backend"
    private const val KEY_MEASURED_AT = "measured_at"
    private const val PROFILE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val SLOW_FIRST_RUN_MS = 2_000.0

    data class Profile(
        val msPerTile: Double,
        val backend: RealEsrganUpscaler.Backend,
        val measuredAtEpochMs: Long
    )

    fun load(context: Context, nowEpochMs: Long = System.currentTimeMillis()): Profile? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bits = prefs.getLong(KEY_MS_PER_TILE, Long.MIN_VALUE)
        val measuredAt = prefs.getLong(KEY_MEASURED_AT, 0L)
        val backendName = prefs.getString(KEY_BACKEND, null) ?: return null
        if (bits == Long.MIN_VALUE || measuredAt <= 0L) return null
        if (nowEpochMs - measuredAt !in 0..PROFILE_MAX_AGE_MS) return null

        val ms = Double.fromBits(bits)
        if (!ms.isFinite() || ms <= 0.0) return null
        val backend = runCatching { RealEsrganUpscaler.Backend.valueOf(backendName) }.getOrNull()
            ?: return null
        return Profile(ms, backend, measuredAt)
    }

    /** Blocking; caller must invoke from a worker thread. */
    fun measure(context: Context): Profile {
        val input = Bitmap.createBitmap(AiTileGrid.TILE_SIZE, AiTileGrid.TILE_SIZE, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.rgb(96, 104, 112))

        try {
            RealEsrganUpscaler.create(context.applicationContext).use { upscaler ->
                // First pass includes warm-up. If it is already very slow, do not punish the user
                // with several additional samples: the first pass is enough to reject full-video AI.
                val first = timedTile(upscaler, input)
                val measured = if (first >= SLOW_FIRST_RUN_MS) {
                    first
                } else {
                    val second = timedTile(upscaler, input)
                    val third = timedTile(upscaler, input)
                    median(first, second, third)
                }

                val profile = Profile(
                    msPerTile = max(0.01, measured),
                    backend = upscaler.backend,
                    measuredAtEpochMs = System.currentTimeMillis()
                )
                save(context, profile)
                return profile
            }
        } finally {
            if (!input.isRecycled) input.recycle()
        }
    }

    private fun timedTile(upscaler: RealEsrganUpscaler, input: Bitmap): Double {
        val started = System.nanoTime()
        val output = upscaler.upscale(
            input = input,
            requestedWidth = AiTileGrid.TILE_SIZE * AiTileGrid.SCALE,
            requestedHeight = AiTileGrid.TILE_SIZE * AiTileGrid.SCALE
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        if (!output.isRecycled) output.recycle()
        return elapsedMs
    }

    private fun median(a: Double, b: Double, c: Double): Double {
        return listOf(a, b, c).sorted()[1]
    }

    private fun save(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MS_PER_TILE, profile.msPerTile.toBits())
            .putString(KEY_BACKEND, profile.backend.name)
            .putLong(KEY_MEASURED_AT, profile.measuredAtEpochMs)
            .apply()
    }
}
