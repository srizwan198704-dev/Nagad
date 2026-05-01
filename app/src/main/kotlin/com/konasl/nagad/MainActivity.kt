package com.konasl.nagad

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.webkit.*
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import android.widget.LinearLayout
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var customWebView: CustomWebView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainApp()
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
    
    fun setOrientation(orientation: Int) {
        requestedOrientation = orientation
    }
    
    fun resetOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Storage, "Files") },
                    label = { Text("Files") },
                    selected = false,
                    onClick = { navController.navigate("files") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Language, "Browser") },
                    label = { Text("Browser") },
                    selected = false,
                    onClick = { navController.navigate("browser") }
                )
            }
        }
    ) { padding ->
        NavHost(navController, "files", Modifier.padding(padding)) {
            composable("files") { FileManagerScreen() }
            composable("browser") { TabbedBrowserScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var currentPath by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
    val files = currentPath.listFiles()?.sortedByDescending { it.isDirectory } ?: emptyList()
    
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileType by remember { mutableStateOf("") }
    var showOptionsPopup by remember { mutableStateOf<File?>(null) }

    if (viewingFile != null) {
        InternalPlayer(viewingFile!!, fileType) { 
            viewingFile = null
            activity?.resetOrientation()
        }
    } else {
        Column {
            TopAppBar(
                title = { Text(currentPath.name.ifEmpty { "Internal Storage" }) },
                navigationIcon = {
                    if (currentPath != Environment.getExternalStorageDirectory()) {
                        IconButton(onClick = { currentPath = currentPath.parentFile!! }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                }
            )
            LazyColumn {
                items(files) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        leadingContent = {
                            val icon = when {
                                file.isDirectory -> Icons.Default.Folder
                                file.extension.lowercase() in listOf("mp4", "mkv", "3gp") -> Icons.Default.PlayCircle
                                file.extension.lowercase() in listOf("jpg", "png", "webp") -> Icons.Default.Image
                                else -> Icons.Default.FileOpen
                            }
                            Icon(icon, null, tint = if (file.isDirectory) Color.Cyan else Color.White)
                        },
                        trailingContent = {
                            IconButton(onClick = { showOptionsPopup = file }) {
                                Icon(Icons.Default.MoreVert, null, tint = Color.White)
                            }
                        },
                        modifier = Modifier.clickable {
                            if (file.isDirectory) {
                                currentPath = file
                            } else {
                                val ext = file.extension.lowercase()
                                when {
                                    ext == "apk" -> installApk(context, file)
                                    ext in listOf("mp4", "mkv", "3gp") -> { 
                                        viewingFile = file
                                        fileType = "video"
                                        activity?.setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                                    }
                                    ext in listOf("jpg", "jpeg", "png", "webp") -> { viewingFile = file; fileType = "image" }
                                    ext in listOf("txt", "json", "log", "js", "css", "html", "xml") -> { viewingFile = file; fileType = "text" }
                                    else -> Toast.makeText(context, "Format not supported internally", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
        
        if (showOptionsPopup != null) {
            AlertDialog(
                onDismissRequest = { showOptionsPopup = null },
                title = { Text(showOptionsPopup?.name ?: "File") },
                text = { 
                    Column {
                        Text("Size: ${formatFileSize(showOptionsPopup?.length() ?: 0)}")
                        Text("Type: ${showOptionsPopup?.extension?.ifEmpty { "Folder" }}")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showOptionsPopup?.deleteRecursively()
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                            showOptionsPopup = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOptionsPopup = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

@Composable
fun LoadImageFromFile(file: File, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(file) {
        scope.launch {
            withContext(Dispatchers.IO) {
                bitmap = BitmapFactory.decodeFile(file.absolutePath)
            }
        }
    }
    
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun InternalPlayer(file: File, type: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var showFullscreen by remember { mutableStateOf(false) }
    
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { 
                    onBack()
                    activity?.resetOrientation()
                }) { 
                    Icon(Icons.Default.Close, null, tint = Color.White) 
                }
                
                if (type == "video") {
                    IconButton(onClick = {
                        showFullscreen = !showFullscreen
                        if (showFullscreen) {
                            activity?.setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        } else {
                            activity?.setOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                        }
                    }) { 
                        Icon(
                            if (showFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            null,
                            tint = Color.White
                        ) 
                    }
                }
            }
            
            when (type) {
                "video" -> {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(file.absolutePath)
                                val mc = MediaController(ctx)
                                mc.setAnchorView(this)
                                setMediaController(mc)
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize().weight(1f)
                    )
                }
                "image" -> {
                    Box(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadImageFromFile(file = file, modifier = Modifier.fillMaxSize())
                    }
                }
                "text" -> {
                    val textContent = remember(file) {
                        try {
                            file.readText().take(50000)
                        } catch (e: Exception) {
                            "Unable to read file: ${e.message}"
                        }
                    }
                    
                    Text(
                        text = textContent,
                        color = Color.Green,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                            .weight(1f)
                    )
                }
            }
        }
    }
}

fun installApk(context: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Error installing APK: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// কাস্টম ওয়েবভিউ ক্লাস ভিডিও পপআপ সাপোর্ট সহ
@SuppressLint("SetJavaScriptEnabled")
class CustomWebView(context: android.content.Context) : WebView(context) {
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalSystemUiVisibility = 0
    private var originalOrientation = 0
    private var isFullscreen = false
    private var videoContainer: FrameLayout? = null
    
    init {
        setupWebView()
    }
    
    private fun setupWebView() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                
                customView = view
                customViewCallback = callback
                isFullscreen = true
                
                // ভিডিও কন্টেইনার তৈরি
                videoContainer = FrameLayout(context).apply {
                    addView(view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
                
                (context as? android.app.Activity)?.apply {
                    // ওরিয়েন্টেশন পরিবর্তন
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    
                    // ভিডিও ভিউ যোগ করা
                    (window.decorView as ViewGroup).addView(videoContainer)
                    
                    // সিস্টেম UI লুকানো
                    originalSystemUiVisibility = window.decorView.systemUiVisibility
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }
            }
            
            override fun onHideCustomView() {
                if (customView == null) return
                
                (context as? android.app.Activity)?.apply {
                    // ওরিয়েন্টেশন রিস্টোর
                    requestedOrientation = originalOrientation
                    
                    // ভিডিও ভিউ রিমুভ
                    videoContainer?.let { (window.decorView as ViewGroup).removeView(it) }
                    videoContainer = null
                    
                    // সিস্টেম UI রিস্টোর
                    window.decorView.systemUiVisibility = originalSystemUiVisibility
                }
                
                customView = null
                customViewCallback?.onCustomViewHidden()
                isFullscreen = false
            }
            
            override fun getDefaultVideoPoster(): Bitmap? {
                return null
            }
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
            }
        }
        
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // ভিডিও প্লেয়ার সাপোর্টের জন্য JavaScript ইঞ্জেক্ট
                view?.evaluateJavascript(
                    """
                    (function() {
                        var videos = document.querySelectorAll('video');
                        for(var i = 0; i < videos.length; i++) {
                            videos[i].setAttribute('controls', 'true');
                            videos[i].setAttribute('webkit-playsinline', 'true');
                            videos[i].setAttribute('playsinline', 'true');
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.let { uri ->
                    val urlString = uri.toString()
                    if (urlString.startsWith("http") || urlString.startsWith("https")) {
                        view?.loadUrl(urlString)
                        return true
                    }
                }
                return false
            }
        }
    }
    
    fun isVideoFullscreen(): Boolean = isFullscreen
    
    fun exitFullscreen() {
        if (isFullscreen) {
            webChromeClient.onHideCustomView()
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
            exitFullscreen()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun TabbedBrowserScreen() {
    var tabs by remember { mutableStateOf(mutableListOf("https://www.google.com")) }
    var activeTabIndex by remember { mutableIntStateOf(0) }
    var showTabList by remember { mutableStateOf(false) }
    var showBrowserMenu by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(TextFieldValue(tabs[activeTabIndex])) }
    var webViewRef by remember { mutableStateOf<CustomWebView?>(null) }
    val context = LocalContext.current

    Column {
        // URL এড্রেসবার
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // URL ইনপুট ফিল্ড
                TextField(
                    value = currentUrl.text,
                    onValueChange = { currentUrl = TextFieldValue(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter URL (e.g., google.com)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // GO বাটন
                Button(
                    onClick = {
                        var url = currentUrl.text.trim()
                        if (url.isNotEmpty()) {
                            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                url = if (url.contains(".")) {
                                    "https://$url"
                                } else {
                                    "https://$url.com"
                                }
                            }
                            currentUrl = TextFieldValue(url)
                            tabs = tabs.toMutableList().apply { 
                                this[activeTabIndex] = url 
                            }
                            webViewRef?.loadUrl(url)
                        }
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Go")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Go")
                }
            }
        }
        
        // ব্রাউজার কন্ট্রোল বার
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { webViewRef?.goBack() }) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
            
            IconButton(onClick = { webViewRef?.goForward() }) {
                Icon(Icons.Default.ArrowForward, "Forward")
            }
            
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Default.Refresh, "Refresh")
            }
            
            IconButton(onClick = {
                val homeUrl = "https://www.google.com"
                currentUrl = TextFieldValue(homeUrl)
                tabs = tabs.toMutableList().apply { 
                    this[activeTabIndex] = homeUrl 
                }
                webViewRef?.loadUrl(homeUrl)
            }) {
                Icon(Icons.Default.Home, "Home")
            }
            
            IconButton(onClick = { 
                val fullscreenStatus = webViewRef?.isVideoFullscreen() ?: false
                if (fullscreenStatus) {
                    webViewRef?.exitFullscreen()
                } else {
                    showTabList = true
                }
            }) {
                Icon(Icons.Default.Tab, "Tabs")
            }
            
            IconButton(onClick = { 
                val newUrl = "https://www.google.com"
                tabs = tabs.toMutableList().apply { add(newUrl) }
                activeTabIndex = tabs.size - 1
                currentUrl = TextFieldValue(newUrl)
                webViewRef?.loadUrl(newUrl)
            }) {
                Icon(Icons.Default.Add, "New Tab")
            }
            
            IconButton(onClick = { showBrowserMenu = true }) {
                Icon(Icons.Default.MoreVert, "Menu")
            }
        }

        // ট্যাব লিস্ট ডায়ালগ
        if (showTabList) {
            AlertDialog(
                onDismissRequest = { showTabList = false },
                title = { Text("Open Tabs") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(tabs) { index, url ->
                            ListItem(
                                headlineContent = { 
                                    Text(url.take(50) + if (url.length > 50) "..." else "", maxLines = 1) 
                                },
                                supportingContent = {
                                    if (index == activeTabIndex) {
                                        Text("Active", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                modifier = Modifier.clickable { 
                                    activeTabIndex = index
                                    currentUrl = TextFieldValue(tabs[index])
                                    webViewRef?.loadUrl(tabs[index])
                                    showTabList = false
                                },
                                trailingContent = {
                                    if (tabs.size > 1) {
                                        IconButton(onClick = { 
                                            tabs = tabs.toMutableList().apply { removeAt(index) }
                                            if (activeTabIndex >= tabs.size) activeTabIndex = tabs.size - 1
                                            if (activeTabIndex < 0) activeTabIndex = 0
                                            if (tabs.isNotEmpty()) {
                                                currentUrl = TextFieldValue(tabs[activeTabIndex])
                                                webViewRef?.loadUrl(tabs[activeTabIndex])
                                            }
                                        }) { 
                                            Icon(Icons.Default.Close, null) 
                                        }
                                    }
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTabList = false }) { Text("Close") }
                }
            )
        }
        
        // ব্রাউজার মেনু ডায়ালগ
        if (showBrowserMenu) {
            AlertDialog(
                onDismissRequest = { showBrowserMenu = false },
                title = { Text("Browser Options") },
                text = {
                    Column {
                        TextButton(onClick = {
                            val currentPageUrl = webViewRef?.url ?: tabs[activeTabIndex]
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, currentPageUrl)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share URL"))
                            showBrowserMenu = false
                        }) {
                            Text("📤 Share URL")
                        }
                        TextButton(onClick = {
                            webViewRef?.reload()
                            showBrowserMenu = false
                            Toast.makeText(context, "Page Refreshed", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("🔄 Refresh")
                        }
                        TextButton(onClick = {
                            val currentUrl = webViewRef?.url ?: tabs[activeTabIndex]
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                            showBrowserMenu = false
                        }) {
                            Text("🌐 Open in External Browser")
                        }
                        TextButton(onClick = {
                            webViewRef?.settings?.javaScriptEnabled = true
                            showBrowserMenu = false
                            Toast.makeText(context, "JavaScript Enabled", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("⚙️ Enable JavaScript")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBrowserMenu = false }) { Text("Close") }
                }
            )
        }

        // ওয়েবভিউ
        AndroidView(
            factory = { ctx ->
                CustomWebView(ctx).apply {
                    webViewRef = this
                    loadUrl(tabs.getOrElse(activeTabIndex) { "https://www.google.com" })
                }
            },
            update = { webView ->
                webViewRef = webView
                val currentTabUrl = tabs.getOrElse(activeTabIndex) { "https://www.google.com" }
                if (webView.url != currentTabUrl) {
                    webView.loadUrl(currentTabUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
