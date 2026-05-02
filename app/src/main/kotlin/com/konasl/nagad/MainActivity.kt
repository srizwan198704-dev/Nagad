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
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val items = listOf(
                    "files" to Icons.Default.Storage,
                    "browser" to Icons.Default.Language
                )

                items.forEach { (route, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, route.replaceFirstChar { it.uppercase() }) },
                        label = { Text(route.replaceFirstChar { it.uppercase() }) },
                        selected = currentRoute == route,
                        onClick = {
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    // This preserves the state of the screen when switching
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
    ) { padding ->
        NavHost(navController, "files", Modifier.padding(padding)) {
            composable("files") { FileManagerScreen() }
            composable("browser") { TabbedBrowserScreen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen() {
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

    // Delete Confirmation Dialog
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete File") },
            text = { Text("Are you sure you want to delete ${file.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    if (file.deleteRecursively()) {
                        files = files.filter { it != file }
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                    fileToDelete = null
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Cancel") } }
        )
    }

    if (viewingFile != null) {
        InternalPlayer(viewingFile!!, fileType) { viewingFile = null }
    } else {
        BackHandler(enabled = currentPathString != Environment.getExternalStorageDirectory().absolutePath) {
            currentPathString = currentPath.parentFile?.absolutePath ?: currentPathString
        }

        Column {
            TopAppBar(title = { Text(if (currentPathString == Environment.getExternalStorageDirectory().absolutePath) "Storage" else currentPath.name) })
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
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    if (file.isDirectory) currentPathString = file.absolutePath
                                    else {
                                        val ext = file.extension.lowercase()
                                        when {
                                            ext == "apk" -> installApk(context, file)
                                            ext in listOf("mp4", "mkv") -> { viewingFile = file; fileType = "video" }
                                            ext in listOf("jpg", "jpeg", "png") -> { viewingFile = file; fileType = "image" }
                                        }
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
                IconButton(onClick = { activity?.toggleOrientation() }) {
                    Icon(Icons.Default.ScreenRotation, null, tint = Color.White)
                }
                IconButton(onClick = { activity?.enterPiPMode() }) {
                    Icon(Icons.Default.PictureInPicture, null, tint = Color.White)
                }
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
    var urlInput by rememberSaveable { mutableStateOf("https://www.google.com") }
    var webView: WebView? by remember { mutableStateOf(null) }
    val activity = LocalContext.current as? MainActivity

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { 
                        val url = if (urlInput.startsWith("http")) urlInput else "https://$urlInput"
                        webView?.loadUrl(url) 
                    }) {
                        Icon(Icons.Default.Search, "Go")
                    }
                }
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, "Reload")
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            urlInput = url ?: urlInput
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl(urlInput)
                    webView = this
                }
            },
            modifier = Modifier.weight(1f)
        )
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
        Toast.makeText(context, "Failed to open APK", Toast.LENGTH_SHORT).show()
    }
}
