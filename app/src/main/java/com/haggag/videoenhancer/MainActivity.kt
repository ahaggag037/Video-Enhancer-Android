package com.haggag.videoenhancer

import android.content.ContentValues
import android.content.Intent
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
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var selectButton: Button
    private lateinit var enhanceButton: Button
    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
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
    private var selectedSourceInfo: AiVideoProcessor.SourceInfo? = null
    private var currentTransformer: Transformer? = null
    private var currentAiProcessor: AiVideoProcessor? = null
    private var currentTempFile: File? = null
    private var exporting = false
    private var neuralBenchmarkRunning = false
    private var neuralProfile: NeuralDeviceBenchmark.Profile? = null

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedSourceInfo = null
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers do not offer persistable permissions; the current grant is still usable.
            }
            fileText.text = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Selected video"
            enhanceButton.isEnabled = false
            statusText.text = "Analyzing video…"
            analyzeVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupSpinners()
        setupSliders()
        neuralProfile = NeuralDeviceBenchmark.load(this)

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
        progressText = findViewById(R.id.progressText)
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
            "Smart AI (Recommended)",
            "AI Detail — Real-ESRGAN (slow / short clips)",
            "Fast GPU"
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
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateSliderLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        brightnessSeek.setOnSeekBarChangeListener(listener)
        contrastSeek.setOnSeekBarChangeListener(listener)
        saturationSeek.setOnSeekBarChangeListener(listener)
        updateSliderLabels()
    }

    private fun analyzeVideo(uri: Uri) {
        Thread({
            val result = runCatching { AiVideoProcessor.readSourceInfo(this, uri) }
            runOnUiThread {
                if (selectedUri != uri || isFinishing) return@runOnUiThread
                result.onSuccess { info ->
                    selectedSourceInfo = info
                    statusText.text = readySummary(info)
                    maybeCalibrateNeuralPerformance(uri, info)
                }.onFailure { error ->
                    selectedSourceInfo = null
                    enhanceButton.isEnabled = false
                    statusText.text = "Could not read video: ${error.message ?: "unsupported file"}"
                }
            }
        }, "video-analyzer").start()
    }

    private fun maybeCalibrateNeuralPerformance(uri: Uri, info: AiVideoProcessor.SourceInfo) {
        val cached = neuralProfile
        if (cached != null) {
            enhanceButton.isEnabled = !exporting
            statusText.text = readySummary(info, cached)
            return
        }

        // Avoid loading a neural model for HD sources where Smart will not use full Real-ESRGAN anyway.
        if (maxOf(info.displayWidth, info.displayHeight) > 1280) {
            enhanceButton.isEnabled = !exporting
            return
        }
        if (neuralBenchmarkRunning) return

        neuralBenchmarkRunning = true
        enhanceButton.isEnabled = false
        statusText.text = "Calibrating AI speed on this phone once…"
        Thread({
            val result = runCatching { NeuralDeviceBenchmark.measure(applicationContext) }
            runOnUiThread {
                neuralBenchmarkRunning = false
                result.onSuccess { profile -> neuralProfile = profile }
                if (selectedUri == uri && !isFinishing) {
                    enhanceButton.isEnabled = !exporting && selectedSourceInfo != null
                    statusText.text = result.fold(
                        onSuccess = { profile -> readySummary(info, profile) },
                        onFailure = { "${readySummary(info)} • AI benchmark unavailable; conservative Smart limits active" }
                    )
                }
            }
        }, "neural-device-benchmark").start()
    }

    private fun readySummary(
        info: AiVideoProcessor.SourceInfo,
        profile: NeuralDeviceBenchmark.Profile? = neuralProfile
    ): String {
        val base = String.format(
            Locale.US,
            "Ready • %d×%d • %.1f fps • %,d frames",
            info.displayWidth,
            info.displayHeight,
            info.fps,
            info.frameCount
        )
        return if (profile != null) {
            String.format(
                Locale.US,
                "%s • AI %.0f ms/tile %s",
                base,
                profile.msPerTile,
                profile.backend.name
            )
        } else {
            base
        }
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

    private fun selectedEngine(): EnhancementPlanner.EngineChoice = when (engineSpinner.selectedItemPosition) {
        1 -> EnhancementPlanner.EngineChoice.AI_DETAIL
        2 -> EnhancementPlanner.EngineChoice.FAST_GPU
        else -> EnhancementPlanner.EngineChoice.SMART
    }

    private fun selectedOutput(): EnhancementPlanner.OutputChoice = when (resolutionSpinner.selectedItemPosition) {
        0 -> EnhancementPlanner.OutputChoice.ORIGINAL
        2 -> EnhancementPlanner.OutputChoice.QHD
        3 -> EnhancementPlanner.OutputChoice.UHD_4K
        else -> EnhancementPlanner.OutputChoice.FULL_HD
    }

    private fun startEnhancement() {
        if (neuralBenchmarkRunning && selectedEngine() == EnhancementPlanner.EngineChoice.SMART) {
            statusText.text = "Finishing the one-time AI speed calibration…"
            return
        }

        val input = selectedUri ?: return
        val info = selectedSourceInfo ?: run {
            statusText.text = "Video analysis is not ready yet"
            return
        }

        val plan = EnhancementPlanner.plan(
            sourceWidth = info.displayWidth,
            sourceHeight = info.displayHeight,
            frameCount = info.frameCount,
            outputChoice = selectedOutput(),
            engineChoice = selectedEngine(),
            neuralMsPerTile = neuralProfile?.msPerTile
        )

        setExporting(true)
        when (plan.path) {
            EnhancementPlanner.Path.AI_DETAIL -> startAiEnhancement(input, plan)
            EnhancementPlanner.Path.FAST_GPU -> startFastEnhancement(
                input = input,
                smartFallback = plan.requestedEngine == EnhancementPlanner.EngineChoice.SMART,
                avoidedNeuralMs = plan.estimatedNeuralMs
            )
        }
    }

    private fun startAiEnhancement(input: Uri, plan: EnhancementPlanner.Plan) {
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressText.text = plan.estimatedNeuralMs?.let {
            "0% • AI estimate ${formatDuration(it)} • preparing"
        } ?: "0% • Preparing Real-ESRGAN"
        statusText.text = "Loading genuine AI detail recovery…"

        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoEnhancer")
        outputDir.mkdirs()
        val tempFile = File(outputDir, "ai_enhanced_${timestamp()}.mp4")
        currentTempFile = tempFile

        val processor = AiVideoProcessor(this)
        currentAiProcessor = processor
        processor.process(
            inputUri = input,
            outputFile = tempFile,
            plan = plan,
            onProgress = { progress ->
                runOnUiThread {
                    if (!exporting || currentAiProcessor !== processor) return@runOnUiThread
                    progressBar.isIndeterminate = false
                    progressBar.progress = progress.percent
                    val backend = progress.backend?.name?.let { " • $it" } ?: ""
                    progressText.text = "${progress.percent}% • ${stageLabel(progress.stage)}$backend"
                    statusText.text = when (progress.stage) {
                        AiVideoProcessor.Stage.PREPARING -> "Preparing Real-ESRGAN…"
                        AiVideoProcessor.Stage.AI_ENHANCING -> "Recovering detail with Real-ESRGAN • frame ${progress.frame + 1}/${progress.totalFrames}"
                        AiVideoProcessor.Stage.ENCODING -> "Encoding enhanced video • frame ${progress.frame}/${progress.totalFrames}"
                        AiVideoProcessor.Stage.AUDIO -> "Preserving original audio…"
                        AiVideoProcessor.Stage.FINISHING -> "Finalizing AI-enhanced MP4…"
                    }
                }
            },
            onComplete = { result ->
                runOnUiThread {
                    if (currentAiProcessor !== processor) return@runOnUiThread
                    currentAiProcessor = null
                    progressBar.progress = 100
                    progressText.text = "100% • AI enhancement complete"
                    statusText.text = "AI enhancement complete. Saving to gallery…"
                    saveToGalleryAsync(
                        source = result.outputFile,
                        resolutionLabel = "${plan.targetWidth}×${plan.targetHeight}",
                        engineLabel = "Real-ESRGAN ${result.backend.name}"
                    )
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (currentAiProcessor !== processor) return@runOnUiThread
                    currentAiProcessor = null
                    currentTempFile = null
                    if (plan.requestedEngine == EnhancementPlanner.EngineChoice.SMART && selectedUri == input) {
                        progressBar.isIndeterminate = true
                        progressText.text = "Smart recovery • Fast GPU"
                        statusText.text = "Neural engine unavailable (${compactError(error)}). Switching to accelerated GPU…"
                        startFastEnhancement(input, smartFallback = true, avoidedNeuralMs = plan.estimatedNeuralMs)
                    } else {
                        setExporting(false)
                        val failure = "AI enhancement failed: ${compactError(error)}"
                        statusText.text = failure
                        Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun stageLabel(stage: AiVideoProcessor.Stage): String = when (stage) {
        AiVideoProcessor.Stage.PREPARING -> "Preparing"
        AiVideoProcessor.Stage.AI_ENHANCING -> "AI detail recovery"
        AiVideoProcessor.Stage.ENCODING -> "Encoding"
        AiVideoProcessor.Stage.AUDIO -> "Audio"
        AiVideoProcessor.Stage.FINISHING -> "Finalizing"
    }

    /** Known-good V1 Media3 path retained as the compatibility/speed engine. */
    private fun startFastEnhancement(
        input: Uri,
        smartFallback: Boolean,
        avoidedNeuralMs: Long? = null
    ) {
        progressBar.isIndeterminate = true
        progressText.text = if (smartFallback) {
            avoidedNeuralMs?.let { "Smart speed path • avoided ~${formatDuration(it)} full AI pass" }
                ?: "Smart speed path • Fast GPU"
        } else {
            "Fast GPU export"
        }
        statusText.text = if (smartFallback) {
            avoidedNeuralMs?.let {
                "Smart measured full Real-ESRGAN at about ${formatDuration(it)} and chose the accelerated GPU path…"
            } ?: "Smart chose the accelerated GPU path for this video length…"
        } else {
            "Enhancing with Fast GPU…"
        }

        val videoEffects = mutableListOf<Effect>()
        val brightness = brightnessValue()
        val contrast = contrastValue()
        val saturation = saturationValue()

        if (brightness != 0f) videoEffects += Brightness(brightness)
        if (contrast != 0f) videoEffects += Contrast(contrast)
        if (saturation != 0f) {
            videoEffects += HslAdjustment.Builder().adjustSaturation(saturation).build()
        }

        when (resolutionSpinner.selectedItemPosition) {
            1 -> videoEffects += LanczosResample.scaleToFitWithFlexibleOrientation(1920, 1080)
            2 -> videoEffects += LanczosResample.scaleToFitWithFlexibleOrientation(2560, 1440)
            3 -> videoEffects += LanczosResample.scaleToFitWithFlexibleOrientation(3840, 2160)
        }

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoEnhancer")
        outputDir.mkdirs()
        val tempFile = File(outputDir, "fast_enhanced_${timestamp()}.mp4")
        currentTempFile = tempFile

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                currentTransformer = null
                statusText.text = "Fast enhancement complete. Saving to gallery…"
                val resolution = if (exportResult.width > 0 && exportResult.height > 0) {
                    "${exportResult.width}×${exportResult.height}"
                } else {
                    "device-selected resolution"
                }
                saveToGalleryAsync(
                    tempFile,
                    resolution,
                    if (smartFallback) "Smart accelerated GPU" else "Fast GPU"
                )
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                currentTransformer = null
                tempFile.delete()
                currentTempFile = null
                setExporting(false)
                val failure = ExportPolicy.formatFailure(
                    errorCodeName = exportException.errorCodeName,
                    message = exportException.message,
                    causeMessage = exportException.cause?.message
                )
                statusText.text = failure
                Toast.makeText(this@MainActivity, failure, Toast.LENGTH_LONG).show()
            }
        }

        currentTransformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setMaxDelayBetweenMuxerSamplesMs(ExportPolicy.maxDelayBetweenMuxerSamplesMs)
            .addListener(listener)
            .build()

        currentTransformer?.start(editedMediaItem, tempFile.absolutePath)
    }

    private fun saveToGalleryAsync(source: File, resolutionLabel: String, engineLabel: String) {
        Thread {
            try {
                val name = "VideoEnhancer_${timestamp()}.mp4"
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

                source.delete()
                currentTempFile = null
                runOnUiThread {
                    setExporting(false)
                    statusText.text = "Saved to Movies/VideoEnhancer • $resolutionLabel • $engineLabel"
                    Toast.makeText(this, "Enhanced video saved", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setExporting(false)
                    statusText.text = "Enhanced file created, but gallery save failed: ${t.message ?: "unknown error"}"
                }
            }
        }.start()
    }

    private fun setExporting(value: Boolean) {
        exporting = value
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        progressText.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
        }
        selectButton.isEnabled = !value
        enhanceButton.isEnabled = !value && !neuralBenchmarkRunning && selectedUri != null && selectedSourceInfo != null
        engineSpinner.isEnabled = !value
        presetSpinner.isEnabled = !value
        resolutionSpinner.isEnabled = !value
        brightnessSeek.isEnabled = !value
        contrastSeek.isEnabled = !value
        saturationSeek.isEnabled = !value
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000.0).coerceAtLeast(0.0)
        return when {
            seconds < 10.0 -> String.format(Locale.US, "%.1fs", seconds)
            seconds < 60.0 -> String.format(Locale.US, "%.0fs", seconds)
            seconds < 3600.0 -> String.format(Locale.US, "%.1f min", seconds / 60.0)
            else -> String.format(Locale.US, "%.1f h", seconds / 3600.0)
        }
    }

    private fun compactError(error: Throwable): String {
        val message = error.message?.replace('\n', ' ')?.trim().orEmpty()
        return if (message.isNotEmpty()) message.take(180) else error.javaClass.simpleName
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onDestroy() {
        if (isFinishing) {
            currentAiProcessor?.cancel()
            currentAiProcessor = null
            currentTransformer?.cancel()
            currentTransformer = null
            currentTempFile?.delete()
            currentTempFile = null
        }
        super.onDestroy()
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
