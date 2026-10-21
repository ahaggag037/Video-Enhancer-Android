package com.haggag.videoenhancer

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

class AiVideoProcessor(private val context: Context) {

    data class Result(
        val width: Int,
        val height: Int,
        val frames: Int,
        val accelerator: String
    )

    fun process(
        input: File,
        output: File,
        onProgress: (Int) -> Unit = {}
    ): Result {
        require(input.exists()) { "AI input file does not exist" }
        if (output.exists()) output.delete()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var decoderStarted = false
        var encoderStarted = false
        var muxerStarted = false
        val engine = VdsrEngine(context)

        try {
            videoExtractor.setDataSource(input.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: error("No video track found")
            videoExtractor.selectTrack(videoTrack)
            val sourceFormat = videoExtractor.getTrackFormat(videoTrack)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Video MIME type is missing")
            val width = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) {
                sourceFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else 1L
            val frameRate = if (sourceFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
            } else 30

            audioExtractor.setDataSource(input.absolutePath)
            val audioTrack = findTrack(audioExtractor, "audio/")
            val audioFormat = audioTrack?.let {
                audioExtractor.selectTrack(it)
                audioExtractor.getTrackFormat(it)
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerAudioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
            var muxerVideoTrack = -1

            val decodeFormat = sourceFormat
            decodeFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(decodeFormat, null, null, 0)

            val encodeFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(width, height, frameRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(VIDEO_MIME)
            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            engine.initialize()
            decoder.start()
            decoderStarted = true
            encoder.start()
            encoderStarted = true

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderEosQueued = false
            var encoderDone = false
            var frames = 0
            var lastProgress = -1

            fun drainEncoder(): Boolean {
                var sawEos = false
                while (true) {
                    val index = encoder.dequeueOutputBuffer(encoderInfo, 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return sawEos
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(muxerVideoTrack < 0) { "Encoder format changed twice" }
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val encoded = encoder.getOutputBuffer(index)
                                ?: error("Encoder output buffer unavailable")
                            if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encoderInfo.size = 0
                            }
                            if (encoderInfo.size > 0) {
                                check(muxerStarted && muxerVideoTrack >= 0) {
                                    "Muxer was not ready for video output"
                                }
                                encoded.position(encoderInfo.offset)
                                encoded.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encoded, encoderInfo)
                            }
                            if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawEos = true
                            }
                            encoder.releaseOutputBuffer(index, false)
                            if (sawEos) return true
                        }
                    }
                }
            }

            fun obtainEncoderInput(): Int {
                while (true) {
                    val index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) return index
                    if (drainEncoder()) error("Encoder ended before all decoded frames were queued")
                }
            }

            while (!encoderDone) {
                if (!extractorDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Decoder input buffer unavailable")
                        inputBuffer.clear()
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                videoExtractor.sampleTime,
                                videoExtractor.sampleFlags
                            )
                            videoExtractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outputIndex >= 0 -> {
                            val endOfStream =
                                (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (decoderInfo.size > 0) {
                                val sourceImage = decoder.getOutputImage(outputIndex)
                                    ?: error("Decoder did not expose a flexible YUV image")
                                try {
                                    val encoderIndex = obtainEncoderInput()
                                    val targetImage = encoder.getInputImage(encoderIndex)
                                        ?: error("Encoder did not expose a flexible YUV image")
                                    enhanceFrame(sourceImage, targetImage, engine, width, height)
                                    encoder.queueInputBuffer(
                                        encoderIndex,
                                        0,
                                        width * height * 3 / 2,
                                        decoderInfo.presentationTimeUs,
                                        0
                                    )
                                    frames++
                                    val progress = ((decoderInfo.presentationTimeUs * 96L) / durationUs)
                                        .toInt().coerceIn(0, 96)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        onProgress(progress)
                                    }
                                } finally {
                                    sourceImage.close()
                                }
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (endOfStream) {
                                decoderDone = true
                                if (!encoderEosQueued) {
                                    val encoderIndex = obtainEncoderInput()
                                    encoder.queueInputBuffer(
                                        encoderIndex,
                                        0,
                                        0,
                                        decoderInfo.presentationTimeUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    encoderEosQueued = true
                                }
                            }
                        }
                    }
                }

                encoderDone = drainEncoder()
            }

            check(muxerStarted) { "Encoder produced no muxable output" }
            if (audioTrack != null && muxerAudioTrack >= 0) {
                copyAudio(audioExtractor, muxer, muxerAudioTrack)
            }
            onProgress(100)

            return Result(width, height, frames, engine.acceleratorName)
        } finally {
            runCatching { engine.close() }
            if (decoderStarted) runCatching { decoder?.stop() }
            if (encoderStarted) runCatching { encoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (output.length() == 0L) output.delete()
        }
    }

    private fun enhanceFrame(
        source: Image,
        target: Image,
        engine: VdsrEngine,
        width: Int,
        height: Int
    ) {
        require(source.planes.size >= 3 && target.planes.size >= 3)
        val sourceCrop = source.cropRect
        val targetCrop = target.cropRect
        val frameWidth = minOf(width, sourceCrop.width(), targetCrop.width())
        val frameHeight = minOf(height, sourceCrop.height(), targetCrop.height())

        val luma = readPlaneAsFloats(
            source.planes[0],
            frameWidth,
            frameHeight,
            sourceCrop.left,
            sourceCrop.top
        )
        val enhanced = engine.enhanceLuma(luma, frameWidth, frameHeight)
        writeFloatsToPlane(
            target.planes[0],
            enhanced,
            frameWidth,
            frameHeight,
            targetCrop.left,
            targetCrop.top
        )

        val chromaWidth = (frameWidth + 1) / 2
        val chromaHeight = (frameHeight + 1) / 2
        for (planeIndex in 1..2) {
            copyPlane(
                source = source.planes[planeIndex],
                target = target.planes[planeIndex],
                width = chromaWidth,
                height = chromaHeight,
                sourceLeft = sourceCrop.left / 2,
                sourceTop = sourceCrop.top / 2,
                targetLeft = targetCrop.left / 2,
                targetTop = targetCrop.top / 2
            )
        }
    }

    private fun readPlaneAsFloats(
        plane: Image.Plane,
        width: Int,
        height: Int,
        left: Int,
        top: Int
    ): FloatArray {
        val buffer = plane.buffer
        val base = buffer.position()
        val result = FloatArray(width * height)
        for (y in 0 until height) {
            val row = base + (top + y) * plane.rowStride
            for (x in 0 until width) {
                val index = row + (left + x) * plane.pixelStride
                result[y * width + x] = (buffer.get(index).toInt() and 0xFF) / 255f
            }
        }
        return result
    }

    private fun writeFloatsToPlane(
        plane: Image.Plane,
        values: FloatArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int
    ) {
        val buffer = plane.buffer
        val base = buffer.position()
        for (y in 0 until height) {
            val row = base + (top + y) * plane.rowStride
            for (x in 0 until width) {
                val value = (values[y * width + x].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                buffer.put(row + (left + x) * plane.pixelStride, value.toByte())
            }
        }
    }

    private fun copyPlane(
        source: Image.Plane,
        target: Image.Plane,
        width: Int,
        height: Int,
        sourceLeft: Int,
        sourceTop: Int,
        targetLeft: Int,
        targetTop: Int
    ) {
        val sourceBuffer = source.buffer
        val targetBuffer = target.buffer
        val sourceBase = sourceBuffer.position()
        val targetBase = targetBuffer.position()
        for (y in 0 until height) {
            val sourceRow = sourceBase + (sourceTop + y) * source.rowStride
            val targetRow = targetBase + (targetTop + y) * target.rowStride
            for (x in 0 until width) {
                val value = sourceBuffer.get(sourceRow + (sourceLeft + x) * source.pixelStride)
                targetBuffer.put(targetRow + (targetLeft + x) * target.pixelStride, value)
            }
        }
    }

    private fun copyAudio(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val format = extractor.getTrackFormat(extractor.sampleTrackIndex)
        val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            max(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 256 * 1024)
        } else 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(capacity)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return index
        }
        return null
    }

    private fun videoBitrate(width: Int, height: Int, frameRate: Int): Int {
        val estimate = width.toLong() * height.toLong() * frameRate.toLong() / 4L
        return estimate.coerceIn(3_000_000L, 24_000_000L).toInt()
    }

    companion object {
        private const val VIDEO_MIME = "video/avc"
        private const val CODEC_TIMEOUT_US = 10_000L
    }
}
