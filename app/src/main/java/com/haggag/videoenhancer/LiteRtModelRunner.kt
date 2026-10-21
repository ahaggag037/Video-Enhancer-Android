/*
 * Portions adapted from Google AI Edge LiteRT samples.
 * Copyright 2026 The Google AI Edge Authors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.haggag.videoenhancer

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer

/** Owns a compiled LiteRT model and reusable input/output tensor buffers. */
internal class LiteRtModelRunner private constructor(
    private val model: CompiledModel
) : AutoCloseable {

    val inputBuffers: List<TensorBuffer>
    val outputBuffers: List<TensorBuffer>

    init {
        var inputs: List<TensorBuffer>? = null
        try {
            inputs = model.createInputBuffers()
            inputBuffers = inputs
            outputBuffers = model.createOutputBuffers()
        } catch (failure: Throwable) {
            inputs?.forEach { runCatching { it.close() } }
            runCatching { model.close() }
            throw failure
        }
    }

    companion object {
        fun fromAssets(
            context: Context,
            fileName: String,
            accelerator: Accelerator
        ): LiteRtModelRunner {
            val options = CompiledModel.Options(accelerator)
            return LiteRtModelRunner(
                CompiledModel.create(context.assets, fileName, options, null)
            )
        }
    }

    fun run(input: FloatArray): FloatArray {
        inputBuffers[0].writeFloat(input)
        model.run(inputBuffers, outputBuffers)
        return outputBuffers[0].readFloat()
    }

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers.forEach { runCatching { it.close() } }
        model.close()
    }
}
