package com.konasl.nagad

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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

    // PiP মোড ট্রিগার ফাংশন
    fun enterPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                NavigationBarItem(
                    icon = { Icon(Icons.Default.Storage, "Files") },
                    label = { Text("Files") },
                    selected = currentRoute == "files",
                    onClick = { 
                        if (currentRoute != "files") {
                            navController.navigate("files") { popUpTo("files") { saveState = true }; launchSingleTop = true; restoreState = true }
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Language, "Browser") },
                    label = { Text("Browser") },
                    selected = currentRoute == "browser",
                    onClick = { 
                        if (currentRoute != "browser") {
                            navController.navigate("browser") { popUpTo("files") { saveState = true }; launchSingleTop = true; restoreState = true }
                        }
                    }
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
    
    // পাথ সেভ রাখার জন্য rememberSaveable
    var currentPathString by rememberSaveable { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    val currentPath = File(currentPathString)
    
    // Alphabetic sorting (Folders first, then files)
    val files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fileType by remember { mutableStateOf("") }

    if (viewingFile != null) {
        InternalPlayer(viewingFile!!, fileType) { viewingFile = null }
    } else {
        Column {
            TopAppBar(
                title = { Text(if (currentPathString == Environment.getExternalStorageDirectory().absolutePath) "Internal Storage" else currentPath.name) },
                navigationIcon = {
                    if (currentPathString != Environment.getExternalStorageDirectory().absolutePath) {
                        IconButton(onClick = { currentPathString = currentPath.parentFile?.absolutePath ?: currentPathString }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                }
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(files) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        leadingContent = {
                            val icon = when {
                                file.isDirectory -> Icons.Default.Folder
                                file.extension.lowercase() in listOf("mp4", "mkv") -> Icons.Default.PlayCircle
                                file.extension.lowercase() in listOf("jpg", "png") -> Icons.Default.Image
                                else -> Icons.Default.FileOpen
                            }
                            Icon(icon, null, tint = if (file.isDirectory) Color.Cyan else Color.White)
                        },
                        modifier = Modifier.clickable {
                            if (file.isDirectory) {
                                currentPathString = file.absolutePath
                            } else {
                                val ext = file.extension.lowercase()
                                when {
                                    ext == "apk" -> installApk(context, file)
                                    ext in listOf("mp4", "mkv") -> { viewingFile = file; fileType = "video" }
                                    ext in listOf("jpg", "jpeg", "png") -> { viewingFile = file; fileType = "image" }
                                    else -> Toast.makeText(context, "Open with external app", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun InternalPlayer(file: File, type: String, onBack: () -> Unit) {
    val activity = LocalContext.current as? MainActivity
    
    // ভিডিও হলে অটো PiP মোডে যাওয়ার অপশন
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (type == "video") {
            IconButton(
                onClick = { activity?.enterPiPMode() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).zIndex(1f)
            ) {
                Icon(Icons.Default.PictureInPicture, null, tint = Color.White)
            }
        }
        
        when (type) {
            "video" -> {
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
            }
            "image" -> {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TabbedBrowserScreen() {
    // ট্যাব এবং ইন্ডেক্স সেভ রাখার জন্য
    var tabs by rememberSaveable { mutableStateOf(listOf("https://www.google.com")) }
    var activeTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val activity = context as? MainActivity
    var webView: WebView? by remember { mutableStateOf(null) }

    Column(Modifier.fillMaxSize()) {
        // Address Bar
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = tabs[activeTabIndex],
                onValueChange = { newUrl -> 
                    tabs = tabs.toMutableList().apply { this[activeTabIndex] = newUrl }
                },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = { webView?.loadUrl(tabs[activeTabIndex]) }) {
                Icon(Icons.Default.Refresh, "Load")
            }
            IconButton(onClick = { activity?.enterPiPMode() }) {
                Icon(Icons.Default.PictureInPicture, "PiP")
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    
                    // ডাউনলোড সাপোর্ট
                    setDownloadListener { url, _, _, _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        ctx.startActivity(intent)
                    }

                    webView = this
                    loadUrl(tabs[activeTabIndex])
                }
            },
            update = { it.loadUrl(tabs[activeTabIndex]) },
            modifier = Modifier.weight(1f)
        )
    }
}

fun installApk(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        data = uri
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}
