package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import java.util.Locale

/**
 * NanoVSR temporal-core benchmark.
 *
 * The exact full-model rewrite proved GPU compatibility but also proved that the
 * learned x4 reconstruction head is too expensive on the target Adreno device.
 * This gate measures where the cost lives by removing the HR head entirely:
 *
 *  1) bidirectional 5-frame temporal core -> center-frame fused LR features;
 *  2) streaming forward core -> one LR frame + hidden state -> next hidden state.
 *
 * No decode/encode/compositor work is included. These are architecture probes,
 * not product-quality output models.
 */
object NanoVsrDeviceBenchmark {

    private data class ProbeSpec(
        val label: String,
        val assetName: String,
        val inputElements: List<Int>,
        val usefulFramesPerInvoke: Int
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
            append("NanoVSR LR temporal-core benchmark • LiteRT GPU\n")
            append("PASS $passed / ${entries.size} • FAIL $failed\n\n")
            entries.forEachIndexed { index, entry ->
                if (index > 0) append("\n")
                append(if (entry.error == null) "PASS • " else "FAIL • ")
                append(entry.label)
                if (entry.error == null) {
                    append(String.format(Locale.US, " • compile %.0f ms", entry.compileMs))
                    append(String.format(Locale.US, " • invoke %.1f ms", entry.invokeMs))
                    append(String.format(Locale.US, " • %.1f useful fps", entry.effectiveFps))
                } else {
                    append("\n  ")
                    append(entry.error)
                }
            }
            append("\n\nInterpretation:\n")
            append("• These probes REMOVE the neural x4 HR head; they measure only the temporal brain at LR.\n")
            append("• Bidir consumes five frames to produce one center-frame LR feature map; useful fps counts one output frame.\n")
            append("• Streaming consumes one frame plus the previous hidden state and produces the next hidden state; useful fps is true per-frame core throughput.\n")
            append("• If streaming 180x320 is practical, V3 should keep AI at LR and let a fused GPU compositor perform final reconstruction/upscale.\n")
            append("• If only 96x160 is practical, Smart should downscale the temporal brain and reserve HR work for deterministic GPU reconstruction.")
        }
    }

    private fun frameElements(h: Int, w: Int) = 3 * h * w
    private fun stateElements(h: Int, w: Int) = 32 * h * w

    private val probes = listOf(
        ProbeSpec(
            label = "Bidir core • 5x96x160 -> center 32ch LR features",
            assetName = "nanovsr_core_bidir_t5_96x160.tflite",
            inputElements = List(5) { frameElements(96, 160) },
            usefulFramesPerInvoke = 1
        ),
        ProbeSpec(
            label = "Bidir core • 5x180x320 -> center 32ch LR features",
            assetName = "nanovsr_core_bidir_t5_180x320.tflite",
            inputElements = List(5) { frameElements(180, 320) },
            usefulFramesPerInvoke = 1
        ),
        ProbeSpec(
            label = "Streaming forward core • 96x160 + state",
            assetName = "nanovsr_core_stream_96x160.tflite",
            inputElements = listOf(frameElements(96, 160), stateElements(96, 160)),
            usefulFramesPerInvoke = 1
        ),
        ProbeSpec(
            label = "Streaming forward core • 180x320 + state",
            assetName = "nanovsr_core_stream_180x320.tflite",
            inputElements = listOf(frameElements(180, 320), stateElements(180, 320)),
            usefulFramesPerInvoke = 1
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
        require(assetsAvailable(context)) { "NanoVSR temporal-core assets are not packaged in this build" }
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

            if (runner.inputBuffers.size != spec.inputElements.size) {
                error("Expected ${spec.inputElements.size} model inputs but runtime exposed ${runner.inputBuffers.size}")
            }

            runner.inputBuffers.forEachIndexed { bufferIndex, tensorBuffer ->
                val count = spec.inputElements[bufferIndex]
                val input = FloatArray(count) { elementIndex ->
                    (((elementIndex * 37) + (bufferIndex * 17)) % 251) / 250f
                }
                tensorBuffer.writeFloat(input)
            }

            // Warm once so compile/lazy shader creation does not dominate the timing.
            runner.invoke()
            runner.readOutput()

            val samples = DoubleArray(3)
            repeat(samples.size) { sampleIndex ->
                val start = System.nanoTime()
                runner.invoke()
                runner.readOutput() // force GPU completion
                samples[sampleIndex] = elapsedMs(start)
            }
            samples.sort()
            val medianMs = samples[1]
            val fps = spec.usefulFramesPerInvoke * 1000.0 / medianMs

            Entry(
                label = spec.label,
                compileMs = compileMs,
                invokeMs = medianMs,
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
            val part = if (message.isEmpty()) current.javaClass.simpleName
            else "${current.javaClass.simpleName}: $message"
            if (parts.lastOrNull() != part) parts += part
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ").take(700)
    }
}
