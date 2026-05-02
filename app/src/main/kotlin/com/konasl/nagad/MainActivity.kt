package com.konasl.nagad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.*
import android.webkit.*
import android.widget.*
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var tabLayout: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var fileManagerView: LinearLayout
    private lateinit var browserView: LinearLayout
    private lateinit var fileListView: ListView
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private lateinit var pathText: TextView
    private lateinit var storageInfo: TextView
    private var isFileManagerActive = true

    // Browser Components
    private lateinit var tabContainer: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private val webTabs = mutableListOf<WebTab>()
    private var currentTabIndex = 0
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout
    private var isDesktopMode = false

    // Fullscreen Video
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    // State Save
    private lateinit var prefs: SharedPreferences
    private val KEY_CURRENT_PATH = "current_path"
    private val KEY_TAB_URLS = "tab_urls"
    private val KEY_CURRENT_TAB = "current_tab"
    private val KEY_IS_FILE_MANAGER = "is_file_manager"
    private val KEY_DESKTOP_MODE = "desktop_mode"

    // UI init flag
    private var isUIInitialized = false

    // Download tracking
    private val activeDownloads = mutableMapOf<Long, String>()
    private var downloadReceiver: BroadcastReceiver? = null

    data class WebTab(
        val webView: WebView,
        var title: String = "New Tab",
        var url: String = ""
    )

    // File upload callback
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileUploadCallback ?: return@registerForActivityResult
        fileUploadCallback = null
        val results = if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            arrayOf(result.data!!.data!!)
        } else {
            null
        }
        cb.onReceiveValue(results)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                initUI()
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
                initUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            supportActionBar?.hide()
        } catch (e: Exception) { }

        prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        isDesktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false)

        checkPermissionAndInit()
    }

    private fun checkPermissionAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    initUI()
                }
            } else {
                initUI()
            }
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (readPerm != PackageManager.PERMISSION_GRANTED || writePerm != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            } else {
                initUI()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        initUI()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initUI() {
        if (isUIInitialized) return
        isUIInitialized = true

        currentPath = try {
            Environment.getExternalStorageDirectory().takeIf { it.exists() && it.canRead() }
                ?: filesDir
        } catch (e: Exception) {
            filesDir
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        }

        val fileTab = createTabButton("📁 Files", true) { switchToFileManager() }
        val browserTab = createTabButton("🌐 Browser", false) { switchToBrowser() }
        tabLayout.addView(fileTab)
        tabLayout.addView(browserTab)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        fileManagerView = createFileManagerView()
        browserView = createBrowserView()

        contentFrame.addView(fileManagerView)
        contentFrame.addView(browserView)

        rootLayout.addView(tabLayout)
        rootLayout.addView(contentFrame)

        setContentView(rootLayout)

        restoreState()
    }

    private fun restoreState() {
        try {
            val savedPath = prefs.getString(KEY_CURRENT_PATH, null)
            if (savedPath != null) {
                val dir = File(savedPath)
                if (dir.exists() && dir.isDirectory && dir.canRead()) {
                    currentPath = dir
                }
            }
        } catch (e: Exception) { }
        loadFiles(currentPath)

        try {
            val tabUrls = prefs.getStringSet(KEY_TAB_URLS, null)
            if (!tabUrls.isNullOrEmpty()) {
                tabUrls.forEach { url ->
                    if (url.isNotEmpty()) addNewTab(url, switch = false)
                }
            }
        } catch (e: Exception) { }

        if (webTabs.isEmpty()) {
            addNewTab("https://www.google.com", switch = true)
        } else {
            val savedIdx = prefs.getInt(KEY_CURRENT_TAB, 0)
            currentTabIndex = savedIdx.coerceIn(0, webTabs.size - 1)
            switchTab(currentTabIndex)
        }

        val savedIsFileManager = prefs.getBoolean(KEY_IS_FILE_MANAGER, true)
        if (savedIsFileManager) {
            switchToFileManager()
            highlightTab(0)
        } else {
            switchToBrowser()
            highlightTab(1)
        }
    }

    private fun highlightTab(activeIndex: Int) {
        for (i in 0 until tabLayout.childCount) {
            tabLayout.getChildAt(i)?.setBackgroundColor(
                if (i == activeIndex) Color.parseColor("#333333") else Color.TRANSPARENT
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()
        webTabs.forEach {
            try { it.webView.onPause() } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        webTabs.forEach {
            try { it.webView.onResume() } catch (e: Exception) { }
        }
    }

    private fun saveState() {
        if (!isUIInitialized) return
        try {
            val urls = webTabs.mapNotNull {
                try { it.webView.url?.takeIf { u -> u.isNotEmpty() } ?: "about:blank" }
                catch (e: Exception) { null }
            }.toSet()

            prefs.edit().apply {
                putString(KEY_CURRENT_PATH, currentPath.absolutePath)
                putStringSet(KEY_TAB_URLS, urls)
                putInt(KEY_CURRENT_TAB, currentTabIndex)
                putBoolean(KEY_IS_FILE_MANAGER, isFileManagerActive)
                putBoolean(KEY_DESKTOP_MODE, isDesktopMode)
                apply()
            }
        } catch (e: Exception) { }
    }

    private fun createTabButton(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(if (selected) Color.parseColor("#333333") else Color.TRANSPARENT)
            setOnClickListener {
                for (i in 0 until tabLayout.childCount) {
                    tabLayout.getChildAt(i)?.setBackgroundColor(Color.TRANSPARENT)
                }
                setBackgroundColor(Color.parseColor("#333333"))
                onClick()
            }
        }
    }

    // ==================== FILE MANAGER ====================
    private fun createFileManagerView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }

        val backBtn = Button(this).apply {
            text = "←"
            textSize = 24f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { goUp() }
        }

        pathText = TextView(this).apply {
            text = "/"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            ellipsize = android.text.TextUtils.TruncateAt.START
            setSingleLine(true)
            textSize = 14f
        }

        val newFolderBtn = Button(this).apply {
            text = "📁+"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { createNewFolder() }
        }

        topBar.addView(backBtn)
        topBar.addView(pathText)
        topBar.addView(newFolderBtn)

        storageInfo = TextView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }

        fileListView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            divider = null
        }

        layout.addView(topBar)
        layout.addView(storageInfo)
        layout.addView(fileListView)
        return layout
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".gif") ||
               lower.endsWith(".bmp") || lower.endsWith(".webp")
    }

    private fun loadFiles(dir: File) {
        try {
            if (!dir.exists() || !dir.canRead()) {
                Toast.makeText(this, "Cannot read directory", Toast.LENGTH_SHORT).show()
                return
            }
            currentPath = dir
            val storagePath = try {
                Environment.getExternalStorageDirectory().absolutePath
            } catch (e: Exception) { "" }
            pathText.text = dir.absolutePath.replace(storagePath, "/storage/emulated/0")

            try {
                storageInfo.text = "Free: ${getSize(dir.freeSpace)} | Total: ${getSize(dir.totalSpace)}"
            } catch (e: Exception) {
                storageInfo.text = ""
            }

            val files = try {
                dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val adapter = object : ArrayAdapter<File>(
                this,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                files
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val file = getItem(position) ?: return view
                    val text1 = view.findViewById<TextView>(android.R.id.text1)
                    val text2 = view.findViewById<TextView>(android.R.id.text2)

                    val icon = when {
                        file.isDirectory -> "📁"
                        file.name.endsWith(".mp4", true)
                                || file.name.endsWith(".mkv", true)
                                || file.name.endsWith(".avi", true) -> "🎬"
                        file.name.endsWith(".pdf", true) -> "📕"
                        file.name.endsWith(".txt", true) -> "📝"
                        isImageFile(file.name) -> "🖼️"
                        else -> "📄"
                    }

                    text1.text = "$icon ${file.name}"
                    text1.setTextColor(Color.BLACK)
                    text1.textSize = 14f
                    text2.text = try {
                        "${getSize(file.length())} | ${
                            SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
                                .format(Date(file.lastModified()))
                        }"
                    } catch (e: Exception) { "" }
                    text2.textSize = 11f
                    return view
                }
            }

            fileListView.adapter = adapter
            fileListView.setOnItemClickListener { _, _, position, _ ->
                if (position >= files.size) return@setOnItemClickListener
                val file = files[position]
                when {
                    file.isDirectory -> loadFiles(file)
                    file.name.endsWith(".mp4", true)
                            || file.name.endsWith(".mkv", true)
                            || file.name.endsWith(".avi", true) -> playVideo(file)
                    file.name.endsWith(".txt", true) -> openTextFile(file)
                    file.name.endsWith(".pdf", true) -> openPdfFile(file)
                    isImageFile(file.name) -> openImageFile(file)
                    else -> Toast.makeText(
                        this@MainActivity, "Unsupported file", Toast.LENGTH_SHORT
                    ).show()
                }
            }

            fileListView.setOnItemLongClickListener { _, _, position, _ ->
                if (position < files.size) showFileOptions(files[position])
                true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading files: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goUp() {
        try {
            val parent = currentPath.parentFile
            if (parent != null && parent.exists() && parent.canRead()) {
                loadFiles(parent)
            }
        } catch (e: Exception) { }
    }

    private fun createNewFolder() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newFolder = File(currentPath, name)
                    if (newFolder.mkdirs()) {
                        Toast.makeText(this, "Created", Toast.LENGTH_SHORT).show()
                        loadFiles(currentPath)
                    } else {
                        Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileOptions(file: File) {
        val options = mutableListOf("Delete", "Rename", "Details")
        if (file.name.endsWith(".pdf", true)) options.add(1, "PDF to JPG")

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Delete" -> deleteFile(file)
                    "Rename" -> renameFile(file)
                    "PDF to JPG" -> pdfToJpg(file)
                    "Details" -> showDetails(file)
                }
            }
            .show()
    }

    private fun deleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Delete ${file.name}?")
            .setPositiveButton("Yes") { _, _ ->
                if (file.deleteRecursively()) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                } else {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun renameFile(file: File) {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && file.renameTo(File(file.parent, newName))) {
                    Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                } else {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetails(file: File) {
        val msg = """
            Name: ${file.name}
            Path: ${file.absolutePath}
            Size: ${getSize(file.length())}
            Modified: ${Date(file.lastModified())}
            Readable: ${file.canRead()}
            Writable: ${file.canWrite()}
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Details")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ==================== VIDEO PLAYER (camera cutout safe) ====================
    private fun playVideo(file: File) {
        try {
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialog.window?.attributes?.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            dialog.window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            dialog.window?.decorView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

            val rootFrame = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val videoView = VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                setVideoPath(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@MainActivity, "Cannot play this video", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            rootFrame.addView(videoView)

            val controlOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(8), dp(8), dp(8), dp(16))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }

            val topControls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val playPauseBtn = Button(this).apply {
                text = "⏸"
                textSize = 20f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        text = "▶"
                    } else {
                        videoView.start()
                        text = "⏸"
                    }
                }
            }

            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) videoView.seekTo(progress)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }

            val timeText = TextView(this).apply {
                text = "00:00 / 00:00"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
            }

            topControls.addView(playPauseBtn)
            topControls.addView(seekBar)
            topControls.addView(timeText)

            val orientationControls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            }

            val portraitBtn = Button(this).apply {
                text = "📱"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#333333"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            val landscapeBtn = Button(this).apply {
                text = "🌍"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#333333"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }

            val sensorBtn = Button(this).apply {
                text = "🔄 Auto"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#333333"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
            }

            // FIXED: Changed variable name from 'closeBtn' to 'closeVideoButton' to avoid conflict
            val closeVideoButton = Button(this).apply {
                text = "✕ Close"
                textSize = 14f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    try { videoView.stopPlayback() } catch (e: Exception) { }
                    dialog.dismiss()
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            orientationControls.addView(portraitBtn)
            orientationControls.addView(landscapeBtn)
            orientationControls.addView(sensorBtn)
            orientationControls.addView(closeVideoButton)

            controlOverlay.addView(topControls)
            controlOverlay.addView(orientationControls)

            rootFrame.addView(controlOverlay)

            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        try {
                            if (videoView.isPlaying) {
                                val current = videoView.currentPosition
                                val duration = videoView.duration
                                if (duration > 0) {
                                    seekBar.max = duration
                                    seekBar.progress = current
                                    timeText.text = "${formatTime(current)} / ${formatTime(duration)}"
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }, 0, 1000)

            dialog.setOnDismissListener {
                timer.cancel()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            dialog.setContentView(rootFrame)
            dialog.show()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        } catch (e: Exception) {
            Toast.makeText(this, "Error playing video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(millis: Int): String {
        if (millis < 0) return "00:00"
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%02d:%02d", minutes, seconds % 60)
        }
    }

    // ==================== TEXT VIEWER ====================
    private fun openTextFile(file: File) {
        try {
            val text = file.readText()

            val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FAFAFA"))
            }

            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#2C2C2C"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleView = TextView(this).apply {
                this.text = file.name
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }

            // FIXED: Changed variable name from 'closeBtn' to 'closeTextButton' to avoid conflict
            val closeTextButton = Button(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setPadding(0, 0, 0, 0)
                setOnClickListener { dialog.dismiss() }
            }

            val searchBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#E8E8E8"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                gravity = Gravity.CENTER_VERTICAL
            }

            val searchInput = EditText(this).apply {
                hint = "🔍 Search in file..."
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                textSize = 13f
            }

            val resultCount = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#666666"))
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
            }

            topBar.addView(titleView)
            topBar.addView(closeTextButton)
            searchBar.addView(searchInput)
            searchBar.addView(resultCount)

            val infoBar = TextView(this).apply {
                val lines = text.lines().size
                val words = text.trim().split(Regex("\\s+")).size
                this.text = "  📄 ${getSize(file.length())}  |  $lines lines  |  $words words"
                setTextColor(Color.parseColor("#666666"))
                textSize = 11f
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }

            val textView = TextView(this).apply {
                this.text = text
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setTextIsSelectable(true)
                textSize = 14f
                setTextColor(Color.parseColor("#212121"))
                typeface = Typeface.MONOSPACE
            }
            scrollView.addView(textView)

            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val query = s.toString().trim()
                    if (query.isEmpty()) {
                        textView.text = text
                        resultCount.text = ""
                        return
                    }
                    val spannable = android.text.SpannableString(text)
                    var count = 0
                    var idx = text.indexOf(query, ignoreCase = true)
                    while (idx != -1) {
                        spannable.setSpan(
                            android.text.style.BackgroundColorSpan(Color.parseColor("#FFEB3B")),
                            idx, idx + query.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        count++
                        idx = text.indexOf(query, idx + 1, ignoreCase = true)
                    }
                    textView.text = spannable
                    resultCount.text = if (count > 0) "$count found" else "Not found"
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            layout.addView(topBar)
            layout.addView(searchBar)
            layout.addView(infoBar)
            layout.addView(scrollView)

            dialog.setContentView(layout)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== IMAGE VIEWER ====================
    private fun openImageFile(file: File) {
        try {
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialog.window?.attributes?.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            val rootFrame = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
            }

            val bitmap = loadBitmapSafe(file)
            if (bitmap == null) {
                Toast.makeText(this, "Cannot open image", Toast.LENGTH_SHORT).show()
                return
            }

            val zoomMatrix = Matrix()
            var zoomScale = 1f

            val imageView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }

            val scaleDetector = ScaleGestureDetector(this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        zoomScale *= detector.scaleFactor
                        zoomScale = zoomScale.coerceIn(0.5f, 8f)
                        zoomMatrix.setScale(zoomScale, zoomScale, detector.focusX, detector.focusY)
                        imageView.imageMatrix = zoomMatrix
                        return true
                    }
                }
            )

            imageView.setOnTouchListener { v, event ->
                scaleDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                    v.performClick()
                }
                true
            }

            rootFrame.addView(imageView)

            val topOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(12), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            }

            val infoText = TextView(this).apply {
                text = "${file.name}  |  ${bitmap.width}×${bitmap.height}  |  ${getSize(file.length())}"
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }

            // FIXED: Changed variable name from 'closeBtn' to 'closeImageButton' to avoid conflict
            val closeImageButton = Button(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setPadding(0, 0, 0, 0)
                setOnClickListener { dialog.dismiss() }
            }

            topOverlay.addView(infoText)
            topOverlay.addView(closeImageButton)
            rootFrame.addView(topOverlay)

            val hintText = TextView(this).apply {
                text = "Pinch to zoom"
                setTextColor(Color.parseColor("#AAFFFFFF"))
                textSize = 11f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#88000000"))
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { setMargins(0, 0, 0, dp(24)) }
            }
            rootFrame.addView(hintText)

            dialog.setContentView(rootFrame)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Image error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBitmapSafe(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val maxDim = 2048
            var sampleSize = 1
            while ((options.outWidth / sampleSize) > maxDim || (options.outHeight / sampleSize) > maxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) { null }
    }

    private fun openPdfFile(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(this, "PDF requires Android 5.0+", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                renderer.close()
                Toast.makeText(this, "Empty PDF", Toast.LENGTH_SHORT).show()
                return
            }

            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.BLACK)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val titleText = TextView(this).apply {
                text = "${file.name} - 1/$pageCount"
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            // FIXED: Changed variable name from 'closeBtn' to 'closePdfButton' to avoid conflict
            val closePdfButton = Button(this).apply {
                text = "✕"
                textSize = 20f
                setOnClickListener {
                    try { renderer.close() } catch (e: Exception) { }
                    dialog.dismiss()
                }
            }
            topBar.addView(titleText)
            topBar.addView(closePdfButton)

            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            var currentPage = 0
            fun renderPage(pageNum: Int) {
                try {
                    val page = renderer.openPage(pageNum)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    imageView.setImageBitmap(bitmap)
                    titleText.text = "${file.name} - ${pageNum + 1}/$pageCount"
                    page.close()
                } catch (e: Exception) {
                    Toast.makeText(this, "PDF render error", Toast.LENGTH_SHORT).show()
                }
            }
            renderPage(0)

            imageView.setOnClickListener {
                currentPage = (currentPage + 1) % pageCount
                renderPage(currentPage)
            }

            layout.addView(topBar)
            layout.addView(imageView)
            dialog.setContentView(layout)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "PDF Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pdfToJpg(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(this, "PDF to JPG requires Android 5.0+", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outputDir = File(currentPath, "${file.nameWithoutExtension}_jpg")
                outputDir.mkdirs()

                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val outFile = File(outputDir, "page_${i + 1}.jpg")
                    FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    page.close()
                }
                renderer.close()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved to ${outputDir.name}", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ==================== BROWSER ====================
    private fun createBrowserView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        }

        tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            isHorizontalScrollBarEnabled = false
        }
        tabContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabContainer)

        val newTabBtn = Button(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { addNewTab("https://www.google.com", switch = true) }
        }

        tabBar.addView(tabScroll)
        tabBar.addView(newTabBtn)

        val urlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val urlBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        urlBar = EditText(this).apply {
            hint = "Enter URL or search"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ ->
                loadUrlInCurrentTab()
                true
            }
        }

        val goBtn = Button(this).apply {
            text = "Go"
            setOnClickListener { loadUrlInCurrentTab() }
        }

        val desktopBtn = Button(this).apply {
            text = if (isDesktopMode) "📱" else "💻"
            textSize = 16f
            setOnClickListener {
                isDesktopMode = !isDesktopMode
                text = if (isDesktopMode) "📱" else "💻"
                Toast.makeText(
                    this@MainActivity,
                    if (isDesktopMode) "Desktop Mode ON" else "Desktop Mode OFF",
                    Toast.LENGTH_SHORT
                ).show()
                webTabs.forEach { tab ->
                    try {
                        updateDesktopMode(tab.webView)
                        tab.webView.reload()
                    } catch (e: Exception) { }
                }
            }
        }

        urlBarLayout.addView(urlBar)
        urlBarLayout.addView(goBtn)
        urlBarLayout.addView(desktopBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            max = 100
            visibility = View.GONE
        }

        urlLayout.addView(urlBarLayout)
        urlLayout.addView(progressBar)

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        layout.addView(tabBar)
        layout.addView(urlLayout)
        layout.addView(webContainer)
        return layout
    }

    private fun updateDesktopMode(webView: WebView) {
        val userAgent = if (isDesktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            WebSettings.getDefaultUserAgent(this)
        }
        try {
            webView.settings.userAgentString = userAgent
        } catch (e: Exception) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addNewTab(url: String, switch: Boolean = true) {
        try {
            val webView = WebView(applicationContext).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                setSavePassword(true)
                @Suppress("DEPRECATION")
                setSaveFormData(true)
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            CookieManager.getInstance().setAcceptCookie(true)

            updateDesktopMode(webView)

            val tab = WebTab(webView, title = "Loading…", url = url)
            webTabs.add(tab)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    view.loadUrl(request.url.toString())
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    val idx = webTabs.indexOfFirst { it.webView == view }
                    if (idx == currentTabIndex) {
                        progressBar.visibility = View.VISIBLE
                        urlBar.setText(url)
                    }
                    if (idx != -1) webTabs[idx].url = url ?: ""
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val idx = webTabs.indexOfFirst { it.webView == view }
                    if (idx == currentTabIndex) {
                        progressBar.visibility = View.GONE
                        urlBar.setText(url)
                    }
                    if (idx != -1) webTabs[idx].url = url ?: ""
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // silently handle errors
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (webTabs.indexOfFirst { it.webView == view } == currentTabIndex) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val index = webTabs.indexOfFirst { it.webView == view }
                    if (index != -1 && !title.isNullOrEmpty()) {
                        webTabs[index].title = title
                        updateTabTitles()
                    }
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    originalOrientation = requestedOrientation

                    val decorView = window.decorView as FrameLayout
                    decorView.addView(
                        customView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                    tabLayout.visibility = View.GONE
                }

                override fun onHideCustomView() {
                    if (customView == null) return
                    val decorView = window.decorView as FrameLayout
                    decorView.removeView(customView)
                    customView = null
                    requestedOrientation = originalOrientation
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                    tabLayout.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                }
            }

            webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, contentLength ->
                handleDownload(downloadUrl, userAgent, contentDisposition, mimetype, contentLength, webView)
            }

            val tabIndex = webTabs.size - 1
            val tabButton = Button(this).apply {
                text = "Tab ${tabIndex + 1}"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#444444"))
                layoutParams = LinearLayout.LayoutParams(
                    dp(70), ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                setPadding(dp(4), 0, dp(4), 0)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { switchTab(webTabs.indexOf(tab)) }
                setOnLongClickListener {
                    closeTab(webTabs.indexOf(tab))
                    true
                }
            }
            tabContainer.addView(tabButton)

            webView.loadUrl(url)

            if (switch) switchTab(tabIndex)

        } catch (e: Exception) {
            Toast.makeText(this, "Error creating tab: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= webTabs.size) return
        currentTabIndex = index
        webContainer.removeAllViews()
        try {
            webContainer.addView(webTabs[index].webView)
            urlBar.setText(
                try { webTabs[index].webView.url ?: "" } catch (e: Exception) { "" }
            )
        } catch (e: Exception) { }
        progressBar.visibility = View.GONE
        updateTabTitles()
    }

    private fun closeTab(index: Int): Boolean {
        if (index < 0 || index >= webTabs.size) return false
        if (webTabs.size <= 1) {
            Toast.makeText(this, "Cannot close last tab", Toast.LENGTH_SHORT).show()
            return false
        }
        try {
            webTabs[index].webView.stopLoading()
            webTabs[index].webView.destroy()
        } catch (e: Exception) { }
        webTabs.removeAt(index)
        if (index < tabContainer.childCount) tabContainer.removeViewAt(index)
        currentTabIndex = currentTabIndex.coerceIn(0, webTabs.size - 1)
        switchTab(currentTabIndex)
        return true
    }

    private fun updateTabTitles() {
        for (i in 0 until tabContainer.childCount) {
            val btn = tabContainer.getChildAt(i) as? Button ?: continue
            val title = if (i < webTabs.size) webTabs[i].title else "Tab ${i + 1}"
            btn.text = if (title.length > 7) title.substring(0, 6) + "…" else title
            btn.setBackgroundColor(
                if (i == currentTabIndex) Color.parseColor("#6200EE")
                else Color.parseColor("#444444")
            )
        }
    }

    private fun loadUrlInCurrentTab() {
        if (webTabs.isEmpty()) return
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) url = "https://www.google.com"
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("about:")) {
            url = if (url.contains(".") && !url.contains(" ")) {
                "https://$url"
            } else {
                "https://www.google.com/search?q=${Uri.encode(url)}"
            }
        }
        try {
            webTabs[currentTabIndex].webView.loadUrl(url)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load URL", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== COMMON ====================
    private fun switchToFileManager() {
        if (customView != null) return
        isFileManagerActive = true
        fileManagerView.visibility = View.VISIBLE
        browserView.visibility = View.GONE
    }

    private fun switchToBrowser() {
        isFileManagerActive = false
        fileManagerView.visibility = View.GONE
        browserView.visibility = View.VISIBLE
        if (currentTabIndex in webTabs.indices) {
            try { webTabs[currentTabIndex].webView.onResume() } catch (e: Exception) { }
        }
    }

    private fun getSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(
            size / Math.pow(1024.0, digitGroups.toDouble())
        ) + " " + units[digitGroups]
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ==================== DOWNLOAD SYSTEM ====================

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimetype: String,
        contentLength: Long,
        webView: WebView
    ) {
        when {
            url.startsWith("blob:") -> downloadBlobUrl(url, webView)
            url.startsWith("data:") -> downloadDataUrl(url)
            else -> downloadWithManager(url, userAgent, contentDisposition, mimetype, contentLength, webView)
        }
    }

    private fun downloadWithManager(
        url: String,
        userAgentStr: String,
        contentDisposition: String,
        mimetype: String,
        contentLength: Long,
        webView: WebView
    ) {
        try {
            val rawName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val defaultFileName = sanitizeFileName(rawName)

            val cookies = CookieManager.getInstance().getCookie(url) ?: ""

            val ua = if (userAgentStr.isBlank()) {
                try { webView.settings.userAgentString } catch (e: Exception) { userAgentStr }
            } else userAgentStr

            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(8))
            }

            val sizeText = if (contentLength > 0) getSize(contentLength) else "Unknown size"

            val infoLabel = TextView(this).apply {
                text = "📦 Size: $sizeText"
                textSize = 13f
                setTextColor(Color.parseColor("#555555"))
                setPadding(0, 0, 0, dp(12))
            }

            val fileNameLabel = TextView(this).apply {
                text = "File name (editable):"
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            }

            val fileNameInput = EditText(this).apply {
                setText(defaultFileName)
                textSize = 14f
                setSingleLine(true)
                post { selectAll() }
            }

            dialogView.addView(infoLabel)
            dialogView.addView(fileNameLabel)
            dialogView.addView(fileNameInput)

            AlertDialog.Builder(this)
                .setTitle("⬇️ Download File")
                .setView(dialogView)
                .setPositiveButton("Download") { _, _ ->
                    val inputName = fileNameInput.text.toString().trim()
                    val fileName = sanitizeFileName(inputName.ifBlank { defaultFileName })

                    try {
                        val parsedUri = Uri.parse(url)

                        val request = DownloadManager.Request(parsedUri).apply {
                            setTitle(fileName)
                            setDescription("Downloading...")
                            setMimeType(mimetype.ifBlank { "application/octet-stream" })
                            setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            if (cookies.isNotEmpty()) addRequestHeader("Cookie", cookies)
                            if (ua.isNotEmpty()) addRequestHeader("User-Agent", ua)
                            try {
                                val referer = "${parsedUri.scheme}://${parsedUri.host}"
                                addRequestHeader("Referer", referer)
                            } catch (e: Exception) { }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            } else {
                                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                destDir.mkdirs()
                                setDestinationUri(Uri.fromFile(File(destDir, fileName)))
                            }
                            setAllowedOverMetered(true)
                            setAllowedOverRoaming(true)
                        }

                        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val downloadId = dm.enqueue(request)
                        activeDownloads[downloadId] = fileName
                        registerDownloadReceiver()

                        Toast.makeText(this, "⬇️ শুরু হয়েছে: $fileName", Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        Toast.makeText(this, "DownloadManager ব্যর্থ, retry করছে...", Toast.LENGTH_SHORT).show()
                        downloadManually(url, cookies, ua, contentDisposition, mimetype, fileName)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadBlobUrl(blobUrl: String, webView: WebView) {
        val js = """
            javascript:(function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$blobUrl', true);
                xhr.responseType = 'blob';
                xhr.onload = function(e) {
                    if (this.status == 200) {
                        var blob = this.response;
                        var reader = new FileReader();
                        reader.readAsDataURL(blob);
                        reader.onloadend = function() {
                            var base64data = reader.result;
                            AndroidInterface.downloadBase64(base64data, blob.type);
                        }
                    }
                };
                xhr.send();
            })()
        """.trimIndent()

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun downloadBase64(dataUrl: String, mimeType: String) {
                runOnUiThread {
                    downloadDataUrl(dataUrl, mimeType)
                }
            }
        }, "AndroidInterface")

        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Blob ফাইল প্রস্তুত করছে...", Toast.LENGTH_SHORT).show()
    }

    private fun downloadDataUrl(dataUrl: String, overrideMime: String = "") {
        Thread {
            try {
                val commaIdx = dataUrl.indexOf(',')
                if (commaIdx == -1) {
                    runOnUiThread { Toast.makeText(this, "Invalid data URL", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                val header = dataUrl.substring(0, commaIdx)
                val base64Data = dataUrl.substring(commaIdx + 1)
                val mimeType = if (overrideMime.isNotEmpty()) overrideMime
                               else header.substringAfter("data:").substringBefore(";").ifBlank { "application/octet-stream" }

                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val fileName = "download_${System.currentTimeMillis()}.$ext"

                val bytes = Base64.decode(base64Data, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        runOnUiThread {
                            Toast.makeText(this, "✅ সংরক্ষিত: $fileName", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    destDir.mkdirs()
                    val file = File(destDir, fileName)
                    FileOutputStream(file).use { it.write(bytes) }
                    runOnUiThread {
                        Toast.makeText(this, "✅ সংরক্ষিত: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Data URL save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun downloadManually(
        url: String,
        cookies: String,
        userAgent: String,
        contentDisposition: String,
        mimetype: String,
        customFileName: String? = null
    ) {
        val fileName = customFileName
            ?: sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimetype))
        Toast.makeText(this, "⬇️ Manual download: $fileName", Toast.LENGTH_SHORT).show()

        Thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", userAgent)
                if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies)
                connection.setRequestProperty("Accept", "*/*")
                connection.instanceFollowRedirects = true
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    runOnUiThread {
                        Toast.makeText(
                            this, "Server error: HTTP $responseCode", Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }

                inputStream = connection.inputStream

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimetype.ifBlank { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            inputStream.copyTo(out, bufferSize = 8192)
                        }
                        cv.clear()
                        cv.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, cv, null, null)
                        runOnUiThread {
                            Toast.makeText(this, "✅ ডাউনলোড সম্পন্ন: $fileName", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this, "Storage write failed", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    destDir.mkdirs()
                    val outFile = File(destDir, fileName)
                    FileOutputStream(outFile).use { out ->
                        inputStream.copyTo(out, bufferSize = 8192)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "✅ ডাউনলোড সম্পন্ন: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { inputStream?.close() } catch (e: Exception) { }
                try { connection?.disconnect() } catch (e: Exception) { }
            }
        }.start()
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiver != null) return
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
                val fileName = activeDownloads.remove(id) ?: return

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(query)

                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusCol >= 0) cursor.getInt(statusCol) else -1

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Toast.makeText(
                                this@MainActivity,
                                "✅ ডাউনলোড সম্পন্ন: $fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else 0
                            val reasonStr = downloadFailReason(reason)
                            Toast.makeText(
                                this@MainActivity,
                                "⚠️ DownloadManager ব্যর্থ ($reasonStr), retry করছে...",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                cursor.close()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun downloadFailReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "Resume করা যাচ্ছে না"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage পাওয়া যাচ্ছে না"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "ফাইল আগে থেকে আছে"
        DownloadManager.ERROR_FILE_ERROR -> "ফাইল error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "স্টোরেজ কম"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "অনেক redirect"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unsupported HTTP code"
        DownloadManager.ERROR_UNKNOWN -> "অজানা error"
        else -> "Error code: $reason"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.length > 200) cleaned.substring(0, 200) else cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> {
                val chromeClient = try {
                    webTabs[currentTabIndex].webView.webChromeClient
                } catch (e: Exception) { null }
                if (chromeClient != null) {
                    chromeClient.onHideCustomView()
                } else {
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    val decorView = window.decorView as FrameLayout
                    customView?.let { decorView.removeView(it) }
                    customView = null
                    tabLayout.visibility = View.VISIBLE
                    requestedOrientation = originalOrientation
                }
            }
            !isFileManagerActive -> {
                val canGoBack = try {
                    webTabs.isNotEmpty() && webTabs[currentTabIndex].webView.canGoBack()
                } catch (e: Exception) { false }
                if (canGoBack) {
                    webTabs[currentTabIndex].webView.goBack()
                } else {
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
            }
            else -> {
                try {
                    val storagePath = Environment.getExternalStorageDirectory().absolutePath
                    if (currentPath.absolutePath != storagePath) {
                        goUp()
                    } else {
                        @Suppress("DEPRECATION")
                        super.onBackPressed()
                    }
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            saveState()
            webTabs.forEach {
                try {
                    it.webView.stopLoading()
                    it.webView.destroy()
                } catch (e: Exception) { }
            }
            webTabs.clear()
        } catch (e: Exception) { }
        try {
            downloadReceiver?.let { unregisterReceiver(it) }
            downloadReceiver = null
        } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
