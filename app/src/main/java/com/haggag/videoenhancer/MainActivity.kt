package com.haggag.videoenhancer

import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.LanczosResample
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var selectButton: Button
    private lateinit var enhanceButton: Button
    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var engineSpinner: Spinner
    private lateinit var presetSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var brightnessSeek: SeekBar
    private lateinit var contrastSeek: SeekBar
    private lateinit var saturationSeek: SeekBar
    private lateinit var brightnessLabel: TextView
    private lateinit var contrastLabel: TextView
    private lateinit var saturationLabel: TextView

    private var selectedUri: Uri? = null
    private var currentTransformer: Transformer? = null
    private var exporting = false
    private val tempFiles = Collections.synchronizedSet(mutableSetOf<File>())

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers only grant access for the current activity lifetime.
            }
            fileText.text = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Selected video"
            enhanceButton.isEnabled = true
            statusText.text = "Video ready"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupSpinners()
        setupSliders()

        selectButton.setOnClickListener {
            if (!exporting) openVideo.launch(arrayOf("video/*"))
        }

        enhanceButton.setOnClickListener {
            if (!exporting) startEnhancement()
        }
    }

    private fun bindViews() {
        selectButton = findViewById(R.id.selectButton)
        enhanceButton = findViewById(R.id.enhanceButton)
        fileText = findViewById(R.id.fileText)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        engineSpinner = findViewById(R.id.engineSpinner)
        presetSpinner = findViewById(R.id.presetSpinner)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        contrastSeek = findViewById(R.id.contrastSeek)
        saturationSeek = findViewById(R.id.saturationSeek)
        brightnessLabel = findViewById(R.id.brightnessLabel)
        contrastLabel = findViewById(R.id.contrastLabel)
        saturationLabel = findViewById(R.id.saturationLabel)
    }

    private fun setupSpinners() {
        val engines = listOf(
            "Auto • AI when useful",
            "Local AI • VDSR",
            "Fast GPU • no AI"
        )
        engineSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engines)
        engineSpinner.setSelection(0)

        val presets = listOf("Natural", "Clean", "Vivid", "Maximum")
        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
        presetSpinner.setSelection(1)
        presetSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            when (position) {
                0 -> applyPreset(50, 54, 54)
                1 -> applyPreset(51, 58, 56)
                2 -> applyPreset(51, 63, 68)
                3 -> applyPreset(52, 67, 74)
            }
        }

        val resolutions = listOf("Original", "Full HD 1080p", "QHD 1440p", "4K 2160p")
        resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        resolutionSpinner.setSelection(1)
    }

    private fun setupSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                updateSliderLabels()

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        brightnessSeek.setOnSeekBarChangeListener(listener)
        contrastSeek.setOnSeekBarChangeListener(listener)
        saturationSeek.setOnSeekBarChangeListener(listener)
        updateSliderLabels()
    }

    private fun applyPreset(brightness: Int, contrast: Int, saturation: Int) {
        brightnessSeek.progress = brightness
        contrastSeek.progress = contrast
        saturationSeek.progress = saturation
        updateSliderLabels()
    }

    private fun updateSliderLabels() {
        brightnessLabel.text = "Brightness  ${signed(brightnessValue())}"
        contrastLabel.text = "Contrast  ${signed(contrastValue())}"
        saturationLabel.text = "Saturation  ${signed(saturationValue() / 100f)}"
    }

    private fun brightnessValue(): Float = (brightnessSeek.progress - 50) / 250f
    private fun contrastValue(): Float = (contrastSeek.progress - 50) / 125f
    private fun saturationValue(): Float = (saturationSeek.progress - 50) * 0.6f

    private fun signed(value: Float): String = String.format(Locale.US, "%+.2f", value)

    private fun startEnhancement() {
        val input = selectedUri ?: return
        val sourceSize = runCatching { readDisplaySize(input) }.getOrElse { error ->
            statusText.text = "Could not read video size: ${error.message ?: "unknown error"}"
            return
        }

        val target = selectedResolutionTarget()
        val requestedEngine = selectedEngineMode()
        val plan = runCatching {
            EnhancementPlanner.plan(
                sourceWidth = sourceSize.first,
                sourceHeight = sourceSize.second,
                target = target,
                requestedEngine = requestedEngine
            )
        }.getOrElse { error ->
            statusText.text = "Could not plan enhancement: ${error.message ?: "unknown error"}"
            return
        }

        setExporting(true)
        progressBar.progress = 1

        if (plan.useAi) {
            startAiPipeline(input, plan)
        } else {
            startFastPipeline(input, plan, target)
        }
    }

    private fun startFastPipeline(input: Uri, plan: EnhancementPlan, target: ResolutionTarget) {
        statusText.text = "Fast GPU enhancement…"
        progressBar.progress = 12
        val output = createTempFile("fast")
        val scale = target != ResolutionTarget.ORIGINAL
        startTransformer(
            input = input,
            output = output,
            targetWidth = if (scale) plan.outputWidth else null,
            targetHeight = if (scale) plan.outputHeight else null,
            applyTone = true,
            onCompleted = { result ->
                progressBar.progress = 96
                saveToGalleryAsync(
                    source = output,
                    width = result.width,
                    height = result.height,
                    note = "Fast GPU"
                )
            }
        )
    }

    private fun startAiPipeline(input: Uri, plan: EnhancementPlan) {
        statusText.text = "Preparing frames for local AI…"
        progressBar.progress = 4
        val prepared = createTempFile("ai_prepared")

        startTransformer(
            input = input,
            output = prepared,
            targetWidth = plan.aiWidth,
            targetHeight = plan.aiHeight,
            applyTone = true,
            onCompleted = {
                progressBar.progress = 8
                statusText.text = "Running VDSR neural detail recovery…"
                runAiStage(prepared, plan)
            }
        )
    }

    private fun runAiStage(prepared: File, plan: EnhancementPlan) {
        val aiOutput = createTempFile("ai_vdsr")
        Thread {
            try {
                val result = AiVideoProcessor(applicationContext).process(prepared, aiOutput) { aiProgress ->
                    val mapped = 8 + (aiProgress * 84 / 100)
                    runOnUiThread {
                        progressBar.progress = mapped.coerceIn(8, 92)
                        statusText.text = "AI VDSR ${aiProgress.coerceIn(0, 100)}%"
                    }
                }
                deleteTemp(prepared)
                runOnUiThread {
                    val needsFinalScale =
                        result.width != plan.outputWidth || result.height != plan.outputHeight
                    if (needsFinalScale) {
                        statusText.text = "Final GPU scale… AI used ${result.accelerator}"
                        progressBar.progress = 94
                        val finalOutput = createTempFile("ai_final")
                        startTransformer(
                            input = Uri.fromFile(aiOutput),
                            output = finalOutput,
                            targetWidth = plan.outputWidth,
                            targetHeight = plan.outputHeight,
                            applyTone = false,
                            onCompleted = { exportResult ->
                                deleteTemp(aiOutput)
                                progressBar.progress = 98
                                saveToGalleryAsync(
                                    source = finalOutput,
                                    width = exportResult.width,
                                    height = exportResult.height,
                                    note = "AI VDSR • ${result.accelerator} • ${result.frames} frames"
                                )
                            }
                        )
                    } else {
                        progressBar.progress = 98
                        saveToGalleryAsync(
                            source = aiOutput,
                            width = result.width,
                            height = result.height,
                            note = "AI VDSR • ${result.accelerator} • ${result.frames} frames"
                        )
                    }
                }
            } catch (error: Throwable) {
                deleteTemp(aiOutput)
                runOnUiThread {
                    statusText.text = "AI unavailable on this codec; finishing with GPU fallback…"
                    progressBar.progress = 72
                    val fallback = createTempFile("ai_fallback")
                    startTransformer(
                        input = Uri.fromFile(prepared),
                        output = fallback,
                        targetWidth = plan.outputWidth,
                        targetHeight = plan.outputHeight,
                        applyTone = false,
                        onCompleted = { exportResult ->
                            deleteTemp(prepared)
                            saveToGalleryAsync(
                                source = fallback,
                                width = exportResult.width,
                                height = exportResult.height,
                                note = "GPU fallback • AI reason: ${shortReason(error)}"
                            )
                        }
                    )
                }
            }
        }.start()
    }

    private fun startTransformer(
        input: Uri,
        output: File,
        targetWidth: Int?,
        targetHeight: Int?,
        applyTone: Boolean,
        onCompleted: (ExportResult) -> Unit
    ) {
        val videoEffects = mutableListOf<Effect>()
        if (applyTone) {
            val brightness = brightnessValue()
            val contrast = contrastValue()
            val saturation = saturationValue()
            if (brightness != 0f) videoEffects += Brightness(brightness)
            if (contrast != 0f) videoEffects += Contrast(contrast)
            if (saturation != 0f) {
                videoEffects += HslAdjustment.Builder().adjustSaturation(saturation).build()
            }
        }
        if (targetWidth != null && targetHeight != null) {
            videoEffects += LanczosResample.scaleToFitWithFlexibleOrientation(targetWidth, targetHeight)
        }

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                currentTransformer = null
                onCompleted(exportResult)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                currentTransformer = null
                deleteTemp(output)
                failExport(
                    ExportPolicy.formatFailure(
                        errorCodeName = exportException.errorCodeName,
                        message = exportException.message,
                        causeMessage = exportException.cause?.message
                    )
                )
            }
        }

        currentTransformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setMaxDelayBetweenMuxerSamplesMs(ExportPolicy.maxDelayBetweenMuxerSamplesMs)
            .addListener(listener)
            .build()

        currentTransformer?.start(editedMediaItem, output.absolutePath)
    }

    private fun saveToGalleryAsync(source: File, width: Int, height: Int, note: String) {
        Thread {
            try {
                val name = "VideoEnhancer_AI_V2_${timestamp()}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEnhancer")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")

                try {
                    contentResolver.openOutputStream(uri, "w").use { output ->
                        requireNotNull(output) { "Could not open gallery output" }
                        source.inputStream().buffered().use { input -> input.copyTo(output) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    contentResolver.delete(uri, null, null)
                    throw t
                }

                deleteTemp(source)
                runOnUiThread {
                    progressBar.progress = 100
                    setExporting(false)
                    val resolution = if (width > 0 && height > 0) "$width×$height" else "saved"
                    statusText.text = "Saved to Movies/VideoEnhancer • $resolution • $note"
                    Toast.makeText(this, "Enhanced video saved", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setExporting(false)
                    statusText.text = "Video created, but gallery save failed: ${shortReason(t)}"
                }
            }
        }.start()
    }

    private fun failExport(message: String) {
        setExporting(false)
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setExporting(value: Boolean) {
        exporting = value
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) progressBar.progress = 0
        selectButton.isEnabled = !value
        enhanceButton.isEnabled = !value && selectedUri != null
        engineSpinner.isEnabled = !value
        presetSpinner.isEnabled = !value
        resolutionSpinner.isEnabled = !value
        brightnessSeek.isEnabled = !value
        contrastSeek.isEnabled = !value
        saturationSeek.isEnabled = !value
    }

    private fun selectedEngineMode(): EngineMode = when (engineSpinner.selectedItemPosition) {
        1 -> EngineMode.LOCAL_AI_VDSR
        2 -> EngineMode.FAST_GPU
        else -> EngineMode.AUTO
    }

    private fun selectedResolutionTarget(): ResolutionTarget = when (resolutionSpinner.selectedItemPosition) {
        0 -> ResolutionTarget.ORIGINAL
        2 -> ResolutionTarget.QHD
        3 -> ResolutionTarget.UHD
        else -> ResolutionTarget.FHD
    }

    private fun readDisplaySize(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("Missing video width")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("Missing video height")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            return if (rotation == 90 || rotation == 270) height to width else width to height
        } finally {
            retriever.release()
        }
    }

    private fun createTempFile(label: String): File {
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoEnhancer")
        outputDir.mkdirs()
        return File(outputDir, "${label}_${timestamp()}_${System.nanoTime()}.mp4").also {
            tempFiles += it
        }
    }

    private fun deleteTemp(file: File) {
        runCatching { file.delete() }
        tempFiles.remove(file)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun shortReason(error: Throwable): String {
        val text = error.message ?: error.cause?.message ?: error.javaClass.simpleName
        return text.replace('\n', ' ').take(120)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onDestroy() {
        if (isFinishing) {
            currentTransformer?.cancel()
            tempFiles.toList().forEach { runCatching { it.delete() } }
            tempFiles.clear()
        }
        super.onDestroy()
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) = onSelected(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
