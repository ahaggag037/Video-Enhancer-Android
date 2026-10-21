package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

class VdsrEngine(private val context: Context) : AutoCloseable {
    private var runtime: Runtime? = null

    val acceleratorName: String
        get() = runtime?.acceleratorName ?: "not initialized"

    fun initialize() {
        close()
        val gpu = runCatching { createRuntime(Accelerator.GPU, "GPU") }.getOrNull()
        runtime = gpu ?: createRuntime(Accelerator.CPU, "CPU")
    }

    fun enhanceLuma(input: FloatArray, width: Int, height: Int): FloatArray {
        val active = runtime ?: error("VDSR engine is not initialized")
        return LumaTiler.enhance(
            input = input,
            width = width,
            height = height,
            tileSize = MODEL_SIZE,
            overlap = TILE_OVERLAP
        ) { tile -> active.runTile(tile) }
    }

    override fun close() {
        runtime?.close()
        runtime = null
    }

    private fun createRuntime(accelerator: Accelerator, name: String): Runtime {
        val model = CompiledModel.create(
            context.assets,
            MODEL_FILE,
            CompiledModel.Options(accelerator),
            null
        )
        return Runtime(model, name)
    }

    private class Runtime(
        private val model: CompiledModel,
        val acceleratorName: String
    ) : AutoCloseable {
        private val inputBuffers = model.createInputBuffers()
        private val outputBuffers = model.createOutputBuffers()

        fun runTile(tile: FloatArray): FloatArray {
            require(tile.size == MODEL_SIZE * MODEL_SIZE)
            inputBuffers[0].writeFloat(tile)
            model.run(inputBuffers, outputBuffers)
            return outputBuffers[0].readFloat()
        }

        override fun close() {
            inputBuffers.forEach { it.close() }
            outputBuffers.forEach { it.close() }
            model.close()
        }
    }

    companion object {
        const val MODEL_FILE = "vdsr_256_fp16.tflite"
        const val MODEL_SIZE = 256
        const val TILE_OVERLAP = 32
    }
}
