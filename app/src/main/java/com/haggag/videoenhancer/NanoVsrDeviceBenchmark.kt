package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import java.util.Locale

/**
 * Second-stage NanoVSR GPU feasibility matrix.
 *
 * The first device matrix proved two independent LiteRT GPU blockers on the
 * tested Snapdragon 680 / Adreno 610 phone: DepthToSpace (PixelShuffle) and
 * rank-5 temporal gather. This matrix removes both blockers independently and
 * asks a narrower question: can the genuine NanoVSR low-resolution recurrent
 * brain run fast on GPU if temporal frames are exposed as five ordinary 4D
 * inputs, and which GPU-friendly high-resolution head family is viable?
 */
object NanoVsrDeviceBenchmark {

    private data class ProbeSpec(
        val label: String,
        val assetName: String,
        val inputElementsPerBuffer: Int,
        val inputCount: Int = 1
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
            append("NanoVSR GPU rewrite matrix • LiteRT CompiledModel\n")
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
            append("\n\nDecision guide:\n")
            append("• 4D core PASS => keep NanoVSR temporal recurrence; replace only the HR head.\n")
            append("• 4D core FAIL at every size => abandon LiteRT NanoVSR on this GPU.\n")
            append("• TransposeConv PASS => learned x2 head can avoid PixelShuffle.\n")
            append("• Resize+Conv PASS => retrain/distill a GPU-safe residual head.\n")
            append("• Small core PASS + large core FAIL => tile the LR temporal core, not HR output.\n")
            append("This probe does not rerun the known-slow CPU path.")
        }
    }

    private val probes = listOf(
        ProbeSpec(
            "TransposeConv x2 candidate head (4D)",
            "probe_transposeconv_x2.tflite",
            1 * 3 * 32 * 32
        ),
        ProbeSpec(
            "Resize x2 + Conv + PReLU candidate head (4D)",
            "probe_resizeconv_head.tflite",
            1 * 32 * 32 * 32
        ),
        ProbeSpec(
            "NanoVSR genuine temporal core • five 4D inputs • 32x32",
            "nanovsr_core4d_t5_32x32.tflite",
            1 * 3 * 32 * 32,
            inputCount = 5
        ),
        ProbeSpec(
            "NanoVSR genuine temporal core • five 4D inputs • 64x64",
            "nanovsr_core4d_t5_64x64.tflite",
            1 * 3 * 64 * 64,
            inputCount = 5
        ),
        ProbeSpec(
            "NanoVSR genuine temporal core • five 4D inputs • 96x160",
            "nanovsr_core4d_t5_96x160.tflite",
            1 * 3 * 96 * 160,
            inputCount = 5
        ),
        ProbeSpec(
            "NanoVSR genuine temporal core • five 4D inputs • 180x320",
            "nanovsr_core4d_t5_180x320.tflite",
            1 * 3 * 180 * 320,
            inputCount = 5
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
        require(assetsAvailable(context)) { "GPU rewrite probe assets are not packaged in this build" }
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
