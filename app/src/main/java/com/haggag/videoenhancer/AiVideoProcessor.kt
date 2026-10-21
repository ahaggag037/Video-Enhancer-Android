package com.haggag.videoenhancer

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Frame-streaming AI video exporter.
 *
 * Frames are decoded in small batches, enhanced with Real-ESRGAN, immediately encoded to H.264,
 * then released. The full enhanced video is never retained in memory. Source audio is remuxed
 * without re-encoding when an audio track is available.
 */
class AiVideoProcessor(private val context: Context) {

    enum class Stage { PREPARING, AI_ENHANCING, ENCODING, AUDIO, FINISHING }

    data class Progress(
        val stage: Stage,
        val percent: Int,
        val frame: Int,
        val totalFrames: Int,
        val backend: RealEsrganUpscaler.Backend? = null
    )

    data class SourceInfo(
        val rawWidth: Int,
        val rawHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val rotation: Int,
        val durationMs: Long,
        val frameCount: Int,
        val fps: Double
    )

    data class Result(
        val outputFile: File,
        val backend: RealEsrganUpscaler.Backend,
        val frameCount: Int,
        val fps: Double
    )

    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    companion object {
        private const val FRAME_BATCH = 4
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        fun readSourceInfo(context: Context, uri: Uri): SourceInfo {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.takeIf { it > 0 } ?: error("Video width unavailable")
                val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.takeIf { it > 0 } ?: error("Video height unavailable")
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()?.mod(360) ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val metadataFrameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull()?.takeIf { it > 0 }
                val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                val derivedFps = if (metadataFrameCount != null && durationMs > 0L) {
                    metadataFrameCount.toDouble() * 1_000.0 / durationMs.toDouble()
                } else null
                val fps = (derivedFps ?: captureFps ?: 30.0).coerceIn(1.0, 60.0)
                val frameCount = metadataFrameCount ?: VideoMath.frameCount(durationMs, fps)
                val rotated = rotation == 90 || rotation == 270
                return SourceInfo(
                    rawWidth = rawWidth,
                    rawHeight = rawHeight,
                    displayWidth = if (rotated) rawHeight else rawWidth,
                    displayHeight = if (rotated) rawWidth else rawHeight,
                    rotation = rotation,
                    durationMs = durationMs,
                    frameCount = frameCount,
                    fps = fps
                )
            } finally {
                retriever.release()
            }
        }
    }

    fun process(
        inputUri: Uri,
        outputFile: File,
        plan: EnhancementPlanner.Plan,
        onProgress: (Progress) -> Unit,
        onComplete: (Result) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        check(worker == null) { "AI video processor is already running" }
        cancelled.set(false)
        worker = Thread({
            try {
                val result = processBlocking(inputUri, outputFile, plan, onProgress)
                if (!cancelled.get()) onComplete(result)
            } catch (t: Throwable) {
                outputFile.delete()
                if (!cancelled.get() && t !is CancellationException) onError(t)
            } finally {
                worker = null
            }
        }, "video-enhancer-ai")
        worker?.start()
    }

    fun cancel() {
        cancelled.set(true)
        worker?.interrupt()
    }

    private fun processBlocking(
        inputUri: Uri,
        outputFile: File,
        plan: EnhancementPlanner.Plan,
        onProgress: (Progress) -> Unit
    ): Result {
        onProgress(Progress(Stage.PREPARING, 0, 0, 0))
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val sourceInfo = readSourceInfo(context, inputUri)
        check(sourceInfo.frameCount > 0) { "Could not determine video frame count" }

        val retriever = MediaMetadataRetriever()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var upscaler: RealEsrganUpscaler? = null
        var muxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1

        try {
            checkCancelled()
            retriever.setDataSource(context, inputUri)
            audioExtractor.setDataSource(context, inputUri, null)
            val audioSourceTrack = findAudioTrack(audioExtractor)

            upscaler = RealEsrganUpscaler.create(context)
            onProgress(Progress(Stage.PREPARING, 1, 0, sourceInfo.frameCount, upscaler.backend))

            val encoderInfo = findFlexibleAvcEncoder()
                ?: error("No H.264 encoder with YUV420 flexible input is available on this device")
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                plan.targetWidth,
                plan.targetHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, VideoMath.recommendedBitrate(plan.targetWidth, plan.targetHeight, sourceInfo.fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceInfo.fps.roundToInt().coerceIn(1, 60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createByCodecName(encoderInfo.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (audioSourceTrack >= 0) {
                audioTrackIndex = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var nextFrame = 0
            var sawEncoderEos = false

            fun startMuxerIfReady(outputFormat: MediaFormat) {
                if (videoTrackIndex >= 0) return
                videoTrackIndex = muxer.addTrack(outputFormat)
                muxer.start()
                muxerStarted = true
            }

            fun drainEncoder(waitForEos: Boolean) {
                var idleRounds = 0
                while (true) {
                    checkCancelled()
                    val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, if (waitForEos) DEQUEUE_TIMEOUT_US else 0L)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!waitForEos) return
                            idleRounds++
                            if (idleRounds > 500) error("Timed out while finalizing H.264 encoder")
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            startMuxerIfReady(encoder.outputFormat)
                            idleRounds = 0
                        }
                        outputIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outputIndex)
                                ?: error("Encoder returned a null output buffer")
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                check(muxerStarted && videoTrackIndex >= 0) { "Muxer not started before encoded sample" }
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encoded, bufferInfo)
                            }
                            sawEncoderEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                            idleRounds = 0
                            if (sawEncoderEos) return
                        }
                    }
                }
            }

            while (nextFrame < sourceInfo.frameCount) {
                checkCancelled()
                val count = minOf(FRAME_BATCH, sourceInfo.frameCount - nextFrame)
                val frames = getFrameBatch(retriever, sourceInfo, nextFrame, count)
                check(frames.isNotEmpty()) { "Decoder returned no frames at index $nextFrame" }

                for ((batchOffset, sourceBitmap) in frames.withIndex()) {
                    checkCancelled()
                    val frameIndex = nextFrame + batchOffset
                    if (frameIndex >= sourceInfo.frameCount) {
                        sourceBitmap.recycle()
                        continue
                    }

                    val oriented = orientFrameIfNeeded(sourceBitmap, sourceInfo)
                    val enhanced = try {
                        upscaler.upscale(
                            oriented,
                            plan.targetWidth,
                            plan.targetHeight
                        ) { tileFraction ->
                            checkCancelled()
                            val overall = ((frameIndex + tileFraction) / sourceInfo.frameCount.toFloat() * 90f)
                                .roundToInt().coerceIn(1, 90)
                            onProgress(
                                Progress(
                                    Stage.AI_ENHANCING,
                                    overall,
                                    frameIndex,
                                    sourceInfo.frameCount,
                                    upscaler.backend
                                )
                            )
                        }
                    } finally {
                        if (oriented !== sourceBitmap && !oriented.isRecycled) oriented.recycle()
                        if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
                    }

                    try {
                        queueBitmap(
                            encoder,
                            enhanced,
                            VideoMath.presentationTimeUs(frameIndex, sourceInfo.fps)
                        )
                        drainEncoder(waitForEos = false)
                        onProgress(
                            Progress(
                                Stage.ENCODING,
                                ((frameIndex + 1).toFloat() / sourceInfo.frameCount * 92f)
                                    .roundToInt().coerceIn(1, 92),
                                frameIndex + 1,
                                sourceInfo.frameCount,
                                upscaler.backend
                            )
                        )
                    } finally {
                        if (!enhanced.isRecycled) enhanced.recycle()
                    }
                }
                nextFrame += frames.size
            }

            val eosInput = dequeueInputOrThrow(encoder)
            val eosPts = VideoMath.presentationTimeUs(sourceInfo.frameCount, sourceInfo.fps)
            encoder.queueInputBuffer(eosInput, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(waitForEos = true)
            check(sawEncoderEos) { "Encoder did not emit end-of-stream" }

            if (audioSourceTrack >= 0 && audioTrackIndex >= 0 && muxerStarted) {
                onProgress(Progress(Stage.AUDIO, 94, sourceInfo.frameCount, sourceInfo.frameCount, upscaler.backend))
                copyAudioTrack(audioExtractor, audioSourceTrack, muxer, audioTrackIndex)
            }

            onProgress(Progress(Stage.FINISHING, 99, sourceInfo.frameCount, sourceInfo.frameCount, upscaler.backend))
            return Result(outputFile, upscaler.backend, sourceInfo.frameCount, sourceInfo.fps)
        } finally {
            runCatching { retriever.release() }
            runCatching { audioExtractor.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { upscaler?.close() }
        }
    }

    private fun getFrameBatch(
        retriever: MediaMetadataRetriever,
        info: SourceInfo,
        start: Int,
        count: Int
    ): List<Bitmap> {
        if (info.frameCount > 0) {
            val indexed = runCatching { retriever.getFramesAtIndex(start, count) }.getOrNull()
            if (!indexed.isNullOrEmpty()) return indexed
        }
        return (0 until count).mapNotNull { offset ->
            val frameIndex = start + offset
            retriever.getFrameAtTime(
                VideoMath.presentationTimeUs(frameIndex, info.fps),
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        }
    }

    private fun orientFrameIfNeeded(bitmap: Bitmap, info: SourceInfo): Bitmap {
        val needsQuarterTurn = (info.rotation == 90 || info.rotation == 270) &&
            bitmap.width == info.rawWidth && bitmap.height == info.rawHeight
        if (!needsQuarterTurn) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(info.rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun findFlexibleAvcEncoder(): MediaCodecInfo? {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            if (!info.isEncoder) return@firstOrNull false
            val types = info.supportedTypes
            if (!types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) {
                return@firstOrNull false
            }
            runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }.getOrDefault(false)
        }
    }

    private fun queueBitmap(encoder: MediaCodec, bitmap: Bitmap, presentationTimeUs: Long) {
        val inputIndex = dequeueInputOrThrow(encoder)
        val image = encoder.getInputImage(inputIndex)
            ?: error("H.264 encoder did not expose flexible YUV input image")
        try {
            writeBitmapToYuv420(bitmap, image)
        } finally {
            image.close()
        }
        val size = bitmap.width * bitmap.height * 3 / 2
        encoder.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
    }

    private fun dequeueInputOrThrow(encoder: MediaCodec): Int {
        repeat(500) {
            checkCancelled()
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) return index
        }
        error("Timed out waiting for H.264 encoder input")
    }

    private fun writeBitmapToYuv420(bitmap: Bitmap, image: Image) {
        require(image.planes.size >= 3) { "Encoder input is not YUV420" }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        writePlane(image.planes[0], width, height) { x, y ->
            val color = pixels[y * width + x]
            rgbToY(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        }
        writePlane(image.planes[1], width / 2, height / 2) { x, y ->
            val color = pixels[(y * 2) * width + (x * 2)]
            rgbToU(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        }
        writePlane(image.planes[2], width / 2, height / 2) { x, y ->
            val color = pixels[(y * 2) * width + (x * 2)]
            rgbToV(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        }
    }

    private inline fun writePlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        value: (Int, Int) -> Int
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                if (offset < buffer.limit()) {
                    buffer.put(offset, value(x, y).coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun rgbToY(r: Int, g: Int, b: Int): Int =
        (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)

    private fun rgbToU(r: Int, g: Int, b: Int): Int =
        (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)

    private fun rgbToV(r: Int, g: Int, b: Int): Int =
        (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun copyAudioTrack(
        extractor: MediaExtractor,
        sourceTrack: Int,
        muxer: MediaMuxer,
        destinationTrack: Int
    ) {
        extractor.selectTrack(sourceTrack)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val format = extractor.getTrackFormat(sourceTrack)
        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 1_048_576
        val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(64 * 1024))
        val info = MediaCodec.BufferInfo()
        while (true) {
            checkCancelled()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
            muxer.writeSampleData(destinationTrack, buffer, info)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(sourceTrack)
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("AI enhancement cancelled")
        }
    }
}
