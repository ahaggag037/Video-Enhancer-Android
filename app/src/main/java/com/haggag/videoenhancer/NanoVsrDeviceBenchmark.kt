package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import java.util.Locale

/**
 * Exact NanoVSR GPU rewrite benchmark.
 *
 * This build keeps the released NanoVSR-226k weights and temporal recurrence but
 * removes the two operators proven incompatible on the test device:
 *  1) rank-5 temporal gather -> five ordinary 4D inputs with an unrolled recurrence;
 *  2) PixelShuffle/DepthToSpace -> exact one-hot deconvolution, then exact
 *     zero-stuff ResizeNearest+Mul+Conv2D lowering.
 *
 * The conversion workflow performs a CPU parity assertion against upstream
 * NanoVSR before this APK is produced. This class only measures real-device GPU
 * compile and execution.
 */
object NanoVsrDeviceBenchmark {

    private data class ProbeSpec(
        val label: String,
        val assetName: String,
        val inputElementsPerBuffer: Int,
        val inputCount: Int = 5,
        val framesPerInvoke: Int = 5
    )

    data class Entry(
        val label: String,
        val compileMs: Double? = null,
        val invokeMs: Double? = null,
        val effectiveFps: Double? = null,
        val error: String? = null
    )

    data class Result(val entries: List<Entry>) {
        val passed: Int get() = entries.count { it.error == null }
        val failed: Int get() = entries.size - passed

        fun format(): String = buildString {
            append("Exact NanoVSR GPU rewrite • LiteRT CompiledModel\n")
            append("PASS $passed / ${entries.size} • FAIL $failed\n\n")
            entries.forEachIndexed { index, entry ->
                if (index > 0) append("\n")
                append(if (entry.error == null) "PASS • " else "FAIL • ")
                append(entry.label)
                if (entry.error == null) {
                    append(String.format(Locale.US, " • compile %.0f ms", entry.compileMs))
                    append(String.format(Locale.US, " • first invoke %.1f ms", entry.invokeMs))
                    append(String.format(Locale.US, " • model-only %.1f fps", entry.effectiveFps))
                } else {
                    append("\n  ")
                    append(entry.error)
                }
            }
            append("\n\nInterpretation:\n")
            append("• PASS means the exact-weight rewrite compiles and executes on this GPU.\n")
            append("• The reported fps is model-only for five frames; decode/compose/encode are not included.\n")
            append("• If 180x320 is too slow but smaller sizes are fast, Smart can run the temporal brain at a smaller LR scale and let the final GPU compositor reach the target resolution.\n")
            append("• If all sizes fail, this LiteRT GPU route is rejected for this device family.")
        }
    }

    private val probes = listOf(
        ProbeSpec(
            "Exact rewrite • 5x32x32 -> 5x128x128",
            "nanovsr_exact4d_t5_32x32.tflite",
            1 * 3 * 32 * 32
        ),
        ProbeSpec(
            "Exact rewrite • 5x64x64 -> 5x256x256",
            "nanovsr_exact4d_t5_64x64.tflite",
            1 * 3 * 64 * 64
        ),
        ProbeSpec(
            "Exact rewrite • 5x96x160 -> 5x384x640",
            "nanovsr_exact4d_t5_96x160.tflite",
            1 * 3 * 96 * 160
        ),
        ProbeSpec(
            "Exact rewrite • 5x180x320 -> 5x720x1280",
            "nanovsr_exact4d_t5_180x320.tflite",
            1 * 3 * 180 * 320
        )
    )

    fun assetsAvailable(context: Context): Boolean = probes.all { spec ->
        runCatching {
            context.assets.open(spec.assetName).use { }
            true
        }.getOrDefault(false)
    }

    /** Blocking. Must run on a worker thread. */
    fun measure(
        context: Context,
        onProgress: (completed: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): Result {
        require(assetsAvailable(context)) { "Exact NanoVSR GPU rewrite assets are not packaged in this build" }
        val appContext = context.applicationContext
        val entries = ArrayList<Entry>(probes.size)

        probes.forEachIndexed { index, spec ->
            onProgress(index, probes.size, spec.label)
            entries += measureGpu(appContext, spec)
            onProgress(index + 1, probes.size, spec.label)
        }
        return Result(entries)
    }

    private fun measureGpu(context: Context, spec: ProbeSpec): Entry {
        var runner: LiteRtModelRunner? = null
        return try {
            val compileStart = System.nanoTime()
            runner = LiteRtModelRunner.fromAssets(context, spec.assetName, Accelerator.GPU)
            val compileMs = elapsedMs(compileStart)

            if (runner.inputBuffers.size != spec.inputCount) {
                error("Expected ${spec.inputCount} model inputs but runtime exposed ${runner.inputBuffers.size}")
            }
            runner.inputBuffers.forEachIndexed { bufferIndex, tensorBuffer ->
                val input = FloatArray(spec.inputElementsPerBuffer) { elementIndex ->
                    (((elementIndex * 37) + (bufferIndex * 17)) % 251) / 250f
                }
                tensorBuffer.writeFloat(input)
            }

            val invokeStart = System.nanoTime()
            runner.invoke()
            // GPU run() may enqueue work; force completion through one output read.
            runner.readOutput()
            val invokeMs = elapsedMs(invokeStart)
            val fps = spec.framesPerInvoke * 1000.0 / invokeMs

            Entry(
                spec.label,
                compileMs = compileMs,
                invokeMs = invokeMs,
                effectiveFps = fps
            )
        } catch (failure: Throwable) {
            Entry(spec.label, error = errorChain(failure))
        } finally {
            runCatching { runner?.close() }
        }
    }

    private fun elapsedMs(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / 1_000_000.0

    private fun errorChain(error: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            val message = current.message?.replace('\n', ' ')?.trim().orEmpty()
            val part = if (message.isEmpty()) {
                current.javaClass.simpleName
            } else {
                "${current.javaClass.simpleName}: $message"
            }
            if (parts.lastOrNull() != part) parts += part
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ").take(700)
    }
}
