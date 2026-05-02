package com.konasl.nagad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.*
import android.webkit.*
import android.widget.*
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
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

    // Fullscreen Video
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private var originalSystemUiVisibility = 0

    // State Save
    private lateinit var prefs: SharedPreferences
    private val KEY_CURRENT_PATH = "current_path"
    private val KEY_TAB_URLS = "tab_urls"
    private val KEY_CURRENT_TAB = "current_tab"
    private val KEY_IS_FILE_MANAGER = "is_file_manager"

    data class WebTab(
        val webView: WebView,
        var title: String = "New Tab",
        var url: String = ""
    )

    private val storagePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                initUI()
            } else {
                toast("Storage permission required")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide action bar/toolbar
        supportActionBar?.hide()
        window.requestFeature(Window.FEATURE_NO_TITLE)
        
        prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        checkPermissionAndInit()
    }

    private fun checkPermissionAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    storagePermission.launch(intent)
                } catch (e: Exception) {
                    toast("Please grant storage permission from settings")
                    initUI()
                }
            } else {
                initUI()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            } else {
                initUI()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initUI()
        } else {
            toast("Permission required for file manager")
            initUI()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initUI() {
        originalSystemUiVisibility = window.decorView.systemUiVisibility

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        }

        val fileTab = createTabButton("📁 Files", true) { switchToFileManager() }
        val browserTab = createTabButton("🌐 Browser", false) { switchToBrowser() }
        tabLayout.addView(fileTab)
        tabLayout.addView(browserTab)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                if (dir.exists() && dir.isDirectory) currentPath = dir
            }
            loadFiles(currentPath)
        } catch (e: Exception) {
            currentPath = Environment.getExternalStorageDirectory()
            loadFiles(currentPath)
        }

        val tabUrls = prefs.getStringSet(KEY_TAB_URLS, null)
        if (tabUrls != null && tabUrls.isNotEmpty() && tabUrls.isNotEmpty()) {
            tabUrls.forEach { url ->
                if (url.isNotEmpty()) {
                    addNewTab(url, false)
                }
            }
            if (webTabs.isNotEmpty()) {
                currentTabIndex = prefs.getInt(KEY_CURRENT_TAB, 0).coerceIn(0, webTabs.size - 1)
                switchTab(currentTabIndex)
            } else {
                addNewTab("https://www.google.com", true)
            }
        } else {
            addNewTab("https://www.google.com", true)
        }

        isFileManagerActive = prefs.getBoolean(KEY_IS_FILE_MANAGER, true)
        if (isFileManagerActive) {
            switchToFileManager()
            if (tabLayout.childCount > 0) {
                (tabLayout.getChildAt(0) as? TextView)?.setBackgroundColor(Color.parseColor("#333333"))
                (tabLayout.getChildAt(1) as? TextView)?.setBackgroundColor(Color.TRANSPARENT)
            }
        } else {
            switchToBrowser()
            if (tabLayout.childCount > 1) {
                (tabLayout.getChildAt(1) as? TextView)?.setBackgroundColor(Color.parseColor("#333333"))
                (tabLayout.getChildAt(0) as? TextView)?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()
        webTabs.forEach { 
            try {
                it.webView.onPause()
            } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        webTabs.forEach { 
            try {
                it.webView.onResume()
            } catch (e: Exception) { }
        }
    }

    private fun saveState() {
        try {
            prefs.edit().apply {
                putString(KEY_CURRENT_PATH, currentPath.absolutePath)
                putStringSet(KEY_TAB_URLS, webTabs.mapNotNull { it.webView.url?.toString() ?: "about:blank" }.toSet())
                putInt(KEY_CURRENT_TAB, currentTabIndex)
                putBoolean(KEY_IS_FILE_MANAGER, isFileManagerActive)
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
                    tabLayout.getChildAt(i).setBackgroundColor(Color.TRANSPARENT)
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
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
        }

        layout.addView(topBar)
        layout.addView(storageInfo)
        layout.addView(fileListView)
        return layout
    }

    private fun loadFiles(dir: File) {
        try {
            currentPath = dir
            pathText.text = dir.absolutePath.replace(Environment.getExternalStorageDirectory().absolutePath, "/storage/emulated/0")
            storageInfo.text = "Free: ${getSize(dir.freeSpace)} | Total: ${getSize(dir.totalSpace)}"

            val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

            val adapter = object : ArrayAdapter<File>(this, android.R.layout.simple_list_item_2, android.R.id.text1, files) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val file = getItem(position)!!
                    val text1 = view.findViewById<TextView>(android.R.id.text1)
                    val text2 = view.findViewById<TextView>(android.R.id.text2)

                    val icon = when {
                        file.isDirectory -> "📁"
                        file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true) || file.name.endsWith(".avi", true) -> "🎬"
                        file.name.endsWith(".pdf", true) -> "📕"
                        file.name.endsWith(".txt", true) -> "📝"
                        file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) -> "🖼️"
                        else -> "📄"
                    }

                    text1.text = "$icon ${file.name}"
                    text1.setTextColor(Color.BLACK)
                    text1.textSize = 14f
                    text2.text = "${getSize(file.length())} | ${SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}"
                    text2.textSize = 11f
                    return view
                }
            }

            fileListView.adapter = adapter
            fileListView.setOnItemClickListener { _, _, position, _ ->
                val file = files[position]
                when {
                    file.isDirectory -> loadFiles(file)
                    file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true) || file.name.endsWith(".avi", true) -> playVideo(file)
                    file.name.endsWith(".txt", true) -> openTextFile(file)
                    file.name.endsWith(".pdf", true) -> openPdfFile(file)
                    else -> toast("Unsupported file")
                }
            }

            fileListView.setOnItemLongClickListener { _, _, position, _ ->
                showFileOptions(files[position])
                true
            }
        } catch (e: Exception) {
            toast("Error loading files: ${e.message}")
        }
    }

    private fun goUp() {
        try {
            val parent = currentPath.parentFile
            if (parent != null && parent.canRead()) {
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
                    if (newFolder.mkdir()) {
                        toast("Created")
                        loadFiles(currentPath)
                    } else toast("Failed")
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
                    toast("Deleted")
                    loadFiles(currentPath)
                } else toast("Delete failed")
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
                    toast("Renamed")
                    loadFiles(currentPath)
                } else toast("Rename failed")
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
        AlertDialog.Builder(this).setTitle("Details").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun playVideo(file: File) {
        try {
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            
            // Custom VideoView with better controls
            val videoView = VideoView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    toast("Cannot play this video")
                    true
                }
            }
            
            // Control Panel
            val controlPanel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
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
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            videoView.seekTo(progress)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            
            val timeText = TextView(this).apply {
                text = "00:00 / 00:00"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
            }
            
            val fullscreenBtn = Button(this).apply {
                text = "⛶"
                textSize = 20f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    // Toggle fullscreen
                    val isFullscreen = dialog.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
                    if (isFullscreen) {
                        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    } else {
                        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    }
                }
            }
            
            val closeBtn = Button(this).apply {
                text = "✕"
                textSize = 20f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnClickListener { 
                    videoView.stopPlayback()
                    dialog.dismiss() 
                }
            }
            
            controlPanel.addView(playPauseBtn)
            controlPanel.addView(seekBar)
            controlPanel.addView(timeText)
            controlPanel.addView(fullscreenBtn)
            controlPanel.addView(closeBtn)
            
            layout.addView(videoView)
            layout.addView(controlPanel)
            
            // Update seekbar and time
            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        if (videoView.isPlaying) {
                            val current = videoView.currentPosition
                            val duration = videoView.duration
                            seekBar.max = duration
                            seekBar.progress = current
                            timeText.text = "${formatTime(current)} / ${formatTime(duration)}"
                        }
                    }
                }
            }, 0, 1000)
            
            dialog.setOnDismissListener { timer.cancel() }
            dialog.setContentView(layout)
            dialog.show()
            
            // Set landscape orientation for video
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            dialog.setOnDismissListener {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                timer.cancel()
            }
            
        } catch (e: Exception) {
            toast("Error playing video: ${e.message}")
        }
    }
    
    private fun formatTime(millis: Int): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%02d:%02d", minutes, seconds % 60)
        }
    }

    private fun openTextFile(file: File) {
        try {
            val text = file.readText()
            val scrollView = ScrollView(this)
            val textView = TextView(this).apply {
                this.text = text
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setTextIsSelectable(true)
                textSize = 14f
            }
            scrollView.addView(textView)
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun openPdfFile(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            toast("PDF requires Android 5.0+")
            return
        }
        try {
            val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            val pageCount = renderer.pageCount
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
            val closeBtn = Button(this).apply {
                text = "✕"
                textSize = 20f
                setOnClickListener { 
                    try { renderer.close() } catch (e: Exception) { }
                    dialog.dismiss() 
                }
            }
            topBar.addView(titleText)
            topBar.addView(closeBtn)

            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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
                } catch (e: Exception) { }
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
            toast("PDF Error: ${e.message}")
        }
    }

    private fun pdfToJpg(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            toast("PDF to JPG requires Android 5.0+")
            return
        }
        Thread {
            try {
                val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
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
                    toast("Saved to ${outputDir.name}")
                    loadFiles(currentPath)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Error: ${e.message}") }
            }
        }.start()
    }

    // ==================== BROWSER ====================
    private fun createBrowserView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabContainer)

        val newTabBtn = Button(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { addNewTab("https://www.google.com", true) }
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

        urlBarLayout.addView(urlBar)
        urlBarLayout.addView(goBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            max = 100
            visibility = View.GONE
        }

        urlLayout.addView(urlBarLayout)
        urlLayout.addView(progressBar)

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        layout.addView(tabBar)
        layout.addView(urlLayout)
        layout.addView(webContainer)
        return layout
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addNewTab(url: String, switch: Boolean = true) {
        try {
            val webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                
                settings.apply {
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
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
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
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (webTabs.indexOfFirst { it.webView == view } == currentTabIndex) {
                            progressBar.progress = newProgress
                            progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        val index = webTabs.indexOfFirst { it.webView == view }
                        if (index != -1 && title != null) {
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
                        decorView.addView(customView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                        tabLayout.visibility = View.GONE
                    }

                    override fun onHideCustomView() {
                        if (customView == null) return
                        val decorView = window.decorView as FrameLayout
                        decorView.removeView(customView)
                        customView = null

                        requestedOrientation = originalOrientation
                        window.decorView.systemUiVisibility = originalSystemUiVisibility
                        tabLayout.visibility = View.VISIBLE
                        customViewCallback?.onCustomViewHidden()
                    }

                    override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                        fileUploadCallback = filePathCallback
                        val intent = Intent(Intent.ACTION_GET_CONTENT)
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        intent.type = "*/*"
                        fileChooserLauncher.launch(intent)
                        return true
                    }
                }

                // Improved Download Manager
                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, contentLength ->
                    try {
                        val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // For Android 10+
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mimetype)
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            }
                            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            if (uri != null) {
                                val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                request.setDestinationUri(uri)
                                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                request.setMimeType(mimetype)
                                request.setTitle(fileName)
                                request.setDescription("Downloading $fileName")
                                
                                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                toast("Download started: $fileName")
                            }
                        } else {
                            // For Android 9 and below
                            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                .setTitle(fileName)
                                .setDescription("Downloading $fileName")
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            
                            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            dm.enqueue(request)
                            toast("Download started: $fileName")
                        }
                    } catch (e: Exception) {
                        toast("Download failed: ${e.message}")
                    }
                }

                webView.webViewClient = webViewClient
                webView.webChromeClient = webChromeClient
                loadUrl(url)
            }

            val tab = WebTab(webView, title = "New Tab", url = url)
            webTabs.add(tab)

            val tabButton = Button(this).apply {
                text = "Tab ${webTabs.size}"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#444444"))
                layoutParams = LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT).apply { 
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
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
            if (switch) switchTab(webTabs.size - 1)
        } catch (e: Exception) {
            toast("Error creating tab: ${e.message}")
        }
    }

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (fileUploadCallback != null) {
            val results = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                arrayOf(result.data?.data!!)
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= webTabs.size) return
        currentTabIndex = index
        webContainer.removeAllViews()
        webContainer.addView(webTabs[index].webView)
        urlBar.setText(webTabs[index].webView.url)
        progressBar.visibility = View.GONE
        updateTabTitles()
    }

    private fun closeTab(index: Int): Boolean {
        if (webTabs.size <= 1) {
            toast("Cannot close last tab")
            return false
        }
        try {
            webTabs[index].webView.stopLoading()
            webTabs[index].webView.destroy()
        } catch (e: Exception) { }
        webTabs.removeAt(index)
        tabContainer.removeViewAt(index)
        if (currentTabIndex >= webTabs.size) currentTabIndex = webTabs.size - 1
        switchTab(currentTabIndex)
        return true
    }

    private fun updateTabTitles() {
        for (i in 0 until tabContainer.childCount) {
            val btn = tabContainer.getChildAt(i) as? Button ?: continue
            val title = if (i < webTabs.size) webTabs[i].title else "Tab"
            btn.text = if (title.length > 7) title.substring(0, 6) + "…" else title
            btn.setBackgroundColor(if (i == currentTabIndex) Color.parseColor("#6200EE") else Color.parseColor("#444444"))
        }
    }

    private fun loadUrlInCurrentTab() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) {
            url = "https://www.google.com"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        try {
            webTabs[currentTabIndex].webView.loadUrl(url)
        } catch (e: Exception) {
            toast("Failed to load URL")
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
        if (currentTabIndex < webTabs.size) {
            try {
                webTabs[currentTabIndex].webView.onResume()
            } catch (e: Exception) { }
        }
    }

    private fun getSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (customView != null) {
            webTabs[currentTabIndex].webView.webChromeClient?.onHideCustomView()
            return
        }

        if (!isFileManagerActive) {
            if (webTabs.isNotEmpty() && webTabs[currentTabIndex].webView.canGoBack()) {
                webTabs[currentTabIndex].webView.goBack()
            } else {
                super.onBackPressed()
            }
        } else {
            try {
                if (currentPath.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                    goUp()
                } else {
                    super.onBackPressed()
                }
            } catch (e: Exception) {
                super.onBackPressed()
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
        } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
