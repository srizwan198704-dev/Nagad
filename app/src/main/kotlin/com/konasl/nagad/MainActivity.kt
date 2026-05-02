package com.konasl.nagad

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Rational
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
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

    fun enterPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
    
    fun toggleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    var isFullScreenPlayerOpen by remember { mutableStateOf(false) }
    
    Scaffold(
        bottomBar = {
            if (!isFullScreenPlayerOpen) {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    listOf("files" to Icons.Default.Storage, "browser" to Icons.Default.Language).forEach { (route, icon) ->
                        NavigationBarItem(
                            icon = { Icon(icon, route) },
                            label = { Text(route.replaceFirstChar { it.uppercase() }) },
                            selected = currentRoute == route,
                            onClick = {
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController, 
            startDestination = "files", 
            modifier = Modifier.padding(if (isFullScreenPlayerOpen) PaddingValues(0.dp) else padding)
        ) {
            composable("files") { 
                FileManagerScreen(onPlayerStateChange = { isFullScreenPlayerOpen = it }) 
            }
            composable("browser") { TabbedBrowserScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(onPlayerStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var currentPathString by rememberSaveable { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    val currentPath = File(currentPathString)
    
    var files by remember { mutableStateOf(emptyList<File>()) }
    LaunchedEffect(currentPathString) {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileType by remember { mutableStateOf("") }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Item") },
            text = { Text("Are you sure you want to delete ${fileToDelete?.name}?") },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = Color.Red), onClick = {
                    if (fileToDelete?.deleteRecursively() == true) {
                        files = files.filter { it != fileToDelete }
                    }
                    fileToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Cancel") } }
        )
    }

    if (viewingFile != null) {
        onPlayerStateChange(true)
        InternalPlayer(viewingFile!!, fileType) { 
            viewingFile = null
            onPlayerStateChange(false)
        }
    } else {
        BackHandler(enabled = currentPathString != Environment.getExternalStorageDirectory().absolutePath) {
            currentPathString = currentPath.parentFile?.absolutePath ?: currentPathString
        }

        Column {
            TopAppBar(title = { Text(if (currentPathString == Environment.getExternalStorageDirectory().absolutePath) "Files" else currentPath.name) })
            LazyColumn(Modifier.fillMaxSize()) {
                items(files) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        leadingContent = {
                            val icon = when {
                                file.isDirectory -> Icons.Default.Folder
                                file.extension.lowercase() in listOf("mp4", "mkv") -> Icons.Default.PlayCircle
                                else -> Icons.Default.FileOpen
                            }
                            Icon(icon, null, tint = if (file.isDirectory) Color.Cyan else Color.White)
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (file.isDirectory) currentPathString = file.absolutePath
                                else {
                                    val ext = file.extension.lowercase()
                                    if (ext in listOf("mp4", "mkv")) { viewingFile = file; fileType = "video" }
                                    else if (ext in listOf("jpg", "png", "jpeg")) { viewingFile = file; fileType = "image" }
                                }
                            },
                            onLongClick = { fileToDelete = file }
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun InternalPlayer(file: File, type: String, onBack: () -> Unit) {
    val activity = LocalContext.current as? MainActivity
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (type == "video") {
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp).zIndex(2f)) {
                IconButton(onClick = { activity?.toggleOrientation() }) { Icon(Icons.Default.ScreenRotation, null, tint = Color.White) }
                IconButton(onClick = { activity?.enterPiPMode() }) { Icon(Icons.Default.PictureInPicture, null, tint = Color.White) }
                IconButton(onClick = { onBack() }) { Icon(Icons.Default.Close, null, tint = Color.White) }
            }
            AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setVideoPath(file.absolutePath)
                        setMediaController(android.widget.MediaController(ctx).apply { setAnchorView(this@apply) })
                        start()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            IconButton(onClick = { onBack() }, Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
    }
}

data class BrowserTab(val url: String, val title: String = "New Tab")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TabbedBrowserScreen() {
    var tabs by rememberSaveable { mutableStateOf(listOf(BrowserTab("https://www.google.com"))) }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var urlInput by remember { mutableStateOf(tabs[selectedTabIndex].url) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    
    val webViews = remember { mutableMapOf<Int, WebView>() }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = {}
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { 
                        selectedTabIndex = index
                        urlInput = tabs[index].url
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.widthIn(max = 80.dp))
                            if (tabs.size > 1) {
                                IconButton(onClick = {
                                    val newTabs = tabs.toMutableList()
                                    newTabs.removeAt(index)
                                    tabs = newTabs
                                    if (selectedTabIndex >= newTabs.size) selectedTabIndex = newTabs.size - 1
                                    urlInput = tabs[selectedTabIndex].url
                                }, Modifier.size(18.dp)) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                )
            }
            IconButton(onClick = {
                tabs = tabs + BrowserTab("https://www.google.com")
                selectedTabIndex = tabs.size - 1
                urlInput = "https://www.google.com"
            }) { Icon(Icons.Default.Add, null) }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                trailingIcon = {
                    IconButton(onClick = { 
                        val finalUrl = if (urlInput.contains("://")) urlInput else "https://$urlInput"
                        webViews[selectedTabIndex]?.loadUrl(finalUrl)
                    }) { Icon(Icons.Default.Search, null) }
                }
            )
            IconButton(onClick = { webViews[selectedTabIndex]?.reload() }) { Icon(Icons.Default.Refresh, null) }
        }

        if (isLoading) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f)) {
            tabs.forEachIndexed { index, tab ->
                if (index == selectedTabIndex) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        isLoading = true
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        urlInput = url ?: ""
                                        val newTabs = tabs.toMutableList()
                                        newTabs[index] = BrowserTab(url ?: "", view?.title ?: "Tab")
                                        tabs = newTabs
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress / 100f
                                    }
                                }
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    setSupportMultipleWindows(true)
                                    javaScriptCanOpenWindowsAutomatically = true
                                    allowFileAccess = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    // Desktop User Agent for Gemini/Google Support
                                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                }
                                loadUrl(tab.url)
                                webViews[index] = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    BackHandler(enabled = webViews[selectedTabIndex]?.canGoBack() == true) {
        webViews[selectedTabIndex]?.goBack()
    }
}

fun installApk(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open APK", Toast.LENGTH_SHORT).show()
    }
}
