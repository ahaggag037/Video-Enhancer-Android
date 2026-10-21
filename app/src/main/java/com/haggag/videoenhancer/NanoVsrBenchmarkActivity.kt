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

        deviceText.text = "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}"

        if (!NanoVsrDeviceBenchmark.assetAvailable(this)) {
            runButton.isEnabled = false
            status.text = "NanoVSR diagnostic model is not packaged in this build"
            result.text = "Use the dedicated Temporal AI benchmark APK."
        }

        runButton.setOnClickListener {
            if (!running) runBenchmark()
        }
    }

    private fun runBenchmark() {
        running = true
        runButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Running NanoVSR on GPU, then bounded CPU fallback…"
        result.text = "Benchmark in progress. The CPU test automatically stops early if it is very slow."

        val thermalBefore = thermalStatus()
        Thread({
            val measured = runCatching { NanoVsrDeviceBenchmark.measure(applicationContext) }
            val thermalAfter = thermalStatus()
            runOnUiThread {
                running = false
                runButton.isEnabled = true
                progress.visibility = View.GONE
                measured.onSuccess { benchmark ->
                    status.text = "Benchmark finished"
                    result.text = buildString {
                        append(benchmark.format())
                        append("\nThermal status: $thermalBefore → $thermalAfter")
                    }
                }.onFailure { error ->
                    status.text = "Benchmark failed"
                    result.text = error.message ?: error.javaClass.simpleName
                }
            }
        }, "nanovsr-device-benchmark").start()
    }

    private fun thermalStatus(): Int {
        val power = getSystemService(PowerManager::class.java) ?: return -1
        return power.currentThermalStatus
    }
}
