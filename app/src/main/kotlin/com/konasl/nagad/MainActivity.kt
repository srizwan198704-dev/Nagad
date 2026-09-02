package com.konasl.nagad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var selectButton: Button
    private lateinit var viewLogButton: Button

    private var selectedVideoUri: Uri? = null
    private var inputFilePath: String? = null

    // Holds full ffmpeg log output for the last run (success or fail)
    private val ffmpegLogBuilder = StringBuilder()

    companion object {
        private const val PREFS_NAME = "crash_prefs"
        private const val KEY_CRASH_LOG = "last_crash_log"
    }

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
        installCrashHandler()
        setContentView(buildUi())
        showPendingCrashIfAny()
    }

    // ---------- Crash handling ----------

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val fullLog = buildString {
                    append("=== CRASH REPORT ===\n")
                    append("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n")
                    append("Thread: ${thread.name}\n")
                    append("App: com.konasl.nagad\n")
                    append("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
                    append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")
                    append(sw.toString())
                }

                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CRASH_LOG, fullLog)
                    .apply()

                try {
                    val crashFile = File(cacheDir, "last_crash.txt")
                    crashFile.writeText(fullLog)
                } catch (_: Exception) {
                }

                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Crash Log", fullLog)
                    clipboard?.setPrimaryClip(clip)
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun showPendingCrashIfAny() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val crashLog = prefs.getString(KEY_CRASH_LOG, null)
        if (!crashLog.isNullOrEmpty()) {
            showTextDialog("App Crashed Last Time", crashLog)
            prefs.edit().remove(KEY_CRASH_LOG).apply()
        }
    }

    private fun showTextDialog(title: String, content: String) {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val logText = TextView(this).apply {
            text = content
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        }

        container.addView(logText)
        scrollView.addView(container)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Copy Log") { dialog, _ ->
                copyToClipboard(content)
                Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setNegativeButton("Dismiss") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("FFmpeg Log", text)
        clipboard?.setPrimaryClip(clip)
    }

    // ---------- Existing UI (unchanged) ----------

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

        val viewCrashLogButton = Button(this).apply {
            text = "View Last Crash Log"
            setOnClickListener {
                val crashFile = File(cacheDir, "last_crash.txt")
                if (crashFile.exists()) {
                    showTextDialog("Last Crash Log", crashFile.readText())
                } else {
                    Toast.makeText(this@MainActivity, "No crash log found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewLogButton = Button(this).apply {
            text = "View Last Export Log"
            setOnClickListener {
                if (ffmpegLogBuilder.isNotEmpty()) {
                    showTextDialog("Last Export Log", ffmpegLogBuilder.toString())
                } else {
                    val logFile = File(cacheDir, "last_export_log.txt")
                    if (logFile.exists()) {
                        showTextDialog("Last Export Log", logFile.readText())
                    } else {
                        Toast.makeText(this@MainActivity, "No export log found yet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        root.addView(title)
        root.addView(selectButton)
        root.addView(selectedFileText)
        root.addView(exportButton)
        root.addView(progressBar)
        root.addView(statusText)
        root.addView(viewCrashLogButton)
        root.addView(viewLogButton)

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
        ffmpegLogBuilder.clear()

        Thread {
            try {
                inputFilePath = copyUriToCache(uri)
                val outputFile = File(cacheDir, "output_4k_${System.currentTimeMillis()}.mp4")
                val outputPath = outputFile.absolutePath

                // NOTE: libx264 is NOT available in this ffmpeg-kit fork (LGPL-only build,
                // confirmed via export log: "Unknown encoder 'libx264'").
                // Using libopenh264 instead (software H.264 encoder, LGPL-safe).
                // libopenh264 has no CRF support, so bitrate-based rate control is used instead.
                val command = "-y -i \"$inputFilePath\" " +
                        "-vf \"scale=3840:2160:force_original_aspect_ratio=decrease," +
                        "pad=3840:2160:(ow-iw)/2:(oh-ih)/2\" " +
                        "-c:v libopenh264 -b:v 25M -maxrate 30M -bufsize 40M " +
                        "-c:a aac -b:a 192k " +
                        "\"$outputPath\""

                runOnUiThread { statusText.text = "Exporting to 4K..." }

                // Capture every line ffmpeg prints — this is where the REAL error shows up
                FFmpegKitConfig.enableLogCallback { log ->
                    val line = log.message ?: ""
                    ffmpegLogBuilder.append(line).append("\n")
                }

                FFmpegKitConfig.enableStatisticsCallback { statistics: Statistics ->
                    val timeMs = statistics.time
                    runOnUiThread {
                        if (timeMs > 0) {
                            statusText.text = "Exporting... (${timeMs / 1000}s processed)"
                        }
                    }
                }

                val session = FFmpegKit.execute(command)

                // Always persist the log so it survives even if something crashes after this point
                val logString = ffmpegLogBuilder.toString().ifBlank {
                    session.allLogsAsString ?: "(no log output captured)"
                }
                try {
                    File(cacheDir, "last_export_log.txt").writeText(
                        "Command: $command\n\nReturn code: ${session.returnCode}\n\n$logString"
                    )
                } catch (_: Exception) {
                }

                runOnUiThread {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        saveToGallery(outputFile)
                        statusText.text = "Export complete! Saved to gallery."
                        progressBar.progress = 100
                        Toast.makeText(this, "4K export successful", Toast.LENGTH_LONG).show()
                    } else {
                        statusText.text = "Export failed (code ${session.returnCode}). Tap 'View Last Export Log' for details."
                        copyToClipboard(logString)
                        Toast.makeText(
                            this,
                            "Export failed — log copied to clipboard",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    exportButton.isEnabled = true
                    selectButton.isEnabled = true
                }
            } catch (e: Exception) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val errLog = sw.toString()
                try {
                    File(cacheDir, "last_export_log.txt").writeText(errLog)
                } catch (_: Exception) {
                }
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    copyToClipboard(errLog)
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
