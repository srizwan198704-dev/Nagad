package com.konasl.nagad

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
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

// --- ফাইল ম্যানেজার লজিক ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen() {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
    val files = currentPath.listFiles()?.sortedByDescending { it.isDirectory } ?: emptyList()
    
    // ভিউয়ার স্টেট
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileType by remember { mutableStateOf("") }

    if (viewingFile != null) {
        InternalPlayer(viewingFile!!, fileType) { viewingFile = null }
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
                                file.extension.lowercase() in listOf("mp4", "mkv") -> Icons.Default.PlayCircle
                                file.extension.lowercase() in listOf("jpg", "png", "webp") -> Icons.Default.Image
                                else -> Icons.Default.FileOpen
                            }
                            Icon(icon, null, tint = if (file.isDirectory) Color.Cyan else Color.White)
                        },
                        trailingContent = {
                            IconButton(onClick = { 
                                file.deleteRecursively()
                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Red)
                            }
                        },
                        modifier = Modifier.clickable {
                            if (file.isDirectory) {
                                currentPath = file
                            } else {
                                val ext = file.extension.lowercase()
                                when {
                                    ext == "apk" -> installApk(context, file)
                                    ext in listOf("mp4", "mkv", "3gp") -> { viewingFile = file; fileType = "video" }
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
    }
}

// --- ইমেজ লোডার কম্পোজেবল ---
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

// --- অল-ইন-ওয়ান ইন্টারনাল প্লেয়ার/ভিউয়ার ---
@Composable
fun InternalPlayer(file: File, type: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column {
            IconButton(onClick = onBack) { 
                Icon(Icons.Default.Close, null, tint = Color.White) 
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
                "image" -> {
                    LoadImageFromFile(
                        file = file,
                        modifier = Modifier.fillMaxSize()
                    )
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

// --- ট্যাব ব্রাউজার লজিক ---
@Composable
fun TabbedBrowserScreen() {
    var tabs by remember { mutableStateOf(mutableListOf("https://www.google.com")) }
    var activeTabIndex by remember { mutableIntStateOf(0) }
    var showTabList by remember { mutableStateOf(false) }

    Column {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
            Button(onClick = { showTabList = true }, Modifier.weight(1f)) { 
                Text("Tabs (${tabs.size})") 
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { 
                tabs = tabs.toMutableList().apply { add("https://www.google.com") }
                activeTabIndex = tabs.size - 1 
            }) {
                Icon(Icons.Default.Add, "New Tab")
            }
        }

        if (showTabList) {
            AlertDialog(
                onDismissRequest = { showTabList = false },
                confirmButton = { 
                    TextButton(onClick = { showTabList = false }) { 
                        Text("Close") 
                    } 
                },
                title = { Text("Open Tabs") },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        itemsIndexed(tabs) { index, url ->
                            ListItem(
                                headlineContent = { 
                                    Text(
                                        url.take(50) + if (url.length > 50) "..." else "",
                                        maxLines = 1
                                    ) 
                                },
                                modifier = Modifier.clickable { 
                                    activeTabIndex = index
                                    showTabList = false
                                },
                                trailingContent = {
                                    if (tabs.size > 1) {
                                        IconButton(onClick = { 
                                            tabs = tabs.toMutableList().apply { removeAt(index) }
                                            if (activeTabIndex >= tabs.size) {
                                                activeTabIndex = tabs.size - 1
                                            }
                                            if (activeTabIndex < 0) activeTabIndex = 0
                                        }) { 
                                            Icon(Icons.Default.Close, null) 
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }

        SecureWebView(
            url = tabs.getOrElse(activeTabIndex) { "https://www.google.com" },
            onUrlChange = { newUrl ->
                if (activeTabIndex < tabs.size) {
                    tabs = tabs.toMutableList().apply { 
                        this[activeTabIndex] = newUrl 
                    }
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureWebView(url: String, onUrlChange: (String) -> Unit) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
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
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        url?.let { onUrlChange(it) }
                    }
                    
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        request?.url?.let { uri ->
                            if (uri.toString().startsWith("http")) {
                                view?.loadUrl(uri.toString())
                                return true
                            }
                        }
                        return false
                    }
                }
                webChromeClient = WebChromeClient()
                setDownloadListener { downloadUrl, _, _, _, _ ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot download: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) {
                webView.loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
