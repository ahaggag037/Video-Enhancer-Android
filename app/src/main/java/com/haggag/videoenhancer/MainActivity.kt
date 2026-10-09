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
    private var currentTempFile: File? = null
    private var exporting = false

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers do not offer persistable permissions; the current grant is still usable.
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
        setExporting(true)
        statusText.text = "Preparing enhancement…"

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
        val tempFile = File(outputDir, "enhanced_${timestamp()}.mp4")
        currentTempFile = tempFile

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                statusText.text = "Enhancement complete. Saving to gallery…"
                saveToGalleryAsync(tempFile, exportResult)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                tempFile.delete()
                currentTempFile = null
                setExporting(false)
                statusText.text = "Export failed: ${exportException.message ?: "unknown error"}"
                Toast.makeText(this@MainActivity, "Could not enhance this video on this device", Toast.LENGTH_LONG).show()
            }
        }

        currentTransformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(listener)
            .build()

        statusText.text = "Enhancing video… Keep the app open"
        currentTransformer?.start(editedMediaItem, tempFile.absolutePath)
    }

    private fun saveToGalleryAsync(source: File, result: ExportResult) {
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
                    val resolution = if (result.width > 0 && result.height > 0) "${result.width}×${result.height}" else "device-selected resolution"
                    statusText.text = "Saved to Movies/VideoEnhancer • $resolution"
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
        selectButton.isEnabled = !value
        enhanceButton.isEnabled = !value && selectedUri != null
        presetSpinner.isEnabled = !value
        resolutionSpinner.isEnabled = !value
        brightnessSeek.isEnabled = !value
        contrastSeek.isEnabled = !value
        saturationSeek.isEnabled = !value
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onDestroy() {
        if (isFinishing) {
            currentTransformer?.cancel()
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
