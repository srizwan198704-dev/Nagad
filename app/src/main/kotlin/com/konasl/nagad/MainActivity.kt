package com.konasl.nagad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.text.format.DateUtils
import android.util.Base64
import android.util.Log
import android.view.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.webkit.*
import android.widget.*
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

// ==================== NOTIFICATION LISTENER (WhatsApp + Messenger) ====================

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.gbwhatsapp", "com.whatsapp.business",
            "com.facebook.orca", "com.facebook.mlite", "com.facebook.messenger.lite",
            "org.telegram.messenger", "org.telegram.messenger.web"
        )
        const val ACTION_CHAT_MESSAGE = "com.konasl.nagad.CHAT_MESSAGE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_APP = "app"

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, WhatsAppNotificationListener::class.java)
            return flat.contains(cn.flattenToString())
        }

        fun getAppLabel(packageName: String): String = when {
            packageName.startsWith("com.whatsapp") || packageName == "com.gbwhatsapp" -> "WhatsApp"
            packageName.startsWith("com.facebook") -> "Messenger"
            packageName.startsWith("org.telegram") -> "Telegram"
            else -> "Message"
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in SUPPORTED_PACKAGES) return
        val extras = sbn.notification?.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (sender.isEmpty() && message.isEmpty()) return
        val intent = Intent(ACTION_CHAT_MESSAGE).apply {
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_APP, getAppLabel(sbn.packageName))
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op */ }
}

// ==================== MAIN ACTIVITY ====================

class MainActivity : AppCompatActivity() {

    // ── Core layout ──────────────────────────────────────────────
    private lateinit var rootLayout: LinearLayout
    private lateinit var tabLayout: LinearLayout
    private lateinit var contentFrame: FrameLayout

    // ── File manager ─────────────────────────────────────────────
    private lateinit var fileManagerView: LinearLayout
    private lateinit var fileListView: ListView
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private lateinit var pathText: TextView
    private lateinit var storageInfo: TextView

    // ── Browser ──────────────────────────────────────────────────
    private lateinit var browserView: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private val webTabs = mutableListOf<WebTab>()
    private var currentTabIndex = 0
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: FrameLayout

    // ── Phone / SMS / Contacts / Flashlight ──────────────────────
    private lateinit var dialerView: LinearLayout
    private lateinit var smsView: LinearLayout
    private var isTorchOn = false
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null

    // ── State flags ───────────────────────────────────────────────
    private var activeSection = "files"
    private var globalDesktopMode = false

    // ── Fullscreen video ─────────────────────────────────────────
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    // ── Video PiP (file player only) ─────────────────────────────
    private var pipVideoView: VideoView? = null
    private var pipHideHandler: Handler? = null
    private var pipHideRunnable: Runnable? = null
    private var pipControlOverlay: LinearLayout? = null
    private var isInPipMode = false

    // ── Prefs ────────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences
    private val KEY_CURRENT_PATH = "current_path"
    private val KEY_TAB_URLS = "tab_urls"
    private val KEY_CURRENT_TAB = "current_tab"
    private val KEY_IS_FILE_MANAGER = "is_file_manager"
    private val KEY_DESKTOP_MODE = "desktop_mode"

    private var isUIInitialized = false

    // ── Downloads ────────────────────────────────────────────────
    private val activeDownloads = mutableMapOf<Long, String>()
    private var downloadReceiver: BroadcastReceiver? = null

    // ── Notification / Chat receiver ─────────────────────────────
    private var chatReceiver: BroadcastReceiver? = null

    // ── Permissions ──────────────────────────────────────────────
    private val PERM_CALL = Manifest.permission.CALL_PHONE
    private val PERM_SMS_R = Manifest.permission.READ_SMS
    private val PERM_SMS_S = Manifest.permission.SEND_SMS
    private val PERM_SMS_R2 = Manifest.permission.RECEIVE_SMS
    private val PERM_CONTACTS = Manifest.permission.READ_CONTACTS

    private val REQ_CALL = 201
    private val REQ_SMS = 202
    private val REQ_CONTACTS = 203

    data class WebTab(
        val webView: WebView,
        var title: String = "New Tab",
        var url: String = "",
        var isDesktopMode: Boolean = false
    )

    // ── File upload ──────────────────────────────────────────────
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileUploadCallback ?: return@registerForActivityResult
            fileUploadCallback = null
            val results =
                if (result.resultCode == Activity.RESULT_OK && result.data?.data != null)
                    arrayOf(result.data!!.data!!)
                else null
            cb.onReceiveValue(results)
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager())
                    Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
            }
            initUI()
        }

    private val notificationAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (WhatsAppNotificationListener.isEnabled(this)) {
                Toast.makeText(
                    this, "✅ নোটিফিকেশন অ্যাক্সেস চালু হয়েছে", Toast.LENGTH_SHORT
                ).show()
                registerChatReceiver()
            }
        }

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { supportActionBar?.hide() } catch (e: Exception) { }
        prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        globalDesktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        initCamera()
        checkPermissionAndShowDialog()
    }

    override fun onStart() {
        super.onStart()
        if (!WhatsAppNotificationListener.isEnabled(this)) requestNotificationAccess()
        else registerChatReceiver()
    }

    override fun onStop() {
        super.onStop()
        unregisterChatReceiver()
    }

    override fun onPause() {
        super.onPause()
        saveState()
        webTabs.forEach { try { it.webView.onPause() } catch (e: Exception) { } }
    }

    override fun onResume() {
        super.onResume()
        webTabs.getOrNull(currentTabIndex)?.let {
            try { it.webView.onResume() } catch (e: Exception) { }
        }
        if (!isInPipMode) {
            if (isUIInitialized) tabLayout.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        try {
            saveState()
            webTabs.forEach {
                try { it.webView.stopLoading(); it.webView.destroy() } catch (e: Exception) { }
            }
            webTabs.clear()
        } catch (e: Exception) { }
        try {
            downloadReceiver?.let { unregisterReceiver(it) }
            downloadReceiver = null
        } catch (e: Exception) { }
        unregisterChatReceiver()
        releaseTorch()
        pipHideHandler?.removeCallbacks(pipHideRunnable ?: Runnable { })
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    // =====================================================================
    // CAMERA / FLASHLIGHT
    // =====================================================================

    private fun initCamera() {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager!!.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.e("Torch", "initCamera error: ${e.message}")
        }
    }

    private fun toggleTorch(btn: Button?) {
        if (cameraManager == null || cameraId == null) {
            Toast.makeText(this, "Flashlight not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            isTorchOn = !isTorchOn
            cameraManager!!.setTorchMode(cameraId!!, isTorchOn)
            btn?.text = if (isTorchOn) "🔦 OFF" else "🔦 ON"
            btn?.setBackgroundColor(
                if (isTorchOn) Color.parseColor("#FFC107") else Color.parseColor("#444444")
            )
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "Torch error: ${e.message}", Toast.LENGTH_SHORT).show()
            isTorchOn = false
        } catch (e: Exception) {
            Toast.makeText(this, "Torch error: ${e.message}", Toast.LENGTH_SHORT).show()
            isTorchOn = false
        }
    }

    private fun releaseTorch() {
        try {
            if (isTorchOn && cameraId != null) {
                cameraManager?.setTorchMode(cameraId!!, false)
                isTorchOn = false
            }
        } catch (e: Exception) { }
    }

    // =====================================================================
    // NOTIFICATION SYSTEM
    // =====================================================================

    private fun requestNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("📱 Notification Access")
            .setMessage("WhatsApp ও Messenger মেসেজের Toast দেখাতে হলে Notification Access অনুমতি দিতে হবে।\n\nSettings → Notification Access → এই অ্যাপ চালু করুন।")
            .setPositiveButton("Settings খুলুন") { _, _ ->
                try {
                    notificationAccessLauncher.launch(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Settings খুলতে পারেনি", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("এখন না", null).show()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerChatReceiver() {
        if (chatReceiver != null) return
        chatReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val sender =
                    intent?.getStringExtra(WhatsAppNotificationListener.EXTRA_SENDER) ?: return
                val message =
                    intent.getStringExtra(WhatsAppNotificationListener.EXTRA_MESSAGE) ?: ""
                val app =
                    intent.getStringExtra(WhatsAppNotificationListener.EXTRA_APP) ?: "Message"
                showChatToast(sender, message, app)
                playSystemNotificationSound()
            }
        }
        val filter = IntentFilter(WhatsAppNotificationListener.ACTION_CHAT_MESSAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(chatReceiver, filter)
        }
    }

    private fun unregisterChatReceiver() {
        try { chatReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        chatReceiver = null
    }

    // FIX: Custom Toast view removed (crashes on Android 13+). Use a safe overlay window instead.
    private fun showChatToast(sender: String, message: String, app: String) {
        runOnUiThread {
            try {
                val accentColor = when (app) {
                    "WhatsApp" -> "#25D366"
                    "Messenger" -> "#0084FF"
                    "Telegram" -> "#2CA5E0"
                    else -> "#888888"
                }
                val appIcon = when (app) {
                    "WhatsApp" -> "💬"
                    "Messenger" -> "💙"
                    "Telegram" -> "✈️"
                    else -> "📨"
                }
                val displayMsg =
                    if (message.length > 80) message.substring(0, 80) + "…" else message
                Toast.makeText(
                    this,
                    "$appIcon $sender\n$displayMsg",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "$app: $sender — $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playSystemNotificationSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            }
            ringtone.play()
        } catch (e: Exception) { }
    }

    // =====================================================================
    // PERMISSION & INIT
    // =====================================================================

    private fun checkPermissionAndShowDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    storagePermissionLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    initUI()
                }
            } else {
                initUI()
            }
        } else {
            val r = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val w = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (r != PackageManager.PERMISSION_GRANTED || w != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    100
                )
            } else {
                initUI()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> initUI()
            REQ_CALL -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Call permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Call permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_SMS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    refreshSmsView()
                } else {
                    Toast.makeText(this, "SMS permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_CONTACTS -> { /* refreshed on next open */ }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initUI() {
        if (isUIInitialized) return
        isUIInitialized = true

        currentPath = try {
            Environment.getExternalStorageDirectory().takeIf { it.exists() && it.canRead() }
                ?: filesDir
        } catch (e: Exception) { filesDir }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ── Main 4-tab bar ──────────────────────────────────────
        tabLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            )
        }

        val fileTab = createTabButton("📁 Files", true) { switchToSection("files") }
        val browserTab = createTabButton("🌐 Browser", false) { switchToSection("browser") }
        val dialerTab = createTabButton("📞 Phone", false) { switchToSection("dialer") }
        val smsTab = createTabButton("💬 SMS", false) { switchToSection("sms") }

        tabLayout.addView(fileTab)
        tabLayout.addView(browserTab)
        tabLayout.addView(dialerTab)
        tabLayout.addView(smsTab)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        fileManagerView = createFileManagerView()
        browserView = createBrowserView()
        dialerView = createDialerView()
        smsView = createSmsView()

        contentFrame.addView(fileManagerView)
        contentFrame.addView(browserView)
        contentFrame.addView(dialerView)
        contentFrame.addView(smsView)

        rootLayout.addView(tabLayout)
        rootLayout.addView(contentFrame)
        setContentView(rootLayout)
        restoreState()
    }

    private fun restoreState() {
        try {
            val savedPath = prefs.getString(KEY_CURRENT_PATH, null)
            if (savedPath != null) {
                val dir = File(savedPath)
                if (dir.exists() && dir.isDirectory && dir.canRead()) currentPath = dir
            }
        } catch (e: Exception) { }
        loadFiles(currentPath)

        try {
            val tabUrls = prefs.getStringSet(KEY_TAB_URLS, null)
            if (!tabUrls.isNullOrEmpty()) {
                tabUrls.forEach { url -> if (url.isNotEmpty()) addNewTab(url, switch = false) }
            }
        } catch (e: Exception) { }

        if (webTabs.isEmpty()) addNewTab("https://www.google.com", switch = true)
        else {
            val savedIdx = prefs.getInt(KEY_CURRENT_TAB, 0)
            currentTabIndex = savedIdx.coerceIn(0, webTabs.size - 1)
            switchTab(currentTabIndex)
        }

        val savedIsFileManager = prefs.getBoolean(KEY_IS_FILE_MANAGER, true)
        switchToSection(if (savedIsFileManager) "files" else "browser")
    }

    private fun switchToSection(section: String) {
        activeSection = section
        fileManagerView.visibility = View.GONE
        browserView.visibility = View.GONE
        dialerView.visibility = View.GONE
        smsView.visibility = View.GONE

        when (section) {
            "files" -> {
                fileManagerView.visibility = View.VISIBLE
                highlightTab(0)
            }
            "browser" -> {
                browserView.visibility = View.VISIBLE
                highlightTab(1)
                if (currentTabIndex in webTabs.indices) {
                    try { webTabs[currentTabIndex].webView.onResume() } catch (e: Exception) { }
                }
            }
            "dialer" -> {
                dialerView.visibility = View.VISIBLE
                highlightTab(2)
            }
            "sms" -> {
                smsView.visibility = View.VISIBLE
                highlightTab(3)
                refreshSmsView()
            }
        }
    }

    private fun highlightTab(activeIndex: Int) {
        for (i in 0 until tabLayout.childCount) {
            tabLayout.getChildAt(i)?.setBackgroundColor(
                if (i == activeIndex) Color.parseColor("#333333") else Color.TRANSPARENT
            )
        }
    }

    // =====================================================================
    // VIDEO PiP (file player only — no app-wide PiP)
    // =====================================================================

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            tabLayout.visibility = View.GONE
            pipControlOverlay?.visibility = View.GONE
            pipHideHandler?.removeCallbacks(pipHideRunnable ?: return)
        } else {
            tabLayout.visibility = View.VISIBLE
            pipControlOverlay?.visibility = View.VISIBLE
        }
    }

    private fun enterFilePip(videoView: VideoView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val builder =
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(true)
            }
            val rect = Rect()
            videoView.getGlobalVisibleRect(rect)
            if (!rect.isEmpty) builder.setSourceRectHint(rect)
            pipVideoView = videoView
            enterPictureInPictureMode(builder.build())
        } catch (e: Exception) {
            Toast.makeText(this, "PiP error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================
    // CUSTOM URL SCHEME
    // =====================================================================

    private fun handleCustomScheme(view: WebView, url: String): Boolean {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") return false
        if (scheme == "javascript") return true
        if (scheme == "intent") return handleIntentScheme(url)

        val launchIntent: Intent? = when (scheme) {
            "market" -> try { Intent(Intent.ACTION_VIEW, uri) } catch (e: Exception) { null }
            "tel" -> Intent(Intent.ACTION_DIAL, uri)
            "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
            "sms", "smsto", "mms", "mmsto" -> Intent(Intent.ACTION_SENDTO, uri)
            "geo", "maps" -> Intent(Intent.ACTION_VIEW, uri)
            "fb", "facebook" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.facebook.katana"
            }
            "instagram" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.instagram.android"
            }
            "twitter", "x" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.twitter.android"
            }
            "whatsapp" -> Intent(Intent.ACTION_VIEW, uri).apply { `package` = "com.whatsapp" }
            "tg" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "org.telegram.messenger"
            }
            "youtube", "vnd.youtube" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.google.android.youtube"
            }
            "snssdk1128", "snssdk1233" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.zhiliaoapp.musically"
            }
            "snapchat" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.snapchat.android"
            }
            "linkedin" -> Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.linkedin.android"
            }
            "spotify" -> Intent(Intent.ACTION_VIEW, uri).apply { `package` = "com.spotify.music" }
            else -> Intent(Intent.ACTION_VIEW, uri)
        }
        return tryLaunchIntent(launchIntent, url, scheme)
    }

    private fun handleIntentScheme(intentUrl: String): Boolean {
        return try {
            val intent = Intent.parseUri(intentUrl, Intent.URI_INTENT_SCHEME)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pm = packageManager
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            if (resolved != null) {
                startActivity(intent); true
            } else {
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrEmpty()) {
                    webTabs.getOrNull(currentTabIndex)?.webView?.loadUrl(fallbackUrl)
                    return true
                }
                val pkg = intent.`package`
                if (!pkg.isNullOrEmpty()) tryLaunchIntent(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                    ),
                    intentUrl,
                    "market"
                )
                else true
            }
        } catch (e: Exception) { true }
    }

    private fun tryLaunchIntent(intent: Intent?, originalUrl: String, scheme: String): Boolean {
        if (intent == null) return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            val fallback = buildFallbackUrl(originalUrl, scheme)
            if (fallback != null) webTabs.getOrNull(currentTabIndex)?.webView?.loadUrl(fallback)
            else Toast.makeText(
                this, "প্রয়োজনীয় অ্যাপ পাওয়া যায়নি", Toast.LENGTH_SHORT
            ).show()
            true
        } catch (e: Exception) {
            Toast.makeText(
                this, "লিংক খুলতে সমস্যা: ${e.message}", Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    private fun buildFallbackUrl(originalUrl: String, scheme: String): String? = when (scheme) {
        "market" -> {
            val id = Uri.parse(originalUrl).getQueryParameter("id")
            if (!id.isNullOrEmpty()) "https://play.google.com/store/apps/details?id=$id"
            else "https://play.google.com"
        }
        "fb", "facebook" -> "https://www.facebook.com"
        "instagram" -> "https://www.instagram.com"
        "twitter", "x" -> "https://twitter.com"
        "whatsapp" -> "https://web.whatsapp.com"
        "tg" -> "https://web.telegram.org"
        "youtube", "vnd.youtube" -> "https://www.youtube.com"
        "linkedin" -> "https://www.linkedin.com"
        "spotify" -> "https://open.spotify.com"
        else -> null
    }

    // =====================================================================
    // ██████████  DIALER SECTION  ██████████
    // =====================================================================

    private var dialInput: EditText? = null
    private var torchBtn: Button? = null

    private fun createDialerView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Header ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(this).apply {
            text = "📞 Phone & Flashlight"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        torchBtn = Button(this).apply {
            text = "🔦 ON"
            setBackgroundColor(Color.parseColor("#444444"))
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(90), dp(40))
            setOnClickListener { toggleTorch(this) }
        }
        header.addView(headerTitle)
        header.addView(torchBtn)
        layout.addView(header)

        // ── Scroll ──
        val scroll = ScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // ── Display field ──
        dialInput = EditText(this).apply {
            hint = "Number / name"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60))
        }
        inner.addView(dialInput)
        inner.addView(View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        })

        // ── Numpad ──
        val numpadRows =
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
        for (row in numpadRows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
                        setMargins(0, dp(4), 0, dp(4))
                    }
            }
            for (digit in row) {
                val btn = Button(this).apply {
                    text = digit
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#2A2A2A"))
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        }
                    setOnClickListener { dialInput?.append(digit) }
                }
                rowLayout.addView(btn)
            }
            inner.addView(rowLayout)
        }

        // ── Action row ──
        inner.addView(View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        })
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        }

        val deleteBtn = Button(this).apply {
            text = "⌫"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            setOnClickListener {
                val s = dialInput?.text ?: return@setOnClickListener
                if (s.isNotEmpty()) dialInput?.setText(s.dropLast(1))
            }
            setOnLongClickListener { dialInput?.setText(""); true }
        }
        val callBtn = Button(this).apply {
            text = "📞 Call"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            setOnClickListener { makeCall() }
        }
        val contactsBtn = Button(this).apply {
            text = "👤"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            setOnClickListener { openContactPicker() }
        }
        actionRow.addView(deleteBtn)
        actionRow.addView(callBtn)
        actionRow.addView(contactsBtn)
        inner.addView(actionRow)

        // ── Recent calls ──
        inner.addView(View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        })
        val recentTitle = TextView(this).apply {
            text = "📋 Recent Calls"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, dp(8))
        }
        inner.addView(recentTitle)
        inner.addView(buildRecentCallsView())

        scroll.addView(inner)
        layout.addView(scroll)
        return layout
    }

    private fun makeCall() {
        val number = dialInput?.text?.toString()?.trim() ?: return
        if (number.isEmpty()) {
            Toast.makeText(this, "নম্বর লিখুন", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, PERM_CALL) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(PERM_CALL), REQ_CALL)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            } catch (e2: Exception) {
                Toast.makeText(this, "Call failed: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openContactPicker() {
        if (ContextCompat.checkSelfPermission(this, PERM_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(PERM_CONTACTS), REQ_CONTACTS)
            return
        }
        showContactListDialog()
    }

    private fun showContactListDialog() {
        val contacts = mutableListOf<Pair<String, String>>()
        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nameIdx =
                    it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx =
                    it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: ""
                    val num = it.getString(numIdx) ?: ""
                    if (num.isNotEmpty()) contacts.add(Pair(name, num))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Contacts error: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        if (contacts.isEmpty()) {
            Toast.makeText(this, "No contacts found", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        val searchEdit = EditText(this).apply {
            hint = "🔍 Search contacts"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        dialogView.addView(searchEdit)
        val listView = ListView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(350))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            divider = null
        }
        dialogView.addView(listView)

        fun makeAdapter(list: List<Pair<String, String>>) =
            object : ArrayAdapter<Pair<String, String>>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, list
            ) {
                override fun getView(
                    position: Int, convertView: View?, parent: ViewGroup
                ): View {
                    val view = super.getView(position, convertView, parent)
                    val item = getItem(position) ?: return view
                    view.setBackgroundColor(Color.parseColor("#1E1E1E"))
                    view.findViewById<TextView>(android.R.id.text1).apply {
                        text = "👤 ${item.first}"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                    }
                    view.findViewById<TextView>(android.R.id.text2).apply {
                        text = item.second
                        setTextColor(Color.parseColor("#AAAAAA"))
                        textSize = 12f
                    }
                    return view
                }
            }

        var currentList = contacts.toMutableList()
        listView.adapter = makeAdapter(currentList)
        val dialog = AlertDialog.Builder(this).setTitle("👤 Contacts").setView(dialogView)
            .setNegativeButton("Cancel", null).create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val pair = currentList.getOrNull(pos) ?: return@setOnItemClickListener
            dialInput?.setText(pair.second.replace(" ", "").replace("-", ""))
            dialog.dismiss()
        }
        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase()
                currentList =
                    contacts.filter {
                        it.first.lowercase().contains(q) || it.second.contains(q)
                    }.toMutableList()
                listView.adapter = makeAdapter(currentList)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        dialog.show()
    }

    private fun buildRecentCallsView(): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_CALL_LOG
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                container.addView(TextView(this).apply {
                    text =
                        "Call log permission not granted.\nAdd READ_CALL_LOG permission and grant at runtime."
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 12f
                    setPadding(0, dp(4), 0, dp(4))
                })
                return container
            }
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE
                ),
                null, null,
                android.provider.CallLog.Calls.DATE + " DESC"
            )
            var count = 0
            cursor?.use {
                val numIdx = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(android.provider.CallLog.Calls.DATE)
                while (it.moveToNext() && count < 20) {
                    val num = if (numIdx >= 0) it.getString(numIdx) else "?"
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val type = if (typeIdx >= 0) it.getInt(typeIdx) else 0
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L
                    val typeIcon = when (type) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "📲"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "📞"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "📵"
                        else -> "📋"
                    }
                    val displayName = if (name.isNotEmpty()) name else num ?: "Unknown"
                    val timeStr =
                        if (date > 0) DateUtils.getRelativeTimeSpanString(
                            date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                        ).toString() else ""
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundColor(Color.parseColor("#1E1E1E"))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, dp(2), 0, dp(2)) }
                    }
                    val iconView = TextView(this).apply {
                        text = typeIcon
                        textSize = 20f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                            setMargins(0, 0, dp(10), 0)
                        }
                    }
                    val textBlock = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                    textBlock.addView(TextView(this).apply {
                        text = displayName
                        setTextColor(Color.WHITE)
                        textSize = 14f
                    })
                    textBlock.addView(TextView(this).apply {
                        text = "$num  ·  $timeStr"
                        setTextColor(Color.parseColor("#888888"))
                        textSize = 11f
                    })
                    val callBackBtn = Button(this).apply {
                        text = "📞"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#4CAF50"))
                        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                        setPadding(0, 0, 0, 0)
                        setOnClickListener {
                            dialInput?.setText(num)
                            makeCall()
                        }
                    }
                    row.addView(iconView)
                    row.addView(textBlock)
                    row.addView(callBackBtn)
                    container.addView(row)
                    count++
                }
            }
            if (count == 0) {
                container.addView(TextView(this).apply {
                    text = "No recent calls"
                    setTextColor(Color.parseColor("#666666"))
                    textSize = 13f
                })
            }
        } catch (e: SecurityException) {
            container.addView(TextView(this).apply {
                text = "Call log access denied"
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
            })
        } catch (e: Exception) {
            container.addView(TextView(this).apply {
                text = "Call log error: ${e.message}"
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
            })
        }
        return container
    }

    // =====================================================================
    // ██████████  SMS SECTION  ██████████
    // =====================================================================

    private var smsListContainer: LinearLayout? = null
    private var smsSearchEdit: EditText? = null

    private fun createSmsView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Header ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(this).apply {
            text = "💬 SMS Messages"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val composeBtn = Button(this).apply {
            text = "✏️ New"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6200EE"))
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(40))
            setOnClickListener { showComposeSmsDialog(null, "") }
        }
        header.addView(headerTitle)
        header.addView(composeBtn)
        layout.addView(header)

        // ── Search ──
        smsSearchEdit = EditText(this).apply {
            hint = "🔍 Search messages"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { refreshSmsView() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        layout.addView(smsSearchEdit)

        // ── Scrollable list ──
        val scroll = ScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        smsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        scroll.addView(smsListContainer)
        layout.addView(scroll)

        return layout
    }

    private fun refreshSmsView() {
        val container = smsListContainer ?: return
        container.removeAllViews()

        if (ContextCompat.checkSelfPermission(
                this, PERM_SMS_R
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(PERM_SMS_R, PERM_SMS_S, PERM_SMS_R2), REQ_SMS
            )
            container.addView(TextView(this).apply {
                text = "📵 SMS permission required.\nTap to grant."
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(20), dp(20), dp(20), dp(20))
                gravity = Gravity.CENTER
                setOnClickListener {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(PERM_SMS_R, PERM_SMS_S, PERM_SMS_R2),
                        REQ_SMS
                    )
                }
            })
            return
        }

        val query = smsSearchEdit?.text?.toString()?.lowercase() ?: ""
        val conversations = mutableMapOf<String, SmsConversation>()

        try {
            val uri = Uri.parse("content://sms/")
            val cursor = contentResolver.query(
                uri,
                arrayOf("_id", "address", "body", "date", "type", "read", "thread_id"),
                null, null, "date DESC"
            )
            cursor?.use {
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                val readIdx = it.getColumnIndex("read")
                while (it.moveToNext()) {
                    val addr =
                        if (addrIdx >= 0) it.getString(addrIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L
                    val type = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                    val isRead = if (readIdx >= 0) it.getInt(readIdx) == 1 else true

                    if (query.isNotEmpty() && !addr.contains(query) && !body.lowercase()
                            .contains(query)
                    ) continue

                    if (!conversations.containsKey(addr)) {
                        conversations[addr] =
                            SmsConversation(addr, body, date, type, if (isRead) 0 else 1)
                    } else if (!isRead) {
                        conversations[addr]!!.unreadCount++
                    }
                }
            }
        } catch (e: Exception) {
            container.addView(TextView(this).apply {
                text = "SMS load error: ${e.message}"
                setTextColor(Color.parseColor("#FF4444"))
                textSize = 12f
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }

        if (conversations.isEmpty()) {
            container.addView(TextView(this).apply {
                text =
                    if (query.isEmpty()) "📭 No messages found" else "🔍 No results for \"$query\""
                setTextColor(Color.parseColor("#666666"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            })
            return
        }

        for ((_, conv) in conversations.entries.take(200)) {
            container.addView(buildSmsRow(conv))
        }
    }

    data class SmsConversation(
        val address: String,
        val snippet: String,
        val date: Long,
        val type: Int,
        var unreadCount: Int
    )

    private fun buildSmsRow(conv: SmsConversation): View {
        val typeIcon =
            if (conv.type == Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT) "➡️" else "📩"
        val timeStr =
            if (conv.date > 0) DateUtils.getRelativeTimeSpanString(
                conv.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString() else ""
        val displayAddr = getContactName(conv.address)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2), 0, dp(2)) }
        }
        val avatarView = TextView(this).apply {
            text = typeIcon
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(12), 0)
            }
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameView = TextView(this).apply {
            text = displayAddr
            setTextColor(if (conv.unreadCount > 0) Color.WHITE else Color.parseColor("#CCCCCC"))
            textSize = 14f
            if (conv.unreadCount > 0) typeface = Typeface.DEFAULT_BOLD
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val timeView = TextView(this).apply {
            text = timeStr
            setTextColor(Color.parseColor("#888888"))
            textSize = 11f
        }
        nameRow.addView(nameView)
        nameRow.addView(timeView)
        textBlock.addView(nameRow)
        textBlock.addView(TextView(this).apply {
            text = conv.snippet.let { if (it.length > 60) it.substring(0, 60) + "…" else it }
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
        })
        if (conv.unreadCount > 0) {
            val badge = TextView(this).apply {
                text = conv.unreadCount.toString()
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#F44336"))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
            }
            row.addView(avatarView)
            row.addView(textBlock)
            row.addView(badge)
        } else {
            row.addView(avatarView)
            row.addView(textBlock)
        }

        row.setOnClickListener { openSmsThread(conv.address, displayAddr) }
        row.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("💬 $displayAddr")
                .setItems(arrayOf("📞 Call", "✏️ Reply", "🗑️ Delete Thread")) { _, which ->
                    when (which) {
                        0 -> {
                            dialInput?.setText(conv.address)
                            switchToSection("dialer")
                        }
                        1 -> showComposeSmsDialog(conv.address, "")
                        2 -> deleteSmsThread(conv.address)
                    }
                }.show()
            true
        }
        return row
    }

    private fun getContactName(address: String): String {
        if (ContextCompat.checkSelfPermission(
                this, PERM_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return address
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)
            )
            val cursor = contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )
            cursor?.use { if (it.moveToFirst()) it.getString(0) else address } ?: address
        } catch (e: Exception) { address }
    }

    private fun openSmsThread(address: String, displayName: String) {
        val messages = mutableListOf<SmsMessage>()
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/"),
                arrayOf("_id", "body", "date", "type"),
                "address = ?", arrayOf(address),
                "date ASC"
            )
            cursor?.use {
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                while (it.moveToNext()) {
                    val body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L
                    val type = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                    messages.add(SmsMessage(body, date, type))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "SMS thread error: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog =
            Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val threadHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = Button(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { dialog.dismiss() }
        }
        val headerName = TextView(this).apply {
            text = "💬 $displayName"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val callBtn2 = Button(this).apply {
            text = "📞"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                dialInput?.setText(address)
                dialog.dismiss()
                switchToSection("dialer")
            }
        }
        threadHeader.addView(backBtn)
        threadHeader.addView(headerName)
        threadHeader.addView(callBtn2)
        layout.addView(threadHeader)

        val msgScroll = ScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val msgContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        for (msg in messages) {
            val isSent = msg.type == Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(
                    if (isSent) Color.parseColor("#6200EE") else Color.parseColor("#2A2A2A")
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        if (isSent) dp(60) else dp(4),
                        dp(4),
                        if (isSent) dp(4) else dp(60),
                        dp(4)
                    )
                    gravity = if (isSent) Gravity.END else Gravity.START
                }
            }
            bubble.addView(TextView(this).apply {
                text = msg.body
                setTextColor(Color.WHITE)
                textSize = 14f
            })
            bubble.addView(TextView(this).apply {
                text = if (msg.date > 0)
                    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(msg.date))
                else ""
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 10f
            })
            val wrapRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (isSent) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            wrapRow.addView(bubble)
            msgContainer.addView(wrapRow)
        }
        if (messages.isEmpty()) {
            msgContainer.addView(TextView(this).apply {
                text = "No messages"
                setTextColor(Color.parseColor("#666666"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            })
        }
        msgScroll.addView(msgContainer)
        layout.addView(msgScroll)
        msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }

        val replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val replyEdit = EditText(this).apply {
            hint = "Type a message…"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxLines = 4
        }
        val sendBtn = Button(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6200EE"))
            layoutParams =
                LinearLayout.LayoutParams(dp(50), dp(50)).apply { setMargins(dp(8), 0, 0, 0) }
            setOnClickListener {
                val text = replyEdit.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendSms(address, text)
                    replyEdit.setText("")
                    dialog.dismiss()
                    openSmsThread(address, displayName)
                }
            }
        }
        replyBar.addView(replyEdit)
        replyBar.addView(sendBtn)
        layout.addView(replyBar)
        dialog.setContentView(layout)
        dialog.show()
    }

    data class SmsMessage(val body: String, val date: Long, val type: Int)

    private fun showComposeSmsDialog(preFilledAddress: String?, preFilledBody: String) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        dialogView.addView(TextView(this).apply {
            text = "To:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
        })
        val toEdit = EditText(this).apply {
            hint = "Phone number"
            textSize = 14f
            setText(preFilledAddress ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        dialogView.addView(toEdit)
        dialogView.addView(View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        })
        dialogView.addView(TextView(this).apply {
            text = "Message:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
        })
        val msgEdit = EditText(this).apply {
            hint = "Type message here…"
            textSize = 14f
            setText(preFilledBody)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
        }
        dialogView.addView(msgEdit)

        AlertDialog.Builder(this)
            .setTitle("✏️ New Message")
            .setView(dialogView)
            .setPositiveButton("📤 Send") { _, _ ->
                val to = toEdit.text.toString().trim()
                val msg = msgEdit.text.toString().trim()
                if (to.isEmpty() || msg.isEmpty()) {
                    Toast.makeText(this, "Number and message required", Toast.LENGTH_SHORT).show()
                } else sendSms(to, msg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendSms(address: String, body: String) {
        if (ContextCompat.checkSelfPermission(
                this, PERM_SMS_S
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(PERM_SMS_S, PERM_SMS_R), REQ_SMS
            )
            Toast.makeText(this, "SMS permission required", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) smsManager.sendTextMessage(address, null, body, null, null)
            else smsManager.sendMultipartTextMessage(address, null, parts, null, null)
            Toast.makeText(this, "✅ SMS sent to $address", Toast.LENGTH_SHORT).show()
            if (activeSection == "sms") refreshSmsView()
        } catch (e: Exception) {
            Toast.makeText(this, "SMS failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteSmsThread(address: String) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Delete Thread")
            .setMessage("Delete all messages with $address?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = contentResolver.delete(
                        Uri.parse("content://sms/"), "address = ?", arrayOf(address)
                    )
                    Toast.makeText(this, "Deleted $deleted message(s)", Toast.LENGTH_SHORT).show()
                    refreshSmsView()
                } catch (e: Exception) {
                    Toast.makeText(
                        this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // =====================================================================
    // FILE MANAGER
    // =====================================================================

    private fun createFileManagerView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        val backBtn = Button(this).apply {
            text = "←"
            textSize = 24f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { goUp() }
        }
        pathText = TextView(this).apply {
            text = "/"
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            ellipsize = android.text.TextUtils.TruncateAt.START
            setSingleLine(true)
            textSize = 14f
        }
        val newFolderBtn = Button(this).apply {
            text = "📁+"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { createNewFolder() }
        }
        topBar.addView(backBtn)
        topBar.addView(pathText)
        topBar.addView(newFolderBtn)
        storageInfo = TextView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        fileListView = ListView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = null
        }
        layout.addView(topBar)
        layout.addView(storageInfo)
        layout.addView(fileListView)
        return layout
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")
    }

    private fun loadFiles(dir: File) {
        try {
            if (!dir.exists() || !dir.canRead()) {
                Toast.makeText(this, "Cannot read directory", Toast.LENGTH_SHORT).show()
                return
            }
            currentPath = dir
            val storagePath =
                try { Environment.getExternalStorageDirectory().absolutePath } catch (e: Exception) { "" }
            pathText.text =
                dir.absolutePath.replace(storagePath, "/storage/emulated/0")
            try {
                storageInfo.text =
                    "Free: ${getSize(dir.freeSpace)} | Total: ${getSize(dir.totalSpace)}"
            } catch (e: Exception) { storageInfo.text = "" }
            val files = try {
                dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            } catch (e: Exception) { emptyList() }
            val adapter = object : ArrayAdapter<File>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, files
            ) {
                override fun getView(
                    position: Int, convertView: View?, parent: ViewGroup
                ): View {
                    val view = super.getView(position, convertView, parent)
                    val file = getItem(position) ?: return view
                    val text1 = view.findViewById<TextView>(android.R.id.text1)
                    val text2 = view.findViewById<TextView>(android.R.id.text2)
                    val icon = when {
                        file.isDirectory -> "📁"
                        file.name.endsWith(".mp4", true) || file.name.endsWith(
                            ".mkv", true
                        ) || file.name.endsWith(".avi", true) -> "🎬"
                        file.name.endsWith(".pdf", true) -> "📕"
                        file.name.endsWith(".txt", true) -> "📝"
                        file.name.endsWith(".zip", true) -> "🗜️"
                        isImageFile(file.name) -> "🖼️"
                        else -> "📄"
                    }
                    text1.text = "$icon ${file.name}"
                    text1.setTextColor(Color.BLACK)
                    text1.textSize = 14f
                    text2.text = try {
                        "${getSize(file.length())} | ${
                            SimpleDateFormat(
                                "dd/MM/yy HH:mm", Locale.getDefault()
                            ).format(Date(file.lastModified()))
                        }"
                    } catch (e: Exception) { "" }
                    text2.textSize = 11f
                    return view
                }
            }
            fileListView.adapter = adapter
            fileListView.setOnItemClickListener { _, _, position, _ ->
                if (position >= files.size) return@setOnItemClickListener
                val file = files[position]
                when {
                    file.isDirectory -> loadFiles(file)
                    file.name.endsWith(".mp4", true) || file.name.endsWith(
                        ".mkv", true
                    ) || file.name.endsWith(".avi", true) -> playVideo(file)
                    file.name.endsWith(".txt", true) -> openTextFile(file)
                    file.name.endsWith(".pdf", true) -> openPdfFile(file)
                    file.name.endsWith(".zip", true) -> showZipOptions(file)
                    isImageFile(file.name) -> openImageFile(file)
                    else -> Toast.makeText(
                        this@MainActivity, "Unsupported file", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            fileListView.setOnItemLongClickListener { _, _, position, _ ->
                if (position < files.size) showFileOptions(files[position])
                true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading files: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goUp() {
        try {
            val parent = currentPath.parentFile
            if (parent != null && parent.exists() && parent.canRead()) loadFiles(parent)
        } catch (e: Exception) { }
    }

    private fun createNewFolder() {
        val input = EditText(this)
        AlertDialog.Builder(this).setTitle("New Folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (File(currentPath, name).mkdirs()) {
                        Toast.makeText(this, "Created", Toast.LENGTH_SHORT).show()
                        loadFiles(currentPath)
                    } else Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFileOptions(file: File) {
        val options = mutableListOf("Delete", "Rename", "Details")
        if (file.name.endsWith(".pdf", true)) options.add(1, "PDF to JPG")
        if (file.name.endsWith(".zip", true)) options.add(1, "Extract ZIP to DCIM")
        AlertDialog.Builder(this).setTitle(file.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Delete" -> deleteFile(file)
                    "Rename" -> renameFile(file)
                    "PDF to JPG" -> pdfToJpg(file)
                    "Extract ZIP to DCIM" -> extractZipToDcim(file)
                    "Details" -> showDetails(file)
                }
            }.show()
    }

    private fun deleteFile(file: File) {
        AlertDialog.Builder(this).setTitle("Delete?").setMessage("Delete ${file.name}?")
            .setPositiveButton("Yes") { _, _ ->
                if (file.deleteRecursively()) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                } else Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("No", null).show()
    }

    private fun renameFile(file: File) {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && file.renameTo(File(file.parent, newName))) {
                    Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                } else Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showDetails(file: File) {
        AlertDialog.Builder(this).setTitle("Details").setMessage(
            """
            Name: ${file.name}
            Path: ${file.absolutePath}
            Size: ${getSize(file.length())}
            Modified: ${Date(file.lastModified())}
            Readable: ${file.canRead()}
            Writable: ${file.canWrite()}
            """.trimIndent()
        ).setPositiveButton("OK", null).show()
    }

    // ── ZIP ──────────────────────────────────────────────────────

    private fun showZipOptions(file: File) {
        AlertDialog.Builder(this).setTitle("🗜️ ${file.name}")
            .setItems(arrayOf("Extract to DCIM", "Details")) { _, which ->
                when (which) { 0 -> extractZipToDcim(file); 1 -> showDetails(file) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun extractZipToDcim(zipFile: File) {
        val dcimDir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        } catch (e: Exception) {
            File(Environment.getExternalStorageDirectory(), "DCIM")
        }
        val destRoot = File(dcimDir, zipFile.nameWithoutExtension)
        val progressDialog = AlertDialog.Builder(this).setTitle("📦 Extracting…")
            .setMessage("0 files extracted").setCancelable(false).create()
        progressDialog.show()
        Thread {
            var extracted = 0; var failed = 0
            try {
                destRoot.mkdirs()
                val zis = ZipInputStream(zipFile.inputStream().buffered())
                var entry = zis.nextEntry
                while (entry != null) {
                    try {
                        val outFile = File(destRoot, entry.name.replace("..", "_"))
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zis.copyTo(it, 8192) }
                            extracted++
                            val c = extracted
                            runOnUiThread { progressDialog.setMessage("$c files extracted…") }
                        }
                        zis.closeEntry()
                    } catch (ex: Exception) {
                        failed++
                        try { zis.closeEntry() } catch (_: Exception) { }
                    }
                    entry = try { zis.nextEntry } catch (_: Exception) { null }
                }
                zis.close()
                runOnUiThread {
                    progressDialog.dismiss()
                    val msg =
                        if (failed == 0) "✅ $extracted files extracted to\nDCIM/${zipFile.nameWithoutExtension}"
                        else "✅ $extracted extracted, ⚠️ $failed failed"
                    AlertDialog.Builder(this).setTitle("Extraction Complete").setMessage(msg)
                        .setPositiveButton("OK", null).show()
                    loadFiles(currentPath)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "ZIP error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── Video Player ─────────────────────────────────────────────

    private fun playVideo(file: File) {
        try {
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialog.window?.attributes?.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            dialog.window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            @Suppress("DEPRECATION")
            dialog.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            val rootFrame = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            // FIX: Use 'this' (Activity context) for VideoView, not applicationContext
            val videoView = VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                setVideoPath(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(
                        this@MainActivity, "Cannot play this video", Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
            rootFrame.addView(videoView)

            val controlOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(8), dp(8), dp(8), dp(16))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                visibility = View.GONE
            }
            pipControlOverlay = controlOverlay

            val topControls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val playPauseBtn = Button(this).apply {
                text = "⏸"
                textSize = 20f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (videoView.isPlaying) {
                        videoView.pause(); text = "▶"
                    } else {
                        videoView.start(); text = "⏸"
                    }
                }
            }
            val seekBar = SeekBar(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeText = TextView(this).apply {
                text = "00:00 / 00:00"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
            }
            topControls.addView(playPauseBtn)
            topControls.addView(seekBar)
            topControls.addView(timeText)

            val orientationControls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            }

            fun makeCtrlBtn(
                label: String,
                color: String = "#333333",
                w: Int = 70,
                action: () -> Unit
            ) = Button(this).apply {
                text = label
                textSize = 12f
                setBackgroundColor(Color.parseColor(color))
                setTextColor(Color.WHITE)
                layoutParams =
                    LinearLayout.LayoutParams(dp(w), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    }
                setOnClickListener { action() }
            }

            orientationControls.addView(makeCtrlBtn("📱") {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            })
            orientationControls.addView(makeCtrlBtn("🌍") {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            })
            orientationControls.addView(makeCtrlBtn("🔄") {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            })
            val pipBtn = makeCtrlBtn("⧉ PiP", "#1565C0", 80) {
                enterFilePip(videoView)
            }.apply {
                isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 1f else 0.4f
            }
            orientationControls.addView(pipBtn)
            orientationControls.addView(makeCtrlBtn("✕", "#D32F2F", 60) {
                try { videoView.stopPlayback() } catch (e: Exception) { }
                pipVideoView = null
                pipControlOverlay = null
                pipHideHandler = null
                pipHideRunnable = null
                dialog.dismiss()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            })

            controlOverlay.addView(topControls)
            controlOverlay.addView(orientationControls)
            rootFrame.addView(controlOverlay)

            val hideHandler = Handler(Looper.getMainLooper())
            pipHideHandler = hideHandler
            val hideRunnable = Runnable { controlOverlay.visibility = View.GONE }
            pipHideRunnable = hideRunnable

            fun showControlsTemporarily() {
                hideHandler.removeCallbacks(hideRunnable)
                if (!isInPipMode) controlOverlay.visibility = View.VISIBLE
                hideHandler.postDelayed(hideRunnable, 3000L)
            }

            rootFrame.setOnClickListener {
                if (isInPipMode) return@setOnClickListener
                if (controlOverlay.visibility == View.VISIBLE) {
                    hideHandler.removeCallbacks(hideRunnable)
                    controlOverlay.visibility = View.GONE
                } else {
                    showControlsTemporarily()
                }
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) videoView.seekTo(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    hideHandler.removeCallbacks(hideRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) { showControlsTemporarily() }
            })

            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        try {
                            if (videoView.isPlaying) {
                                val cur = videoView.currentPosition
                                val dur = videoView.duration
                                if (dur > 0) {
                                    seekBar.max = dur
                                    seekBar.progress = cur
                                    timeText.text = "${formatTime(cur)} / ${formatTime(dur)}"
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }, 0, 1000)

            dialog.setOnDismissListener {
                timer.cancel()
                hideHandler.removeCallbacks(hideRunnable)
                pipVideoView = null
                pipControlOverlay = null
                pipHideHandler = null
                pipHideRunnable = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            dialog.setContentView(rootFrame)
            dialog.show()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } catch (e: Exception) {
            Toast.makeText(this, "Error playing video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(millis: Int): String {
        if (millis < 0) return "00:00"
        val s = millis / 1000; val m = s / 60; val h = m / 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m % 60, s % 60)
        else String.format("%02d:%02d", m, s % 60)
    }

    // ── Text Viewer ──────────────────────────────────────────────

    private fun openTextFile(file: File) {
        try {
            val fileContent = file.readText()
            val dialog = Dialog(
                this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen
            )
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FAFAFA"))
            }
            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#2C2C2C"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(this).apply {
                this.text = file.name
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val closeBtn = Button(this).apply {
                text = "X"
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setPadding(0, 0, 0, 0)
                setOnClickListener { dialog.dismiss() }
            }
            val searchBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#E8E8E8"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                gravity = Gravity.CENTER_VERTICAL
            }
            val searchInput = EditText(this).apply {
                hint = "🔍 Search in file…"
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                textSize = 13f
            }
            val resultCount = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#666666"))
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
            }
            topBar.addView(titleView)
            topBar.addView(closeBtn)
            searchBar.addView(searchInput)
            searchBar.addView(resultCount)
            val infoBar = TextView(this).apply {
                val lines = fileContent.lines().size
                val words = fileContent.trim().split(Regex("\\s+")).size
                this.text =
                    "  📄 ${getSize(file.length())}  |  $lines lines  |  $words words"
                setTextColor(Color.parseColor("#666666"))
                textSize = 11f
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            val scrollView = ScrollView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val textView = TextView(this).apply {
                this.text = fileContent
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setTextIsSelectable(true)
                textSize = 14f
                setTextColor(Color.parseColor("#212121"))
                typeface = Typeface.MONOSPACE
            }
            scrollView.addView(textView)
            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s.toString().trim()
                    if (q.isEmpty()) {
                        textView.text = fileContent; resultCount.text = ""; return
                    }
                    val span = android.text.SpannableString(fileContent)
                    var cnt = 0
                    var idx = fileContent.indexOf(q, ignoreCase = true)
                    while (idx != -1) {
                        span.setSpan(
                            android.text.style.BackgroundColorSpan(Color.parseColor("#FFEB3B")),
                            idx, idx + q.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        cnt++
                        idx = fileContent.indexOf(q, idx + 1, ignoreCase = true)
                    }
                    textView.text = span
                    resultCount.text = if (cnt > 0) "$cnt found" else "Not found"
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            layout.addView(topBar)
            layout.addView(searchBar)
            layout.addView(infoBar)
            layout.addView(scrollView)
            dialog.setContentView(layout)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Image Viewer ──────────────────────────────────────────────

    private fun openImageFile(file: File) {
        try {
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialog.window?.attributes?.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val rootFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val bitmap = loadBitmapSafe(file)
            if (bitmap == null) {
                Toast.makeText(this, "Cannot open image", Toast.LENGTH_SHORT).show()
                return
            }
            val zoomMatrix = Matrix()
            var zoomScale = 1f
            val imageView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            val scaleDetector =
                ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        zoomScale = (zoomScale * d.scaleFactor).coerceIn(0.5f, 8f)
                        zoomMatrix.setScale(zoomScale, zoomScale, d.focusX, d.focusY)
                        imageView.imageMatrix = zoomMatrix
                        return true
                    }
                })
            imageView.setOnTouchListener { v, event ->
                scaleDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) v.performClick()
                true
            }
            rootFrame.addView(imageView)
            val topOverlay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(12), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            }
            val infoText = TextView(this).apply {
                text =
                    "${file.name}  |  ${bitmap.width}×${bitmap.height}  |  ${getSize(file.length())}"
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val closeBtn = Button(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setPadding(0, 0, 0, 0)
                setOnClickListener { dialog.dismiss() }
            }
            topOverlay.addView(infoText)
            topOverlay.addView(closeBtn)
            rootFrame.addView(topOverlay)
            rootFrame.addView(TextView(this).apply {
                text = "Pinch to zoom"
                setTextColor(Color.parseColor("#AAFFFFFF"))
                textSize = 11f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#88000000"))
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { setMargins(0, 0, 0, dp(24)) }
            })
            dialog.setContentView(rootFrame)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Image error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBitmapSafe(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val max = 2048; var sample = 1
            while ((opts.outWidth / sample) > max || (opts.outHeight / sample) > max) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) { null }
    }

    private fun openPdfFile(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(this, "PDF requires Android 5.0+", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                renderer.close()
                Toast.makeText(this, "Empty PDF", Toast.LENGTH_SHORT).show()
                return
            }
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.BLACK)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val titleText = TextView(this).apply {
                text = "${file.name} - 1/$pageCount"
                setTextColor(Color.WHITE)
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val closeBtn = Button(this).apply {
                text = "✕"
                textSize = 20f
                setOnClickListener {
                    try { renderer.close() } catch (e: Exception) { }
                    dialog.dismiss()
                }
            }
            topBar.addView(titleText)
            topBar.addView(closeBtn)
            val imageView = ImageView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            var currentPage = 0
            fun renderPage(n: Int) {
                try {
                    val page = renderer.openPage(n)
                    val bmp =
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(
                        bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    imageView.setImageBitmap(bmp)
                    titleText.text = "${file.name} - ${n + 1}/$pageCount"
                    page.close()
                } catch (e: Exception) {
                    Toast.makeText(this, "PDF render error", Toast.LENGTH_SHORT).show()
                }
            }
            renderPage(0)
            imageView.setOnClickListener {
                currentPage = (currentPage + 1) % pageCount
                renderPage(currentPage)
            }
            layout.addView(topBar)
            layout.addView(imageView)
            dialog.setContentView(layout)
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "PDF Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pdfToJpg(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(this, "PDF to JPG requires Android 5.0+", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val outputDir = File(currentPath, "${file.nameWithoutExtension}_jpg")
                outputDir.mkdirs()
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bmp =
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(File(outputDir, "page_${i + 1}.jpg")).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    page.close()
                }
                renderer.close()
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Saved to ${outputDir.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadFiles(currentPath)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // =====================================================================
    // BROWSER
    // =====================================================================

    private fun createBrowserView(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
            )
        }
        tabScroll = HorizontalScrollView(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            isHorizontalScrollBarEnabled = false
        }
        tabContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabContainer)
        val newTabBtn = Button(this).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#444444"))
            setOnClickListener { addNewTab("https://www.google.com", switch = true) }
        }
        tabBar.addView(tabScroll)
        tabBar.addView(newTabBtn)

        val urlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val urlBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        urlBar = EditText(this).apply {
            hint = "Enter URL or search"
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> loadUrlInCurrentTab(); true }
        }
        val goBtn = Button(this).apply { text = "Go"; setOnClickListener { loadUrlInCurrentTab() } }
        val desktopBtn = Button(this).apply {
            text = "💻"
            textSize = 16f
            setOnClickListener {
                val tab = webTabs.getOrNull(currentTabIndex) ?: return@setOnClickListener
                tab.isDesktopMode = !tab.isDesktopMode
                text = if (tab.isDesktopMode) "📱" else "💻"
                Toast.makeText(
                    this@MainActivity,
                    if (tab.isDesktopMode) "Desktop Mode ON" else "Desktop Mode OFF",
                    Toast.LENGTH_SHORT
                ).show()
                updateDesktopModeForTab(tab)
                tab.webView.reload()
            }
        }
        urlBarLayout.addView(urlBar)
        urlBarLayout.addView(goBtn)
        urlBarLayout.addView(desktopBtn)
        progressBar =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
                max = 100
                visibility = View.GONE
            }
        urlLayout.addView(urlBarLayout)
        urlLayout.addView(progressBar)
        webContainer = FrameLayout(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        layout.addView(tabBar)
        layout.addView(urlLayout)
        layout.addView(webContainer)
        return layout
    }

    private fun updateDesktopModeForTab(tab: WebTab) {
        val ua = if (tab.isDesktopMode)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        else WebSettings.getDefaultUserAgent(this)
        try { tab.webView.settings.userAgentString = ua } catch (e: Exception) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addNewTab(url: String, switch: Boolean = true) {
        try {
            // FIX: Use 'this' Activity context for WebView — not applicationContext
            val webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION") setSavePassword(true)
                @Suppress("DEPRECATION") setSaveFormData(true)
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            CookieManager.getInstance().setAcceptCookie(true)

            val tab = WebTab(webView, title = "Loading…", url = url, isDesktopMode = false)
            webTabs.add(tab)
            updateDesktopModeForTab(tab)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val reqUrl = request.url.toString()
                    val scheme = request.url.scheme?.lowercase() ?: ""
                    if (scheme == "http" || scheme == "https" || scheme == "about") {
                        view.loadUrl(reqUrl); return true
                    }
                    if (scheme == "data") return false
                    return handleCustomScheme(view, reqUrl)
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    val idx = webTabs.indexOfFirst { it.webView == view }
                    if (idx == currentTabIndex) {
                        progressBar.visibility = View.VISIBLE; urlBar.setText(url)
                    }
                    if (idx != -1) webTabs[idx].url = url ?: ""
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    val idx = webTabs.indexOfFirst { it.webView == view }
                    if (idx == currentTabIndex) {
                        progressBar.visibility = View.GONE; urlBar.setText(url)
                    }
                    if (idx != -1) webTabs[idx].url = url ?: ""
                    view?.let { injectImageLongClickSave(it) }
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val code =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode
                                ?: -1 else -1
                        if (code == -10) return
                    }
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (webTabs.indexOfFirst { it.webView == view } == currentTabIndex) {
                        progressBar.progress = newProgress
                        progressBar.visibility =
                            if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val i = webTabs.indexOfFirst { it.webView == view }
                    if (i != -1 && !title.isNullOrEmpty()) {
                        webTabs[i].title = title; updateTabTitles()
                    }
                }
                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: JsResult?
                ): Boolean {
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity).setTitle("Alert")
                            .setMessage(message)
                            .setPositiveButton("OK") { _, _ -> result?.confirm() }
                            .setCancelable(false).show()
                    }
                    return true
                }
                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: JsResult?
                ): Boolean {
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity).setTitle("Confirm")
                            .setMessage(message)
                            .setPositiveButton("OK") { _, _ -> result?.confirm() }
                            .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                            .setCancelable(false).show()
                    }
                    return true
                }
                override fun onJsPrompt(
                    view: WebView?, url: String?, message: String?,
                    defaultValue: String?, result: JsPromptResult?
                ): Boolean {
                    runOnUiThread {
                        val input = EditText(this@MainActivity).apply { setText(defaultValue) }
                        AlertDialog.Builder(this@MainActivity).setTitle("Input")
                            .setMessage(message).setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                result?.confirm(input.text.toString())
                            }
                            .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                            .setCancelable(false).show()
                    }
                    return true
                }
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) { callback?.onCustomViewHidden(); return }
                    customView = view; customViewCallback = callback
                    originalOrientation = requestedOrientation
                    val decorView = window.decorView as FrameLayout
                    decorView.addView(
                        customView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    tabLayout.visibility = View.GONE
                }
                override fun onHideCustomView() {
                    if (customView == null) return
                    (window.decorView as FrameLayout).removeView(customView)
                    customView = null
                    requestedOrientation = originalOrientation
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                    tabLayout.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    fileChooserLauncher.launch(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        }
                    )
                    return true
                }
            }

            webView.setDownloadListener { dlUrl, ua, cd, mime, len ->
                handleDownload(dlUrl, ua, cd, mime, len, webView)
            }
            webContainer.addView(webView)
            val tabIndex = webTabs.size - 1
            val tabButton = Button(this).apply {
                text = "Tab ${tabIndex + 1}"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#444444"))
                layoutParams =
                    LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                setPadding(dp(4), 0, dp(4), 0)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { switchTab(webTabs.indexOf(tab)) }
                setOnLongClickListener { closeTab(webTabs.indexOf(tab)); true }
            }
            tabContainer.addView(tabButton)
            webView.loadUrl(url)
            if (switch) switchTab(tabIndex)
        } catch (e: Exception) {
            Toast.makeText(
                this, "Error creating tab: ${e.message}", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= webTabs.size) return
        webTabs.forEachIndexed { i, tab ->
            if (i == index) {
                tab.webView.visibility = View.VISIBLE
                try { tab.webView.onResume() } catch (e: Exception) { }
            } else {
                tab.webView.visibility = View.GONE
                try { tab.webView.onPause() } catch (e: Exception) { }
            }
        }
        currentTabIndex = index
        urlBar.setText(
            try { webTabs[index].webView.url ?: "" } catch (e: Exception) { "" }
        )
        progressBar.visibility = View.GONE
        updateTabTitles()
        updateDesktopBtnIcon()
    }

    private fun updateDesktopBtnIcon() {
        val tab = webTabs.getOrNull(currentTabIndex) ?: return
        try {
            val urlBarLayout =
                (browserView.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? LinearLayout
                    ?: return
            for (i in 0 until urlBarLayout.childCount) {
                val child = urlBarLayout.getChildAt(i)
                if (child is Button && (child.text == "💻" || child.text == "📱")) {
                    child.text = if (tab.isDesktopMode) "📱" else "💻"; break
                }
            }
        } catch (e: Exception) { }
    }

    private fun closeTab(index: Int): Boolean {
        if (index < 0 || index >= webTabs.size) return false
        if (webTabs.size <= 1) {
            Toast.makeText(this, "Cannot close last tab", Toast.LENGTH_SHORT).show()
            return false
        }
        try {
            webTabs[index].webView.stopLoading()
            webContainer.removeView(webTabs[index].webView)
            webTabs[index].webView.destroy()
        } catch (e: Exception) { }
        webTabs.removeAt(index)
        if (index < tabContainer.childCount) tabContainer.removeViewAt(index)
        currentTabIndex = currentTabIndex.coerceIn(0, webTabs.size - 1)
        switchTab(currentTabIndex)
        return true
    }

    private fun updateTabTitles() {
        for (i in 0 until tabContainer.childCount) {
            val btn = tabContainer.getChildAt(i) as? Button ?: continue
            val title = if (i < webTabs.size) webTabs[i].title else "Tab ${i + 1}"
            btn.text = if (title.length > 7) title.substring(0, 6) + "…" else title
            btn.setBackgroundColor(
                if (i == currentTabIndex) Color.parseColor("#6200EE")
                else Color.parseColor("#444444")
            )
        }
    }

    private fun loadUrlInCurrentTab() {
        if (webTabs.isEmpty()) return
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) url = "https://www.google.com"
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("about:")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://www.google.com/search?q=${Uri.encode(url)}"
        }
        try { webTabs[currentTabIndex].webView.loadUrl(url) } catch (e: Exception) {
            Toast.makeText(this, "Failed to load URL", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectImageLongClickSave(webView: WebView) {
        val js = """
            (function() {
                if (window._imgSaveInjected) return;
                window._imgSaveInjected = true;
                document.addEventListener('contextmenu', function(e) {
                    var t = e.target;
                    if (t && t.tagName === 'IMG') {
                        var src = t.src || t.currentSrc || '';
                        if (src) { e.preventDefault(); AndroidImageSave.onImageLongClick(src, t.alt || ''); }
                    }
                }, true);
                document.addEventListener('touchstart', function(e) {
                    if (e.touches.length !== 1) return;
                    var t = e.target;
                    if (!t || t.tagName !== 'IMG') return;
                    var src = t.src || t.currentSrc || '';
                    if (!src) return;
                    var timer = setTimeout(function() { AndroidImageSave.onImageLongClick(src, t.alt || ''); }, 600);
                    document.addEventListener('touchend', function cancel() { clearTimeout(timer); document.removeEventListener('touchend', cancel); });
                    document.addEventListener('touchmove', function cancel2() { clearTimeout(timer); document.removeEventListener('touchmove', cancel2); });
                }, true);
            })();
        """.trimIndent()
        try { webView.removeJavascriptInterface("AndroidImageSave") } catch (e: Exception) { }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onImageLongClick(imageUrl: String, alt: String) {
                runOnUiThread { showImageSaveDialog(imageUrl, webView) }
            }
        }, "AndroidImageSave")
        webView.evaluateJavascript(js, null)
    }

    private fun showImageSaveDialog(imageUrl: String, webView: WebView) {
        val options = arrayOf("💾 ছবি সেভ করুন", "🔗 লিংক কপি করুন", "🌐 নতুন ট্যাবে খুলুন")
        AlertDialog.Builder(this).setTitle("ছবির অপশন").setItems(options) { _, which ->
            when (which) {
                0 -> downloadImage(imageUrl, webView)
                1 -> {
                    val cm =
                        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("Image URL", imageUrl)
                    )
                    Toast.makeText(this, "লিংক কপি হয়েছে", Toast.LENGTH_SHORT).show()
                }
                2 -> addNewTab(imageUrl, switch = true)
            }
        }.setNegativeButton("বাতিল", null).show()
    }

    private fun downloadImage(imageUrl: String, webView: WebView) {
        when {
            imageUrl.startsWith("data:") -> downloadDataUrl(imageUrl)
            imageUrl.startsWith("blob:") -> downloadBlobUrl(imageUrl, webView)
            else -> {
                val cookies = CookieManager.getInstance().getCookie(imageUrl) ?: ""
                val ua = try { webView.settings.userAgentString } catch (e: Exception) { "" }
                val ext = imageUrl.substringAfterLast(".").substringBefore("?").lowercase()
                    .takeIf { it.length in 2..5 } ?: "jpg"
                downloadManually(
                    imageUrl, cookies, ua, "", "image/*",
                    "image_${System.currentTimeMillis()}.$ext"
                )
            }
        }
    }

    // =====================================================================
    // COMMON HELPERS
    // =====================================================================

    private fun getSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val dg = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(
            size / Math.pow(1024.0, dg.toDouble())
        ) + " " + units[dg]
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // =====================================================================
    // DOWNLOAD SYSTEM
    // =====================================================================

    private fun handleDownload(
        url: String, userAgent: String, contentDisposition: String,
        mimetype: String, contentLength: Long, webView: WebView
    ) {
        when {
            url.startsWith("blob:") -> downloadBlobUrl(url, webView)
            url.startsWith("data:") -> downloadDataUrl(url)
            else -> downloadWithManager(url, userAgent, contentDisposition, mimetype, contentLength, webView)
        }
    }

    private fun downloadWithManager(
        url: String, userAgentStr: String, contentDisposition: String,
        mimetype: String, contentLength: Long, webView: WebView
    ) {
        try {
            val rawName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val defaultFileName = sanitizeFileName(rawName)
            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
            val ua = if (userAgentStr.isBlank()) {
                try { webView.settings.userAgentString } catch (e: Exception) { userAgentStr }
            } else userAgentStr

            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(8))
            }
            val sizeText = if (contentLength > 0) getSize(contentLength) else "Unknown size"
            dialogView.addView(TextView(this).apply {
                text = "📦 Size: $sizeText"
                textSize = 13f
                setTextColor(Color.parseColor("#555555"))
                setPadding(0, 0, 0, dp(12))
            })
            dialogView.addView(TextView(this).apply {
                text = "File name (editable):"
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })
            val fileNameInput = EditText(this).apply {
                setText(defaultFileName); textSize = 14f; setSingleLine(true); post { selectAll() }
            }
            dialogView.addView(fileNameInput)
            AlertDialog.Builder(this).setTitle("⬇️ Download File").setView(dialogView)
                .setPositiveButton("Download") { _, _ ->
                    val inputName = fileNameInput.text.toString().trim()
                    val fileName = sanitizeFileName(inputName.ifBlank { defaultFileName })
                    try {
                        val parsedUri = Uri.parse(url)
                        val request = DownloadManager.Request(parsedUri).apply {
                            setTitle(fileName)
                            setDescription("Downloading…")
                            setMimeType(mimetype.ifBlank { "application/octet-stream" })
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            if (cookies.isNotEmpty()) addRequestHeader("Cookie", cookies)
                            if (ua.isNotEmpty()) addRequestHeader("User-Agent", ua)
                            try {
                                addRequestHeader(
                                    "Referer", "${parsedUri.scheme}://${parsedUri.host}"
                                )
                            } catch (e: Exception) { }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS, fileName
                                )
                            else {
                                val destDir = Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                                )
                                destDir.mkdirs()
                                setDestinationUri(Uri.fromFile(File(destDir, fileName)))
                            }
                            setAllowedOverMetered(true)
                            setAllowedOverRoaming(true)
                        }
                        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val downloadId = dm.enqueue(request)
                        activeDownloads[downloadId] = fileName
                        registerDownloadReceiver()
                        Toast.makeText(
                            this, "⬇️ শুরু হয়েছে: $fileName", Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this, "DownloadManager ব্যর্থ, retry করছে...", Toast.LENGTH_SHORT
                        ).show()
                        downloadManually(url, cookies, ua, contentDisposition, mimetype, fileName)
                    }
                }.setNegativeButton("Cancel", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadBlobUrl(blobUrl: String, webView: WebView) {
        val js = """
            javascript:(function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$blobUrl', true);
                xhr.responseType = 'blob';
                xhr.onload = function(e) {
                    if (this.status == 200) {
                        var blob = this.response;
                        var reader = new FileReader();
                        reader.readAsDataURL(blob);
                        reader.onloadend = function() { AndroidInterface.downloadBase64(reader.result, blob.type); }
                    }
                };
                xhr.send();
            })()
        """.trimIndent()
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun downloadBase64(dataUrl: String, mimeType: String) {
                runOnUiThread { downloadDataUrl(dataUrl, mimeType) }
            }
        }, "AndroidInterface")
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Blob ফাইল প্রস্তুত করছে...", Toast.LENGTH_SHORT).show()
    }

    private fun downloadDataUrl(dataUrl: String, overrideMime: String = "") {
        Thread {
            try {
                val commaIdx = dataUrl.indexOf(',')
                if (commaIdx == -1) {
                    runOnUiThread {
                        Toast.makeText(this, "Invalid data URL", Toast.LENGTH_SHORT).show()
                    }; return@Thread
                }
                val header = dataUrl.substring(0, commaIdx)
                val base64Data = dataUrl.substring(commaIdx + 1)
                val mimeType = if (overrideMime.isNotEmpty()) overrideMime
                else header.substringAfter("data:").substringBefore(";")
                    .ifBlank { "application/octet-stream" }
                val ext =
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val fileName = "download_${System.currentTimeMillis()}.$ext"
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                    val uri =
                        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        runOnUiThread {
                            Toast.makeText(
                                this, "✅ সংরক্ষিত: $fileName", Toast.LENGTH_LONG
                            ).show()
                        }
                    } else runOnUiThread {
                        Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val destDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    destDir.mkdirs()
                    FileOutputStream(File(destDir, fileName)).use { it.write(bytes) }
                    runOnUiThread {
                        Toast.makeText(
                            this, "✅ সংরক্ষিত: $fileName", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this, "Data URL save failed: ${e.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun downloadManually(
        url: String, cookies: String, userAgent: String,
        contentDisposition: String, mimetype: String, customFileName: String? = null
    ) {
        val fileName = customFileName
            ?: sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimetype))
        Toast.makeText(this, "⬇️ Manual download: $fileName", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", userAgent)
                if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies)
                connection.setRequestProperty("Accept", "*/*")
                connection.instanceFollowRedirects = true
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    runOnUiThread {
                        Toast.makeText(
                            this, "Server error: HTTP $code", Toast.LENGTH_LONG
                        ).show()
                    }; return@Thread
                }
                inputStream = connection.inputStream
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(
                            MediaStore.Downloads.MIME_TYPE,
                            mimetype.ifBlank { "application/octet-stream" }
                        )
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri =
                        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)
                            ?.use { inputStream.copyTo(it, 8192) }
                        cv.clear()
                        cv.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, cv, null, null)
                        runOnUiThread {
                            Toast.makeText(
                                this, "✅ ডাউনলোড সম্পন্ন: $fileName", Toast.LENGTH_LONG
                            ).show()
                        }
                    } else runOnUiThread {
                        Toast.makeText(this, "Storage write failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val destDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    destDir.mkdirs()
                    FileOutputStream(File(destDir, fileName)).use {
                        inputStream.copyTo(it, 8192)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this, "✅ ডাউনলোড সম্পন্ন: $fileName", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this, "Download failed: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try { inputStream?.close() } catch (e: Exception) { }
                try { connection?.disconnect() } catch (e: Exception) { }
            }
        }.start()
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiver != null) return
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id =
                    intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
                val fileName = activeDownloads.remove(id) ?: return
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    when (if (statusCol >= 0) cursor.getInt(statusCol) else -1) {
                        DownloadManager.STATUS_SUCCESSFUL ->
                            Toast.makeText(
                                this@MainActivity,
                                "✅ ডাউনলোড সম্পন্ন: $fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        DownloadManager.STATUS_FAILED -> {
                            val rc =
                                cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (rc >= 0) cursor.getInt(rc) else 0
                            Toast.makeText(
                                this@MainActivity,
                                "⚠️ ব্যর্থ (${downloadFailReason(reason)})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                cursor.close()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun downloadFailReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "Resume করা যাচ্ছে না"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage পাওয়া যাচ্ছে না"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "ফাইল আগে থেকে আছে"
        DownloadManager.ERROR_FILE_ERROR -> "ফাইল error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "স্টোরেজ কম"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "অনেক redirect"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unsupported HTTP code"
        DownloadManager.ERROR_UNKNOWN -> "অজানা error"
        else -> "Error code: $reason"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.length > 200) cleaned.substring(0, 200)
        else cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    // =====================================================================
    // BACK BUTTON
    // =====================================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> {
                val chromeClient = try {
                    webTabs[currentTabIndex].webView.webChromeClient
                } catch (e: Exception) { null }
                if (chromeClient != null) chromeClient.onHideCustomView()
                else {
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    (window.decorView as FrameLayout).removeView(customView)
                    customView = null
                    tabLayout.visibility = View.VISIBLE
                    requestedOrientation = originalOrientation
                }
            }
            activeSection == "browser" -> {
                val canGoBack = try {
                    webTabs.isNotEmpty() && webTabs[currentTabIndex].webView.canGoBack()
                } catch (e: Exception) { false }
                if (canGoBack) webTabs[currentTabIndex].webView.goBack()
                else @Suppress("DEPRECATION") super.onBackPressed()
            }
            activeSection == "files" -> {
                try {
                    val storagePath = Environment.getExternalStorageDirectory().absolutePath
                    if (currentPath.absolutePath != storagePath) goUp()
                    else @Suppress("DEPRECATION") super.onBackPressed()
                } catch (e: Exception) {
                    @Suppress("DEPRECATION") super.onBackPressed()
                }
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // =====================================================================
    // STATE SAVE
    // =====================================================================

    private fun saveState() {
        if (!isUIInitialized) return
        try {
            val urls = webTabs.mapNotNull {
                try {
                    it.webView.url?.takeIf { u -> u.isNotEmpty() } ?: "about:blank"
                } catch (e: Exception) { null }
            }.toSet()
            prefs.edit().apply {
                putString(KEY_CURRENT_PATH, currentPath.absolutePath)
                putStringSet(KEY_TAB_URLS, urls)
                putInt(KEY_CURRENT_TAB, currentTabIndex)
                putBoolean(KEY_IS_FILE_MANAGER, activeSection == "files")
                putBoolean(KEY_DESKTOP_MODE, globalDesktopMode)
                apply()
            }
        } catch (e: Exception) { }
    }

    // =====================================================================
    // UI HELPERS
    // =====================================================================

    private fun createTabButton(
        text: String, selected: Boolean, onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
            setBackgroundColor(
                if (selected) Color.parseColor("#333333") else Color.TRANSPARENT
            )
            setOnClickListener {
                for (i in 0 until tabLayout.childCount) {
                    tabLayout.getChildAt(i)?.setBackgroundColor(Color.TRANSPARENT)
                }
                setBackgroundColor(Color.parseColor("#333333"))
                onClick()
            }
        }
    }
}
