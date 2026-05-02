package com.konasl.nagad

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Rational
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fileSection: LinearLayout
    private lateinit var browserSection: LinearLayout
    private lateinit var playerSection: FrameLayout
    
    // Browser States
    private lateinit var tabLayout: TabLayout
    private lateinit var webContainer: FrameLayout
    private val webViews = mutableListOf<WebView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()
        setupUI()
        
        // Default View
        showSection("files")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerSection.visibility == View.VISIBLE) {
                    closePlayer()
                } else if (browserSection.visibility == View.VISIBLE) {
                    val currentWeb = webViews.getOrNull(tabLayout.selectedTabPosition)
                    if (currentWeb?.canGoBack() == true) {
                        currentWeb.goBack()
                    } else {
                        showSection("files")
                    }
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupUI() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        fileSection = createFileSection()
        browserSection = createBrowserSection()
        playerSection = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        bottomNav = BottomNavigationView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            // মেনু আইটেম তৈরি
            menu.add(Menu.NONE, 1, 0, "Files").setIcon(android.R.drawable.ic_menu_save)
            menu.add(Menu.NONE, 2, 1, "Browser").setIcon(android.R.drawable.ic_menu_search)
            
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    1 -> showSection("files")
                    2 -> showSection("browser")
                }
                true
            }
        }

        val navParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            150 // নির্দিষ্ট উচ্চতা
        ).apply { gravity = Gravity.BOTTOM }

        rootLayout.addView(fileSection)
        rootLayout.addView(browserSection)
        rootLayout.addView(playerSection)
        rootLayout.addView(bottomNav, navParams)

        setContentView(rootLayout)
    }

    private fun showSection(type: String) {
        fileSection.visibility = if (type == "files") View.VISIBLE else View.GONE
        browserSection.visibility = if (type == "browser") View.VISIBLE else View.GONE
        bottomNav.visibility = View.VISIBLE
        if (type == "files") refreshFileList(Environment.getExternalStorageDirectory())
    }

    private fun createFileSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(this@MainActivity).apply {
                text = "File Manager"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(40, 40, 40, 40)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(title)
            
            val listView = ListView(this@MainActivity).apply {
                tag = "file_list"
            }
            addView(listView)
        }
    }

    private fun refreshFileList(dir: File) {
        val listView = fileSection.findViewWithTag<ListView>("file_list")
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        
        val adapter = object : ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, files) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val file = getItem(position)
                view.text = file?.name
                view.setTextColor(if (file?.isDirectory == true) Color.CYAN else Color.WHITE)
                return view
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val file = files[position]
            if (file.isDirectory) refreshFileList(file)
            else openFile(file)
        }
        
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val file = files[position]
            AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete ${file.name}?")
                .setPositiveButton("Yes") { _, _ -> 
                    file.deleteRecursively()
                    refreshFileList(dir)
                }
                .setNegativeButton("No", null)
                .show()
            true
        }
    }

    private fun createBrowserSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            tabLayout = TabLayout(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#1F1F1F"))
                setTabTextColors(Color.GRAY, Color.WHITE)
                setSelectedTabIndicatorColor(Color.RED)
                tabMode = TabLayout.MODE_SCROLLABLE
            }
            
            val controls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(10, 10, 10, 10)
                
                val urlInput = EditText(this@MainActivity).apply {
                    hint = "Enter URL"
                    setHintTextColor(Color.GRAY)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    tag = "url_input"
                }
                
                val btnAdd = Button(this@MainActivity).apply { text = "+" }
                btnAdd.setOnClickListener { addNewTab("https://www.google.com") }

                val btnGo = Button(this@MainActivity).apply { text = "Go" }
                btnGo.setOnClickListener {
                    val url = urlInput.text.toString()
                    val finalUrl = if (url.contains("://")) url else "https://$url"
                    webViews.getOrNull(tabLayout.selectedTabPosition)?.loadUrl(finalUrl)
                }
                
                addView(urlInput)
                addView(btnGo)
                addView(btnAdd)
            }

            webContainer = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            }

            addView(tabLayout)
            addView(controls)
            addView(webContainer)

            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { updateWebVisibility(tab.position) }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            addNewTab("https://www.google.com")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addNewTab(url: String) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val index = webViews.indexOf(view)
                    if (index != -1) tabLayout.getTabAt(index)?.text = view?.title ?: "Tab"
                }
            }
            loadUrl(url)
            visibility = View.GONE
        }

        webViews.add(webView)
        webContainer.addView(webView)
        val newTab = tabLayout.newTab().setText("New Tab")
        tabLayout.addTab(newTab)
        newTab.select()
    }

    private fun updateWebVisibility(index: Int) {
        webViews.forEachIndexed { i, webView ->
            webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

    private fun openFile(file: File) {
        val ext = file.extension.lowercase()
        if (ext == "mp4" || ext == "mkv") {
            bottomNav.visibility = View.GONE
            fileSection.visibility = View.GONE
            playerSection.visibility = View.VISIBLE
            playerSection.removeAllViews()

            val videoView = VideoView(this).apply {
                setVideoPath(file.absolutePath)
                val mc = MediaController(this@MainActivity)
                mc.setAnchorView(this)
                setMediaController(mc)
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
            }
            playerSection.addView(videoView)
            videoView.start()
        } else if (ext == "apk") {
            installApk(file)
        }
    }

    private fun closePlayer() {
        playerSection.visibility = View.GONE
        showSection("files")
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
