package com.konasl.nagad

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
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
    
    private lateinit var tabLayout: TabLayout
    private lateinit var webContainer: FrameLayout
    private val webViews = mutableListOf<WebView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // থিম এরর ফিক্স করার জন্য এটি সবার আগে থাকতে হবে
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
        super.onCreate(savedInstanceState)
        
        try {
            setupUI()
            checkPermissions()
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
        } catch (e: Exception) {
            Toast.makeText(this, "Setup Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        rootLayout = FrameLayout(this).apply { 
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // ফাইল ও ব্রাউজার সেকশন তৈরি
        fileSection = createFileSection()
        browserSection = createBrowserSection()
        playerSection = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        // বটম নেভিগেশন ফিক্স
        bottomNav = BottomNavigationView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            // আইকন হিসেবে অ্যান্ড্রয়েড সিস্টেম ড্রয়েবল ব্যবহার
            menu.add(Menu.NONE, 1, 0, "Files").setIcon(android.R.drawable.ic_menu_save)
            menu.add(Menu.NONE, 2, 1, "Browser").setIcon(android.R.drawable.ic_menu_search)
            
            itemIconTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            itemTextColor = android.content.res.ColorStateList.valueOf(Color.WHITE)

            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    1 -> showSection("files")
                    2 -> showSection("browser")
                }
                true
            }
        }

        val navParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { 
            gravity = Gravity.BOTTOM 
        }

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
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { 
                setMargins(0, 0, 0, 160) // বটম বারের জন্য জায়গা রাখা
            }
            val title = TextView(this@MainActivity).apply {
                text = "My Files"
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(40, 50, 40, 30)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(title)
            val listView = ListView(this@MainActivity).apply { 
                tag = "file_list"
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            addView(listView)
        }
    }

    private fun refreshFileList(dir: File) {
        val listView = fileSection.findViewWithTag<ListView>("file_list") ?: return
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        
        val adapter = object : ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, files) {
            override fun getView(position: Int, v: View?, p: ViewGroup): View {
                val tv = super.getView(position, v, p) as TextView
                val f = getItem(position)
                tv.text = f?.name
                tv.setTextColor(if (f?.isDirectory == true) Color.CYAN else Color.WHITE)
                tv.setPadding(40, 30, 40, 30)
                return tv
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, i, _ ->
            val f = files[i]
            if (f.isDirectory) refreshFileList(f) else openFile(f)
        }
    }

    private fun createBrowserSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { 
                setMargins(0, 0, 0, 160) 
            }

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
                    hint = "https://"; setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                }
                val btnAdd = Button(this@MainActivity).apply { text = "+" }
                btnAdd.setOnClickListener { addNewTab("https://www.google.com") }
                val btnGo = Button(this@MainActivity).apply { text = "Go" }
                btnGo.setOnClickListener {
                    val url = urlInput.text.toString()
                    val finalUrl = if (url.startsWith("http")) url else "https://$url"
                    webViews.getOrNull(tabLayout.selectedTabPosition)?.loadUrl(finalUrl)
                }
                addView(urlInput); addView(btnGo); addView(btnAdd)
            }

            webContainer = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            }

            addView(tabLayout); addView(controls); addView(webContainer)
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t: TabLayout.Tab) { updateWebVisibility(t.position) }
                override fun onTabUnselected(t: TabLayout.Tab) {}
                override fun onTabReselected(t: TabLayout.Tab) {}
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
                databaseEnabled = true
                // Desktop User Agent
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
        val tab = tabLayout.newTab().setText("Tab ${webViews.size}")
        tabLayout.addTab(tab)
        tab.select()
    }

    private fun updateWebVisibility(index: Int) {
        webViews.forEachIndexed { i, wv -> wv.visibility = if (i == index) View.VISIBLE else View.GONE }
    }

    private fun openFile(file: File) {
        val ext = file.extension.lowercase()
        if (ext in listOf("mp4", "mkv", "3gp", "webm")) {
            bottomNav.visibility = View.GONE
            fileSection.visibility = View.GONE
            playerSection.visibility = View.VISIBLE
            playerSection.removeAllViews()

            val videoView = VideoView(this).apply {
                setVideoPath(file.absolutePath)
                val mc = MediaController(this@MainActivity)
                mc.setAnchorView(this)
                setMediaController(mc)
                // Landscape Frame Fix
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
            }
            playerSection.addView(videoView)
            videoView.start()
        } else if (ext == "apk") {
            installApk(file)
        }
    }

    private fun closePlayer() {
        playerSection.removeAllViews()
        playerSection.visibility = View.GONE
        showSection("files")
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot install APK", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
