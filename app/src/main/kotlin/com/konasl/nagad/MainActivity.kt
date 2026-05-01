package com.konasl.nagad

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
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
    
    // Alphabetically sorted files
    val sortedFiles = remember(currentPath) {
        currentPath.listFiles()?.let { files ->
            files.sortedWith(compareBy(
                { !it.isDirectory },
                { it.name.lowercase() }
            ))
        } ?: emptyList()
    }
    
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileType by remember { mutableStateOf("") }
    var showOptionsPopup by remember { mutableStateOf<File?>(null) }
    var showVideoPopup by remember { mutableStateOf(false) }
    var popupVideoFile by remember { mutableStateOf<File?>(null) }

    if (viewingFile != null && !showVideoPopup) {
        InternalPlayer(viewingFile!!, fileType) { 
            viewingFile = null
            activity?.resetOrientation()
        }
    } else if (showVideoPopup && popupVideoFile != null) {
        AlertDialog(
            onDismissRequest = { 
                showVideoPopup = false
                popupVideoFile = null
                activity?.resetOrientation()
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoPopupPlayer(
                    file = popupVideoFile!!,
                    onClose = { 
                        showVideoPopup = false
                        popupVideoFile = null
                        activity?.resetOrientation()
                    }
                )
            }
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
                },
                actions = {
                    Text(
                        text = "${sortedFiles.size} items",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
            LazyColumn {
                items(sortedFiles) { file ->
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
                        supportingContent = {
                            if (!file.isDirectory) {
                                Text(
                                    formatFileSize(file.length()),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { showOptionsPopup = file }) {
                                Icon(Icons.Default.MoreVert, null, tint = Color.White)
                            }
                        },
                        modifier = Modifier
                            .pointerInput(file) {
                                detectLongPressAndPopup(file) { selectedFile ->
                                    if (selectedFile.extension.lowercase() in listOf("mp4", "mkv", "3gp")) {
                                        popupVideoFile = selectedFile
                                        showVideoPopup = true
                                        activity?.setOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                                    } else {
                                        showOptionsPopup = selectedFile
                                    }
                                }
                            }
                            .clickable {
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
                        Text("Path: ${showOptionsPopup?.parent}")
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

suspend fun PointerInputScope.detectLongPressAndPopup(
    file: File,
    onLongPress: (File) -> Unit
) {
    var isLongPressHandled = false
    var startTime = System.currentTimeMillis()
    
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.pressed }) {
                startTime = System.currentTimeMillis()
                delay(500)
                if (!isLongPressHandled && System.currentTimeMillis() - startTime >= 500) {
                    isLongPressHandled = true
                    onLongPress(file)
                }
            } else {
                isLongPressHandled = false
            }
        }
    }
}

@Composable
fun VideoPopupPlayer(file: File, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
            modifier = Modifier.fillMaxSize()
        )
        
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White)
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

data class TabState(
    val url: String,
    val title: String,
    val scrollPosition: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean
)

@SuppressLint("SetJavaScriptEnabled")
class CustomWebView(context: android.content.Context) : WebView(context) {
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility = 0
    private var isFullscreen = false
    private var videoContainer: FrameLayout? = null
    private var onLongClickListener: ((String) -> Unit)? = null
    private var isDesktopMode = false
    
    private var _onPageStartedListener: ((String) -> Unit)? = null
    private var _onPageFinishedListener: ((String) -> Unit)? = null
    private var _onProgressChangedListener: ((Int) -> Unit)? = null
    private var _onTitleChangedListener: ((String) -> Unit)? = null
    
    fun setOnPageStartedListener(listener: (String) -> Unit) { _onPageStartedListener = listener }
    fun setOnPageFinishedListener(listener: (String) -> Unit) { _onPageFinishedListener = listener }
    fun setOnProgressChangedListener(listener: (Int) -> Unit) { _onProgressChangedListener = listener }
    fun setOnTitleChangedListener(listener: (String) -> Unit) { _onTitleChangedListener = listener }
    
    init {
        setupWebView()
    }
    
    fun setDesktopMode(enabled: Boolean) {
        isDesktopMode = enabled
        if (enabled) {
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            settings.userAgentString = null
        }
        reload()
    }
    
    fun isDesktopMode(): Boolean = isDesktopMode
    
    fun setOnLongClickListener(listener: (String) -> Unit) {
        onLongClickListener = listener
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
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
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
                
                videoContainer = FrameLayout(context).apply {
                    addView(view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
                
                (context as? android.app.Activity)?.apply {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    (window.decorView as ViewGroup).addView(videoContainer)
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
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    videoContainer?.let { (window.decorView as ViewGroup).removeView(it) }
                    videoContainer = null
                    window.decorView.systemUiVisibility = originalSystemUiVisibility
                }
                
                customView = null
                customViewCallback?.onCustomViewHidden()
                isFullscreen = false
            }
            
            override fun getDefaultVideoPoster(): Bitmap? = null
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                _onProgressChangedListener?.invoke(newProgress)
            }
            
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                title?.let { _onTitleChangedListener?.invoke(it) }
            }
        }
        
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { _onPageStartedListener?.invoke(it) }
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { 
                    _onPageFinishedListener?.invoke(it)
                }
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
        
        setOnLongClickListener { v, _ ->
            val hitTestResult = hitTestResult
            when (hitTestResult.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val extra = hitTestResult.extra
                    if (extra != null) {
                        onLongClickListener?.invoke(extra)
                    }
                }
            }
            true
        }
    }
    
    fun saveTabState(): TabState {
        return TabState(
            url = url ?: "",
            title = title?.toString() ?: "",
            scrollPosition = scrollY,
            canGoBack = canGoBack(),
            canGoForward = canGoForward()
        )
    }
    
    fun restoreTabState(state: TabState) {
        if (state.url.isNotEmpty()) {
            loadUrl(state.url)
        }
    }
    
    fun isVideoFullscreen(): Boolean = isFullscreen
    
    fun exitFullscreen() {
        if (isFullscreen) {
            webChromeClient?.onHideCustomView()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabbedBrowserScreen() {
    var tabs by remember { mutableStateOf(mutableListOf("https://www.google.com")) }
    var tabTitles by remember { mutableStateOf(mutableListOf("New Tab")) }
    var activeTabIndex by remember { mutableIntStateOf(0) }
    var showTabList by remember { mutableStateOf(false) }
    var showBrowserMenu by remember { mutableStateOf(false) }
    var showDesktopModeDialog by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(TextFieldValue(tabs[activeTabIndex])) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var currentWebView by remember { mutableStateOf<CustomWebView?>(null) }
    var isDesktopMode by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val downloadReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    Toast.makeText(context, "Download completed!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    DisposableEffect(Unit) {
        context.registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        onDispose {
            context.unregisterReceiver(downloadReceiver)
        }
    }
    
    // Desktop Mode Dialog
    if (showDesktopModeDialog) {
        AlertDialog(
            onDismissRequest = { showDesktopModeDialog = false },
            title = { Text("Browser Mode") },
            text = {
                Column {
                    Text("Select browsing mode:", modifier = Modifier.padding(bottom = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isDesktopMode,
                            onCheckedChange = { checked ->
                                isDesktopMode = checked
                                currentWebView?.setDesktopMode(checked)
                                showDesktopModeDialog = false
                            }
                        )
                        Text("Desktop Mode (View websites like on PC)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = !isDesktopMode,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    isDesktopMode = false
                                    currentWebView?.setDesktopMode(false)
                                    showDesktopModeDialog = false
                                }
                            }
                        )
                        Text("Mobile Mode (Default)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDesktopModeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
    
    Column {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { currentWebView?.goBack() }) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
                
                IconButton(onClick = { currentWebView?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, "Forward")
                }
                
                Box(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = currentUrl.text,
                        onValueChange = { currentUrl = TextFieldValue(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search or enter URL") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        trailingIcon = {
                            if (currentUrl.text.isNotEmpty()) {
                                IconButton(onClick = { currentUrl = TextFieldValue("") }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        }
                    )
                    
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent
                        )
                    }
                }
                
                IconButton(onClick = {
                    var url = currentUrl.text.trim()
                    if (url.isNotEmpty()) {
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = if (url.contains(".")) "https://$url" else "https://$url.com"
                        }
                        currentUrl = TextFieldValue(url)
                        tabs = tabs.toMutableList().apply { this[activeTabIndex] = url }
                        currentWebView?.loadUrl(url)
                    } else {
                        currentWebView?.reload()
                    }
                }) {
                    Icon(if (isLoading) Icons.Default.Close else Icons.Default.Search, "Go/Refresh")
                }
                
                IconButton(onClick = {
                    val homeUrl = "https://www.google.com"
                    currentUrl = TextFieldValue(homeUrl)
                    tabs = tabs.toMutableList().apply { this[activeTabIndex] = homeUrl }
                    currentWebView?.loadUrl(homeUrl)
                }) {
                    Icon(Icons.Default.Home, "Home")
                }
                
                IconButton(onClick = { showDesktopModeDialog = true }) {
                    Icon(
                        if (isDesktopMode) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                        "Mode"
                    )
                }
                
                Badge(
                    containerColor = if (tabs.size > 1) MaterialTheme.colorScheme.primary else Color.Transparent
                ) {
                    IconButton(onClick = { showTabList = true }) {
                        Icon(Icons.Default.Tab, "Tabs")
                        if (tabs.size > 1) {
                            Text(
                                text = "${tabs.size}",
                                modifier = Modifier.padding(start = 2.dp),
                                fontSize = MaterialTheme.typography.labelSmall.fontSize
                            )
                        }
                    }
                }
                
                IconButton(onClick = { showBrowserMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Menu")
                }
            }
        }
        
        if (showTabList) {
            AlertDialog(
                onDismissRequest = { showTabList = false },
                title = { Text("Open Tabs (${tabs.size})") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(tabs) { index, url ->
                            ListItem(
                                headlineContent = { 
                                    Text(
                                        tabTitles.getOrElse(index) { url.take(30) },
                                        maxLines = 1
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        url.take(50) + if (url.length > 50) "..." else "",
                                        maxLines = 1,
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize
                                    )
                                    if (index == activeTabIndex) {
                                        Text(" • Active", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                modifier = Modifier.clickable { 
                                    activeTabIndex = index
                                    currentUrl = TextFieldValue(tabs[index])
                                    currentWebView?.loadUrl(tabs[index])
                                    showTabList = false
                                },
                                trailingContent = {
                                    if (tabs.size > 1) {
                                        IconButton(onClick = { 
                                            tabs = tabs.toMutableList().apply { removeAt(index) }
                                            tabTitles = tabTitles.toMutableList().apply { removeAt(index) }
                                            if (activeTabIndex >= tabs.size) activeTabIndex = tabs.size - 1
                                            if (activeTabIndex < 0 && tabs.isNotEmpty()) activeTabIndex = 0
                                            if (tabs.isNotEmpty()) {
                                                currentUrl = TextFieldValue(tabs[activeTabIndex])
                                                currentWebView?.loadUrl(tabs[activeTabIndex])
                                            }
                                        }) { 
                                            Icon(Icons.Default.Close, null) 
                                        }
                                    }
                                }
                            )
                        }
                        
                        item {
                            Button(
                                onClick = {
                                    val newUrl = "https://www.google.com"
                                    tabs = tabs.toMutableList().apply { add(newUrl) }
                                    tabTitles = tabTitles.toMutableList().apply { add("New Tab") }
                                    activeTabIndex = tabs.size - 1
                                    currentUrl = TextFieldValue(newUrl)
                                    currentWebView?.loadUrl(newUrl)
                                    showTabList = false
                                },
                                modifier = Modifier.fillMaxWidth().padding(8.dp)
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Tab")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTabList = false }) { Text("Close") }
                }
            )
        }
        
        if (showBrowserMenu) {
            AlertDialog(
                onDismissRequest = { showBrowserMenu = false },
                title = { Text("Browser Options") },
                text = {
                    Column {
                        TextButton(onClick = {
                            val currentPageUrl = currentWebView?.url ?: tabs[activeTabIndex]
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
                            currentWebView?.reload()
                            showBrowserMenu = false
                        }) {
                            Text("🔄 Refresh")
                        }
                        TextButton(onClick = {
                            val currentUrl = currentWebView?.url ?: tabs[activeTabIndex]
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                            showBrowserMenu = false
                        }) {
                            Text("🌐 Open in External Browser")
                        }
                        TextButton(onClick = {
                            currentWebView?.settings?.javaScriptEnabled = true
                            showBrowserMenu = false
                            Toast.makeText(context, "JavaScript Enabled", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("⚙️ Enable JavaScript")
                        }
                        TextButton(onClick = {
                            currentWebView?.clearHistory()
                            showBrowserMenu = false
                            Toast.makeText(context, "History Cleared", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("🗑 Clear History")
                        }
                        TextButton(onClick = {
                            showDesktopModeDialog = true
                            showBrowserMenu = false
                        }) {
                            Text(if (isDesktopMode) "📱 Switch to Mobile Mode" else "💻 Switch to Desktop Mode")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBrowserMenu = false }) { Text("Close") }
                }
            )
        }

        AndroidView(
            factory = { ctx ->
                CustomWebView(ctx).apply {
                    setDesktopMode(isDesktopMode)
                    
                    setOnLongClickListener { url ->
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Link Options")
                            .setMessage(url)
                            .setPositiveButton("Open in New Tab") { _, _ ->
                                tabs = tabs.toMutableList().apply { add(url) }
                                tabTitles = tabTitles.toMutableList().apply { add("Loading...") }
                                activeTabIndex = tabs.size - 1
                                currentUrl = TextFieldValue(url)
                                loadUrl(url)
                            }
                            .setNeutralButton("Share") { _, _ ->
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                }
                                ctx.startActivity(Intent.createChooser(shareIntent, "Share Link"))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    
                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        try {
                            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                setTitle("Downloading...")
                                setDescription("Downloading file from web")
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "download_${System.currentTimeMillis()}")
                                allowScanningByMediaScanner()
                            }
                            val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            downloadManager.enqueue(request)
                            Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    setOnPageStartedListener { url ->
                        scope.launch {
                            currentUrl = TextFieldValue(url)
                            tabs = tabs.toMutableList().apply { 
                                if (activeTabIndex < size) this[activeTabIndex] = url 
                            }
                        }
                    }
                    
                    setOnPageFinishedListener { url ->
                        scope.launch { isLoading = false }
                    }
                    
                    setOnProgressChangedListener { newProgress ->
                        scope.launch {
                            progress = newProgress
                            isLoading = newProgress in 1..99
                        }
                    }
                    
                    setOnTitleChangedListener { title ->
                        scope.launch {
                            if (activeTabIndex < tabTitles.size) {
                                tabTitles = tabTitles.toMutableList().apply { 
                                    this[activeTabIndex] = title.take(20)
                                }
                            }
                        }
                    }
                    
                    currentWebView = this
                    loadUrl(tabs[activeTabIndex])
                }
            },
            update = { webView ->
                currentWebView = webView
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
