package com.konasl.nagad

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {
    
    private val viewModel: HybridViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request necessary permissions
        requestPermissions()
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HybridApp(viewModel = viewModel)
                }
            }
        }
    }
    
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT <= 32) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                }
            }
            
            if (permissions.isNotEmpty()) {
                requestPermissionsLauncher.launch(permissions.toTypedArray())
            }
        }
    }
    
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
}

// ViewModel with state persistence
class HybridViewModel : ViewModel() {
    private val savedStateHandle = SavedStateHandle()
    
    private val _selectedTab = MutableStateFlow(savedStateHandle.get<Int>("selected_tab") ?: 0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()
    
    private val _currentUrl = MutableStateFlow(savedStateHandle.get<String>("current_url") ?: "https://www.google.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()
    
    private val _desktopMode = MutableStateFlow(savedStateHandle.get<Boolean>("desktop_mode") ?: false)
    val desktopMode: StateFlow<Boolean> = _desktopMode.asStateFlow()
    
    private val _files = MutableStateFlow<List<FileInfo>>(emptyList())
    val files: StateFlow<List<FileInfo>> = _files.asStateFlow()
    
    private val _scrollPosition = MutableStateFlow(savedStateHandle.get<Int>("scroll_position") ?: 0)
    val scrollPosition: StateFlow<Int> = _scrollPosition.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _showPopup = MutableStateFlow(false)
    val showPopup: StateFlow<Boolean> = _showPopup.asStateFlow()
    
    init {
        savedStateHandle["selected_tab"] = _selectedTab.value
        savedStateHandle["current_url"] = _currentUrl.value
        savedStateHandle["desktop_mode"] = _desktopMode.value
        savedStateHandle["scroll_position"] = _scrollPosition.value
    }
    
    fun selectTab(index: Int) {
        _selectedTab.value = index
        savedStateHandle["selected_tab"] = index
    }
    
    fun updateUrl(url: String) {
        _currentUrl.value = url
        savedStateHandle["current_url"] = url
    }
    
    fun toggleDesktopMode() {
        _desktopMode.value = !_desktopMode.value
        savedStateHandle["desktop_mode"] = _desktopMode.value
    }
    
    fun updateScrollPosition(position: Int) {
        _scrollPosition.value = position
        savedStateHandle["scroll_position"] = position
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun setFiles(files: List<FileInfo>) {
        _files.value = files
    }
    
    fun togglePopup() {
        _showPopup.value = !_showPopup.value
    }
    
    fun deleteFile(context: Context, fileInfo: FileInfo): Boolean {
        return try {
            val file = File(fileInfo.path)
            val deleted = file.delete()
            if (deleted) {
                loadFiles(context)
            }
            deleted
        } catch (e: Exception) {
            false
        }
    }
    
    fun loadFiles(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileList = getFilesFromDirectory(context)
            withContext(Dispatchers.Main) {
                _files.value = fileList
            }
        }
    }
    
    private fun getFilesFromDirectory(context: Context): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        
        try {
            val directory = Environment.getExternalStorageDirectory()
            val file = File(directory.path)
            val fileList = file.listFiles() ?: return emptyList()
            
            for (f in fileList) {
                if (!f.name.startsWith(".")) {
                    val extension = f.extension.lowercase()
                    val type = when {
                        f.isDirectory -> "folder"
                        extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm") -> "video"
                        extension in listOf("mp3", "wav", "aac", "flac", "ogg", "m4a") -> "audio"
                        extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> "image"
                        else -> "file"
                    }
                    
                    files.add(
                        FileInfo(
                            name = f.name,
                            path = f.absolutePath,
                            isDirectory = f.isDirectory,
                            size = if (!f.isDirectory) f.length() else 0,
                            lastModified = f.lastModified(),
                            type = type
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return files.sortedBy { it.name.lowercase() }
    }
}

data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val type: String
)

// Main Composable
@Composable
fun HybridApp(viewModel: HybridViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        viewModel.loadFiles(context)
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { viewModel.selectTab(0) },
                text = { Text("📁 File Manager") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { viewModel.selectTab(1) },
                text = { Text("🌐 Browser") }
            )
        }
        
        when (selectedTab) {
            0 -> FileManagerScreen(viewModel = viewModel)
            1 -> BrowserScreen(viewModel = viewModel)
        }
    }
}

// File Manager Screen
@Composable
fun FileManagerScreen(viewModel: HybridViewModel) {
    val files by viewModel.files.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val scrollPosition by viewModel.scrollPosition.collectAsState()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollPosition)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showVideoPlayer by remember { mutableStateOf<FileInfo?>(null) }
    
    LaunchedEffect(listState.firstVisibleItemIndex) {
        viewModel.updateScrollPosition(listState.firstVisibleItemIndex)
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Search files...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )
        
        val filteredFiles = if (searchQuery.isEmpty()) files 
            else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
        
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredFiles) { file ->
                FileItemRow(
                    file = file,
                    onItemClick = {
                        if (file.type == "video") {
                            showVideoPlayer = file
                        } else if (file.isDirectory) {
                            Toast.makeText(context, "Folder: ${file.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            openFile(context, file)
                        }
                    },
                    onDelete = {
                        if (viewModel.deleteFile(context, file)) {
                            Toast.makeText(context, "Deleted: ${file.name}", Toast.LENGTH_SHORT).show()
                            viewModel.loadFiles(context)
                        } else {
                            Toast.makeText(context, "Failed to delete", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
    
    if (showVideoPlayer != null) {
        VideoPlayerDialog(
            file = showVideoPlayer!!,
            onDismiss = { showVideoPlayer = null }
        )
    }
}

@Composable
fun FileItemRow(file: FileInfo, onItemClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    file.isDirectory -> Icons.Default.Folder
                    file.type == "video" -> Icons.Default.Videocam
                    file.type == "audio" -> Icons.Default.Audiotrack
                    else -> Icons.Default.InsertDriveFile
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
                    if (!file.isDirectory) {
                        Text(
                            text = formatFileSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }
            
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
            }
        }
    }
}

@Composable
fun VideoPlayerDialog(file: FileInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
    
    LaunchedEffect(file) {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(file.path)))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }
}

// Browser Screen
@Composable
fun BrowserScreen(viewModel: HybridViewModel) {
    val context = LocalContext.current
    val currentUrl by viewModel.currentUrl.collectAsState()
    val desktopMode by viewModel.desktopMode.collectAsState()
    val showPopup by viewModel.showPopup.collectAsState()
    var urlText by remember { mutableStateOf(currentUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // URL Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webView?.goBack() },
                        enabled = canGoBack
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    
                    IconButton(
                        onClick = { webView?.goForward() },
                        enabled = canGoForward
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
                    }
                    
                    IconButton(
                        onClick = { webView?.reload() }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    
                    IconButton(
                        onClick = {
                            var url = urlText.trim()
                            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                url = "https://$url"
                            }
                            viewModel.updateUrl(url)
                            webView?.loadUrl(url)
                        }
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Go")
                    }
                }
                
                // Desktop Mode Toggle
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Desktop Mode", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = desktopMode,
                        onCheckedChange = { 
                            viewModel.toggleDesktopMode()
                            webView?.settings?.userAgentString = if (desktopMode) {
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            } else {
                                null
                            }
                            webView?.reload()
                        }
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    Button(
                        onClick = { viewModel.togglePopup() },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Popup")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Popup Mode")
                    }
                }
            }
        }
        
        // Progress Bar
        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        // WebView
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
                        
                        if (desktopMode) {
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        }
                        
                        // Enable downloading
                        setSupportMultipleWindows(false)
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            viewModel.updateUrl(url ?: "")
                            urlText = url ?: ""
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                        }
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                            if (newProgress == 100) isLoading = false
                        }
                        
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            fileChooserLauncher.launch(arrayOf())
                            uploadCallback = filePathCallback
                            return true
                        }
                    }
                    
                    loadUrl(currentUrl)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
    
    // File chooser handling
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            uploadCallback?.onReceiveValue(arrayOf(it))
            uploadCallback = null
        } ?: run {
            uploadCallback?.onReceiveValue(null)
            uploadCallback = null
        }
    }
    
    // Popup mode
    if (showPopup) {
        PopupWebView(
            url = currentUrl,
            onDismiss = { viewModel.togglePopup() }
        )
    }
}

private var uploadCallback: ValueCallback<Array<Uri>>? = null
private val fileChooserLauncher = remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<Array<String>>?>(null) }

@Composable
fun PopupWebView(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// Utility functions
fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun openFile(context: Context, fileInfo: FileInfo) {
    try {
        val uri = Uri.fromFile(File(fileInfo.path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(fileInfo.path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open this file", Toast.LENGTH_SHORT).show()
    }
}

fun getMimeType(path: String): String {
    val extension = path.substringAfterLast(".", "").lowercase()
    return when (extension) {
        "mp4", "mkv", "avi" -> "video/*"
        "mp3", "wav" -> "audio/*"
        "jpg", "jpeg", "png", "gif" -> "image/*"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "*/*"
    }
}
