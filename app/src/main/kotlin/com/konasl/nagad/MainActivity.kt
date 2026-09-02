package com.konasl.nagad

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var selectButton: Button

    private var selectedVideoUri: Uri? = null
    private var inputFilePath: String? = null

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            val name = getFileName(uri)
            selectedFileText.text = "Selected: $name"
            exportButton.isEnabled = true
            statusText.text = "Ready to export"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "4K Video Exporter"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        selectButton = Button(this).apply {
            text = "Select Video"
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }

        selectedFileText = TextView(this).apply {
            text = "No video selected"
            setPadding(0, 24, 0, 24)
            gravity = Gravity.CENTER
        }

        exportButton = Button(this).apply {
            text = "Export to 4K"
            isEnabled = false
            setOnClickListener { startExport() }
        }

        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
        }

        statusText = TextView(this).apply {
            text = "Select a video to begin"
            setPadding(0, 24, 0, 0)
            gravity = Gravity.CENTER
        }

        root.addView(title)
        root.addView(selectButton)
        root.addView(selectedFileText)
        root.addView(exportButton)
        root.addView(progressBar)
        root.addView(statusText)

        return root
    }

    private fun getFileName(uri: Uri): String {
        var name = "video"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun copyUriToCache(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val cacheFile = File(cacheDir, "input_${System.currentTimeMillis()}.mp4")
        FileOutputStream(cacheFile).use { output ->
            inputStream?.copyTo(output)
        }
        inputStream?.close()
        return cacheFile.absolutePath
    }

    private fun startExport() {
        val uri = selectedVideoUri ?: return

        exportButton.isEnabled = false
        selectButton.isEnabled = false
        statusText.text = "Preparing..."
        progressBar.progress = 0

        Thread {
            try {
                inputFilePath = copyUriToCache(uri)
                val outputFile = File(cacheDir, "output_4k_${System.currentTimeMillis()}.mp4")
                val outputPath = outputFile.absolutePath

                val command = "-y -i \"$inputFilePath\" " +
                        "-vf \"scale=3840:2160:force_original_aspect_ratio=decrease," +
                        "pad=3840:2160:(ow-iw)/2:(oh-ih)/2\" " +
                        "-c:v libx264 -preset medium -crf 18 " +
                        "-c:a aac -b:a 192k " +
                        "\"$outputPath\""

                runOnUiThread { statusText.text = "Exporting to 4K..." }

                FFmpegKitConfig.enableStatisticsCallback { statistics: Statistics ->
                    val timeMs = statistics.time
                    runOnUiThread {
                        if (timeMs > 0) {
                            statusText.text = "Exporting... (${timeMs / 1000}s processed)"
                        }
                    }
                }

                val session = FFmpegKit.execute(command)

                runOnUiThread {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        saveToGallery(outputFile)
                        statusText.text = "Export complete! Saved to gallery."
                        progressBar.progress = 100
                        Toast.makeText(
                            this,
                            "4K export successful",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        statusText.text = "Export failed: ${session.failStackTrace ?: session.returnCode}"
                        Toast.makeText(
                            this,
                            "Export failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    exportButton.isEnabled = true
                    selectButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    exportButton.isEnabled = true
                    selectButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "4K_${file.name}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Video4KExport")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

        uri?.let {
            resolver.openOutputStream(it)?.use { outStream ->
                file.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
        }
    }
}
