package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import java.util.Locale

/**
 * GPU isolation matrix for NanoVSR feasibility.
 *
 * The first real-device run proved that the full 5x180x320 NanoVSR graph cannot
 * compile on the tested phone GPU while CPU inference is far too slow. This
 * probe intentionally avoids any product-path changes and isolates the failure
 * to individual operator families, rank-5 temporal indexing, or graph/shape size.
 */
object NanoVsrDeviceBenchmark {

    private data class ProbeSpec(
        val label: String,
        val assetName: String,
        val inputElements: Int
    )

    data class Entry(
        val label: String,
        val compileMs: Double? = null,
        val invokeMs: Double? = null,
        val error: String? = null
    )

    data class Result(val entries: List<Entry>) {
        val passed: Int get() = entries.count { it.error == null }
        val failed: Int get() = entries.size - passed

        fun format(): String = buildString {
            append("GPU isolation matrix • LiteRT CompiledModel\n")
            append("PASS $passed / ${entries.size} • FAIL $failed\n\n")
            entries.forEachIndexed { index, entry ->
                if (index > 0) append("\n")
                append(if (entry.error == null) "PASS • " else "FAIL • ")
                append(entry.label)
                if (entry.error == null) {
                    append(String.format(Locale.US, " • compile %.0f ms", entry.compileMs))
                    append(String.format(Locale.US, " • first invoke %.1f ms", entry.invokeMs))
                } else {
                    append("\n  ")
                    append(entry.error)
                }
            }
            append("\n\nInterpretation:\n")
            append("• Primitive FAIL => unsupported GPU operator/runtime path.\n")
            append("• Primitives PASS + Rank5 FAIL => temporal tensor/indexing limitation.\n")
            append("• Small NanoVSR PASS + large FAIL => shape/resource threshold.\n")
            append("• All small probes PASS + all NanoVSR FAIL => composite graph/backend limitation.\n")
            append("CPU is not rerun here; the previous real-device result was ~22.48 s per 5-frame window (0.2 fps).")
        }
    }

    private val probes = listOf(
        ProbeSpec("Conv + LeakyReLU (4D)", "probe_conv_leaky.tflite", 1 * 3 * 64 * 64),
        ProbeSpec("PReLU (4D)", "probe_prelu.tflite", 1 * 3 * 64 * 64),
        ProbeSpec("PixelShuffle x2 / DepthToSpace (4D)", "probe_pixelshuffle.tflite", 1 * 3 * 64 * 64),
        ProbeSpec("Bilinear Resize x4 (4D)", "probe_resize.tflite", 1 * 3 * 32 * 32),
        ProbeSpec("Concat + 1x1 Conv (4D)", "probe_concat.tflite", 1 * 3 * 64 * 64),
        ProbeSpec("Rank-5 temporal gather + fusion", "probe_rank5_gather.tflite", 1 * 5 * 3 * 32 * 32),
        ProbeSpec("NanoVSR full graph • 5x32x32", "nanovsr_226k_t5_32x32.tflite", 1 * 5 * 3 * 32 * 32),
        ProbeSpec("NanoVSR full graph • 5x64x64", "nanovsr_226k_t5_64x64.tflite", 1 * 5 * 3 * 64 * 64),
        ProbeSpec("NanoVSR full graph • 5x96x160", "nanovsr_226k_t5_96x160.tflite", 1 * 5 * 3 * 96 * 160),
        ProbeSpec("NanoVSR full graph • 5x180x320", "nanovsr_226k_t5_180x320.tflite", 1 * 5 * 3 * 180 * 320)
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
        require(assetsAvailable(context)) { "GPU isolation assets are not packaged in this build" }
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

            val input = FloatArray(spec.inputElements) { index -> ((index * 37) % 251) / 250f }
            runner.writeInput(input)
            val invokeStart = System.nanoTime()
            runner.invoke()
            val invokeMs = elapsedMs(invokeStart)

            Entry(spec.label, compileMs = compileMs, invokeMs = invokeMs)
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
