package com.konasl.nagad

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.*
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NagadApp()
        }
    }
}

@Composable
fun NagadApp() {
    val navController = rememberNavController()
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Files") },
                    selected = false,
                    onClick = { navController.navigate("files") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Public, contentDescription = null) },
                    label = { Text("Browser") },
                    selected = false,
                    onClick = { navController.navigate("browser") }
                )
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "files", modifier = Modifier.padding(padding)) {
            composable("files") { FileManagerScreen() }
            composable("browser") { SecureBrowserScreen() }
        }
    }
}

// --- FILE MANAGER SECTION ---
@Composable
fun FileManagerScreen() {
    val path = remember { mutableStateOf(File("/storage/emulated/0")) }
    val files = path.value.listFiles()?.toList() ?: emptyList()

    Column {
        TopAppBar(title = { Text("Storage: ${path.value.name}") }, 
            navigationIcon = {
                if (path.value.parentFile != null) {
                    IconButton(onClick = { path.value = path.value.parentFile!! }) {
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
                        Icon(
                            if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (file.isDirectory) Color(0xFFFFC107) else Color.Gray
                        )
                    },
                    modifier = androidx.compose.foundation.clickable {
                        if (file.isDirectory) path.value = file
                    }
                )
            }
        }
    }
}

// --- SECURE BROWSER SECTION ---
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureBrowserScreen() {
    var url by remember { mutableStateOf("https://www.google.com") }
    var webView: WebView? = null

    Column {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // Simulating loader
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.allowFileAccess = true
                    
                    // Secure handling for Google Accounts
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36"
                    
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return false
                        }
                    }
                    
                    webChromeClient = WebChromeClient()
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(title: @Composable () -> Unit, navigationIcon: @Composable (() -> Unit)? = null) {
    CenterAlignedTopAppBar(title = title, navigationIcon = navigationIcon ?: {})
}
