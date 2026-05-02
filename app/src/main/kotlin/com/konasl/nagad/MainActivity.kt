package com.konasl.nagad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.provider.Settings
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
            if (Environment.isExternalStorageManager()) initUI()
            else toast("Storage permission required")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        checkPermissionAndInit()
    }

    private fun checkPermissionAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                storagePermission.launch(intent)
            } else initUI()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            } else initUI()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initUI()
        } else toast("Permission required")
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

        val fileTab = createTabButton("Files", true) { switchToFileManager() }
        val browserTab = createTabButton("Browser", false) { switchToBrowser() }
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
        val savedPath = prefs.getString(KEY_CURRENT_PATH, null)
        if (savedPath != null) {
            val dir = File(savedPath)
            if (dir.exists() && dir.isDirectory) currentPath = dir
        }
        loadFiles(currentPath)

        val tabUrls = prefs.getStringSet(KEY_TAB_URLS, null)
        if (tabUrls != null && tabUrls.isNotEmpty()) {
            tabUrls.forEach { addNewTab(it, false) }
            currentTabIndex = prefs.getInt(KEY_CURRENT_TAB, 0).coerceIn(0, webTabs.size - 1)
            switchTab(currentTabIndex)
        } else {
            addNewTab("https://moviebox.ph", true)
        }

        isFileManagerActive = prefs.getBoolean(KEY_IS_FILE_MANAGER, true)
        if (isFileManagerActive) {
            switchToFileManager()
            (tabLayout.getChildAt(0) as TextView).setBackgroundColor(Color.parseColor("#333333"))
            (tabLayout.getChildAt(1) as TextView).setBackgroundColor(Color.TRANSPARENT)
        } else {
            switchToBrowser()
            (tabLayout.getChildAt(1) as TextView).setBackgroundColor(Color.parseColor("#333333"))
            (tabLayout.getChildAt(0) as TextView).setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()
        webTabs.forEach { it.webView.onPause() }
    }

    override fun onResume() {
        super.onResume()
        webTabs.forEach { it.webView.onResume() }
    }

    private fun saveState() {
        prefs.edit().apply {
            putString(KEY_CURRENT_PATH, currentPath.absolutePath)
            putStringSet(KEY_TAB_URLS, webTabs.map { it.webView.url?.toString() ?: "about:blank" }.toSet())
            putInt(KEY_CURRENT_TAB, currentTabIndex)
            putBoolean(KEY_IS_FILE_MANAGER, isFileManagerActive)
            apply()
        }
    }

    private fun createTabButton(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
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
        }

        val backBtn = Button(this).apply {
            text = "←"
            setOnClickListener { goUp() }
        }

        pathText = TextView(this).apply {
            text = "/"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            ellipsize = android.text.TextUtils.TruncateAt.START
            setSingleLine(true)
        }

        val newFolderBtn = Button(this).apply {
            text = "+"
            setOnClickListener { createNewFolder() }
        }

        topBar.addView(backBtn)
        topBar.addView(pathText)
        topBar.addView(newFolderBtn)

        storageInfo = TextView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 12f
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
        currentPath = dir
        pathText.text = dir.absolutePath.replace(Environment.getExternalStorageDirectory().absolutePath, "/storage")
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
                text2.text = "${getSize(file.length())} | ${SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}"
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
    }

    private fun goUp() {
        val parent = currentPath.parentFile
        if (parent != null && parent.canRead() && parent.absolutePath.startsWith(Environment.getExternalStorageDirectory().absolutePath)) {
            loadFiles(parent)
        }
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
                    toast("Renamed")
                    loadFiles(currentPath)
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
        AlertDialog.Builder(this).setTitle("Details").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun playVideo(file: File) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = FrameLayout(this)
        val videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            setVideoPath(file.absolutePath)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                start()
            }
        }
        val closeBtn = Button(this).apply {
            text = "X"
            setBackgroundColor(Color.parseColor("#AA000000"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(16), dp(16), 0, 0)
            }
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(videoView)
        layout.addView(closeBtn)
        dialog.setContentView(layout)
        dialog.show()
    }

    private fun openTextFile(file: File) {
        try {
            val text = file.readText()
            val scrollView = ScrollView(this)
            val textView = TextView(this).apply {
                this.text = text
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setTextIsSelectable(true)
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
                text = "X"
                setOnClickListener { renderer.close(); dialog.dismiss() }
            }
            topBar.addView(titleText)
            topBar.addView(closeBtn)

            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            var currentPage = 0
            fun renderPage(pageNum: Int) {
                val page = renderer.openPage(pageNum)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                imageView.setImageBitmap(bitmap)
                titleText.text = "${file.name} - ${pageNum + 1}/$pageCount"
                page.close()
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
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
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
        }
        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabContainer)

        val newTabBtn = Button(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { addNewTab("https://moviebox.ph", true) }
        }

        tabBar.addView(tabScroll)
        tabBar.addView(newTabBtn)

        val urlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val urlBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }

        urlBar = EditText(this).apply {
            hint = "Enter URL"
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
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

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
                    if (index != -1) {
                        webTabs[index].title = title ?: "New Tab"
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
                    val intent = fileChooserParams?.createIntent()
                    if (intent != null) {
                        fileUploadCallback = filePathCallback
                        uploadMessage = null
                        fileChooserLauncher.launch(intent)
                        return true
                    }
                    return false
                }
            }

            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                val request = DownloadManager.Request(Uri.parse(url))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                toast("Downloading...")
            }

            loadUrl(url)
        }

        val tab = WebTab(webView, title = "New Tab", url = url)
        webTabs.add(tab)

        val tabButton = Button(this).apply {
            text = "Tab ${webTabs.size}"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(2), 0, dp(2), 0) }
            setOnClickListener { switchTab(webTabs.indexOf(tab)) }
            setOnLongClickListener { closeTab(webTabs.indexOf(tab)); true }
        }
        tabContainer.addView(tabButton)
        if (switch) switchTab(webTabs.size - 1)
    }

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var uploadMessage: ValueCallback<Uri>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (fileUploadCallback != null) {
            fileUploadCallback?.onReceiveValue(if (result.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data) else null)
            fileUploadCallback = null
        } else if (uploadMessage != null) {
            val data = result.data?.data
            uploadMessage?.onReceiveValue(data)
            uploadMessage = null
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
        webTabs[index].webView.destroy()
        webTabs.removeAt(index)
        tabContainer.removeViewAt(index)
        if (currentTabIndex >= webTabs.size) currentTabIndex = webTabs.size - 1
        switchTab(currentTabIndex)
        return true
    }

    private fun updateTabTitles() {
        for (i in 0 until tabContainer.childCount) {
            val btn = tabContainer.getChildAt(i) as Button
            val title = webTabs[i].title
            btn.text = if (title.length > 10) title.substring(0, 10) + "..." else title
            btn.setBackgroundColor(if (i == currentTabIndex) Color.parseColor("#6200EE") else Color.parseColor("#444444"))
        }
    }

    private fun loadUrlInCurrentTab() {
        var url = urlBar.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        webTabs[currentTabIndex].webView.loadUrl(url)
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
            webTabs[currentTabIndex].webView.onResume()
        }
    }

    private fun getSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() {
        if (customView != null) {
            webTabs[currentTabIndex].webView.webChromeClient?.onHideCustomView()
            return
        }

        if (!isFileManagerActive) {
            if (webTabs[currentTabIndex].webView.canGoBack()) {
                webTabs[currentTabIndex].webView.goBack()
            } else {
                super.onBackPressed()
            }
        } else if (currentPath.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
            goUp()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        saveState()
        webTabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
