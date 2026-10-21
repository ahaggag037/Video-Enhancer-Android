package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import java.util.Locale

/**
 * Diagnostic-only feasibility benchmark for the converted NanoVSR-226k model.
 * It measures CompiledModel execution without CPU output readback so the number
 * is closer to a future GPU/NPU-resident video pipeline than Bitmap processing.
 */
object NanoVsrDeviceBenchmark {
    const val ASSET_NAME = "nanovsr_226k_t5_180x320.tflite"
    private const val TEMPORAL_WINDOW = 5
    private const val INPUT_HEIGHT = 180
    private const val INPUT_WIDTH = 320
    private const val CHANNELS = 3
    private const val GPU_TIMED_RUNS = 3
    private const val CPU_TIMED_RUNS = 2
    private const val SLOW_CPU_WARMUP_MS = 5_000.0

    data class Entry(
        val accelerator: Accelerator,
        val loadMs: Double? = null,
        val medianWindowMs: Double? = null,
        val modelOnlyFps: Double? = null,
        val samples: Int = 0,
        val error: String? = null
    )

    data class Result(val entries: List<Entry>) {
        fun format(): String = buildString {
            append("NanoVSR-226k • 5×180×320 → 5×720×1280\n")
            entries.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                append(entry.accelerator.name)
                if (entry.error != null) {
                    append(" unavailable • ")
                    append(entry.error.take(180))
                } else {
                    append(String.format(Locale.US, " • load %.0f ms", entry.loadMs))
                    append(String.format(Locale.US, " • %.1f ms/window", entry.medianWindowMs))
                    append(String.format(Locale.US, " • model-only %.1f fps", entry.modelOnlyFps))
                    append(" • n=${entry.samples}")
                }
            }
            append("\nModel-only benchmark; decode/compose/encode are not included.")
        }
    }

    fun assetAvailable(context: Context): Boolean = runCatching {
        context.assets.open(ASSET_NAME).use { }
        true
    }.getOrDefault(false)

    /** Blocking. Must run on a worker thread. */
    fun measure(context: Context): Result {
        require(assetAvailable(context)) { "NanoVSR diagnostic asset is not packaged in this build" }
        val input = FloatArray(TEMPORAL_WINDOW * CHANNELS * INPUT_HEIGHT * INPUT_WIDTH) { index ->
            ((index * 37) % 251) / 250f
        }

        val entries = listOf(Accelerator.GPU, Accelerator.CPU).map { accelerator ->
            measureOne(context.applicationContext, accelerator, input)
        }
        return Result(entries)
    }

    private fun measureOne(
        context: Context,
        accelerator: Accelerator,
        input: FloatArray
    ): Entry {
        var runner: LiteRtModelRunner? = null
        return try {
            val loadStart = System.nanoTime()
            runner = LiteRtModelRunner.fromAssets(context, ASSET_NAME, accelerator)
            val loadMs = elapsedMs(loadStart)

            runner.writeInput(input)
            val warmupStart = System.nanoTime()
            runner.invoke()
            val warmupMs = elapsedMs(warmupStart)

            if (accelerator == Accelerator.CPU && warmupMs >= SLOW_CPU_WARMUP_MS) {
                return Entry(
                    accelerator = accelerator,
                    loadMs = loadMs,
                    medianWindowMs = warmupMs,
                    modelOnlyFps = TEMPORAL_WINDOW * 1000.0 / warmupMs,
                    samples = 1
                )
            }

            val runCount = if (accelerator == Accelerator.GPU) GPU_TIMED_RUNS else CPU_TIMED_RUNS
            val timings = DoubleArray(runCount)
            repeat(runCount) { index ->
                val started = System.nanoTime()
                runner.invoke()
                timings[index] = elapsedMs(started)
            }
            timings.sort()
            val median = if (timings.size % 2 == 1) {
                timings[timings.size / 2]
            } else {
                (timings[timings.size / 2 - 1] + timings[timings.size / 2]) / 2.0
            }
            val fps = TEMPORAL_WINDOW * 1000.0 / median
            Entry(
                accelerator = accelerator,
                loadMs = loadMs,
                medianWindowMs = median,
                modelOnlyFps = fps,
                samples = runCount
            )
        } catch (failure: Throwable) {
            Entry(
                accelerator = accelerator,
                error = compactError(failure)
            )
        } finally {
            runCatching { runner?.close() }
        }
    }

    private fun elapsedMs(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / 1_000_000.0

    private fun compactError(error: Throwable): String {
        val message = error.message?.replace('\n', ' ')?.trim().orEmpty()
        return if (message.isNotEmpty()) message else error.javaClass.simpleName
    }
}
