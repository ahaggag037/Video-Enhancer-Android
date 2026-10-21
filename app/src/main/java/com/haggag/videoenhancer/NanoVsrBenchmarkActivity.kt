package com.haggag.videoenhancer

import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NanoVsrBenchmarkActivity : AppCompatActivity() {

    private lateinit var runButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var deviceText: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nanovsr_benchmark)

        runButton = findViewById(R.id.runBenchmarkButton)
        progress = findViewById(R.id.benchmarkProgress)
        status = findViewById(R.id.benchmarkStatus)
        result = findViewById(R.id.benchmarkResult)
        deviceText = findViewById(R.id.deviceText)

        deviceText.text = buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
            append("\nHardware ${Build.HARDWARE} • Board ${Build.BOARD}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append("\nSoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            }
        }

        if (!NanoVsrDeviceBenchmark.assetsAvailable(this)) {
            runButton.isEnabled = false
            status.text = "GPU isolation models are not packaged in this build"
            result.text = "Use the dedicated Temporal AI GPU Isolation APK."
        }

        runButton.setOnClickListener {
            if (!running) runBenchmark()
        }
    }

    private fun runBenchmark() {
        running = true
        runButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Starting GPU isolation matrix…"
        result.text = "Testing primitive operators, rank-5 temporal indexing, then NanoVSR at increasing shapes. CPU is intentionally skipped."

        val thermalBefore = thermalStatus()
        Thread({
            val measured = runCatching {
                NanoVsrDeviceBenchmark.measure(applicationContext) { completed, total, label ->
                    runOnUiThread {
                        status.text = "GPU isolation $completed/$total • $label"
                    }
                }
            }
            val thermalAfter = thermalStatus()
            runOnUiThread {
                running = false
                runButton.isEnabled = true
                progress.visibility = View.GONE
                measured.onSuccess { benchmark ->
                    status.text = "GPU isolation finished • ${benchmark.passed} pass / ${benchmark.failed} fail"
                    result.text = buildString {
                        append(benchmark.format())
                        append("\nThermal status: $thermalBefore → $thermalAfter")
                    }
                }.onFailure { error ->
                    status.text = "GPU isolation failed before probes completed"
                    result.text = error.message ?: error.javaClass.simpleName
                }
            }
        }, "nanovsr-gpu-isolation").start()
    }

    private fun thermalStatus(): Int {
        val power = getSystemService(PowerManager::class.java) ?: return -1
        return power.currentThermalStatus
    }
}
