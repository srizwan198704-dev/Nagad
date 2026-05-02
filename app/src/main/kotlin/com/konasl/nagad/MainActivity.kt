package com.konasl.nagad

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
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

    private lateinit var container: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fileLayout: LinearLayout
    private lateinit var browserLayout: LinearLayout
    private lateinit var playerLayout: FrameLayout
    
    private lateinit var tabLayout: TabLayout
    private lateinit var webFrame: FrameLayout
    private val webList = mutableListOf<WebView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // রানটাইমে থিম এরর প্রতিরোধের জন্য
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
        super.onCreate(savedInstanceState)

        initUI()
        handleBackPress()
        checkStoragePermission()
        
        // শুরুতে ফাইল ম্যানেজার দেখাবে
        switchView("files")
    }

    private fun initUI() {
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        // ফাইল সেকশন
        fileLayout = createFilesUI()
        // ব্রাউজার সেকশন
        browserLayout = createBrowserUI()
        // ভিডিও প্লেয়ার সেকশন
        playerLayout = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        // বটম নেভিগেশন
        bottomNav = BottomNavigationView(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            val m = menu
            m.add(0, 101, 0, "ফাইলস").setIcon(android.R.drawable.ic_menu_save)
            m.add(0, 102, 1, "ব্রাউজার").setIcon(android.R.drawable.ic_menu_search)
            
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    101 -> switchView("files")
                    102 -> switchView("browser")
                }
                true
            }
        }

        val navParams = FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM }

        container.addView(fileLayout)
        container.addView(browserLayout)
        container.addView(playerLayout)
        container.addView(bottomNav, navParams)

        setContentView(container)
    }

    private fun switchView(tag: String) {
        fileLayout.visibility = if (tag == "files") View.VISIBLE else View.GONE
        browserLayout.visibility = if (tag == "browser") View.VISIBLE else View.GONE
        bottomNav.visibility = View.VISIBLE
        if (tag == "files") loadFiles(Environment.getExternalStorageDirectory())
    }

    private fun createFilesUI(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 160)
            val header = TextView(this@MainActivity).apply {
                text = "ফাইল ম্যানেজার"
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(50, 50, 50, 30)
                typeface = Typeface.DEFAULT_BOLD
            }
            val list = ListView(this@MainActivity).apply { tag = "list_view" }
            addView(header)
            addView(list)
        }
    }

    private fun loadFiles(dir: File) {
        val list = fileLayout.findViewWithTag<ListView>("list_view") ?: return
        val items = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        
        list.adapter = object : ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, conv: View?, parent: ViewGroup): View {
                val tv = super.getView(pos, conv, parent) as TextView
                val f = getItem(pos)
                tv.text = f?.name
                tv.setTextColor(if (f?.isDirectory == true) Color.CYAN else Color.WHITE)
                return tv
            }
        }

        list.setOnItemClickListener { _, _, i, _ ->
            val f = items[i]
            if (f.isDirectory) loadFiles(f) else handleFile(f)
        }
    }

    private fun createBrowserUI(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, 160)

            tabLayout = TabLayout(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#222222"))
                setTabTextColors(Color.LTGRAY, Color.WHITE)
                setSelectedTabIndicatorColor(Color.RED)
                tabMode = TabLayout.MODE_SCROLLABLE
            }

            val bar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(10, 10, 10, 10)
                val input = EditText(this@MainActivity).apply {
                    hint = "URL লিখুন"; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                }
                val go = Button(this@MainActivity).apply { text = "Go" }
                val add = Button(this@MainActivity).apply { text = "+" }
                
                add.setOnClickListener { openNewTab("https://www.google.com") }
                go.setOnClickListener {
                    val url = input.text.toString()
                    val finalUrl = if (url.startsWith("http")) url else "https://$url"
                    webList.getOrNull(tabLayout.selectedTabPosition)?.loadUrl(finalUrl)
                }
                addView(input); addView(go); addView(add)
            }

            webFrame = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }

            addView(tabLayout); addView(bar); addView(webFrame)
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t: TabLayout.Tab) {
                    webList.forEachIndexed { i, wv -> wv.visibility = if (i == t.position) View.VISIBLE else View.GONE }
                }
                override fun onTabUnselected(t: TabLayout.Tab) {}
                override fun onTabReselected(t: TabLayout.Tab) {}
            })
            openNewTab("https://www.google.com")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openNewTab(url: String) {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    val i = webList.indexOf(v)
                    if (i != -1) tabLayout.getTabAt(i)?.text = v?.title ?: "ট্যাব"
                }
            }
            loadUrl(url)
            visibility = View.GONE
        }
        webList.add(wv)
        webFrame.addView(wv)
        val tab = tabLayout.newTab().setText("নতুন ট্যাব")
        tabLayout.addTab(tab)
        tab.select()
    }

    private fun handleFile(f: File) {
        if (f.extension.lowercase() in listOf("mp4", "mkv", "webm")) {
            bottomNav.visibility = View.GONE
            fileLayout.visibility = View.GONE
            playerLayout.visibility = View.VISIBLE
            playerLayout.removeAllViews()

            val vv = VideoView(this).apply {
                setVideoPath(f.absolutePath)
                val mc = MediaController(this@MainActivity)
                setMediaController(mc)
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
            }
            playerLayout.addView(vv)
            vv.start()
        } else if (f.extension.lowercase() == "apk") {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", f)
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "ইনস্টল করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerLayout.visibility == View.VISIBLE) {
                    playerLayout.removeAllViews()
                    playerLayout.visibility = View.GONE
                    switchView("files")
                } else if (browserLayout.visibility == View.VISIBLE) {
                    val current = webList.getOrNull(tabLayout.selectedTabPosition)
                    if (current?.canGoBack() == true) current.goBack() else switchView("files")
                } else {
                    finish()
                }
            }
        })
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
