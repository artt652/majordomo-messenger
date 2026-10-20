package com.majordomo.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.app.NotificationCompat

class MainActivity : Activity() {
    private lateinit var launcher: WebView
    private lateinit var site: WebView
    private lateinit var root: FrameLayout
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingRequest: PermissionRequest? = null
    private var pendingResources = emptyArray<String>()
    @Volatile private var activeSiteUrl: String = ""
    private val permissionRequestCode = 301
    private val fileChooserCode = 401
    private val notificationPermissionRequestCode = 501
    private var pendingUnreadCount: Int? = null
    private val unreadNotificationId = 7301
    private val unreadChannelId = "majordomo_unread_messages"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android's system information row (time, battery, network) visible.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(48, 63, 159)
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        CookieManager.getInstance().setAcceptCookie(true)
        createUnreadNotificationChannel()

        root = FrameLayout(this)
        // Android 15+ enforces edge-to-edge for apps targeting SDK 35.
        // Keep the system status bar visible and reserve its inset so WebView
        // content (including full-screen pages) cannot draw over the clock/icons.
        root.setBackgroundColor(Color.rgb(48, 63, 159))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, statusTop, 0, navigationBottom)
            insets
        }
        launcher = WebView(this)
        site = WebView(this)

        configureWebView(launcher)
        configureWebView(site)
        CookieManager.getInstance().setAcceptThirdPartyCookies(launcher, true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(site, true)

        launcher.addJavascriptInterface(PageBridge(), "MajordomoApp")
        launcher.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    openSite(uri.toString())
                    return true
                }
                if (uri.scheme == "file") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, uri)); true }
                catch (_: Exception) { true }
            }
        }

        site.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                if (uri.scheme == "file") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, uri)); true }
                catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank() && url.startsWith("http")) activeSiteUrl = url
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame &&
                    error.errorCode != -3 &&
                    error.errorCode != WebViewClient.ERROR_UNSUPPORTED_SCHEME) {
                    returnHomeAfterError(request.url.toString(), error.description?.toString() ?: "Ошибка сети")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                super.onReceivedHttpError(view, request, response)
                // Do not intercept 401/403: some sites use them during authentication.
                // Ignore subresource errors such as a missing image or script.
                if (request.isForMainFrame && response.statusCode >= 400 &&
                    response.statusCode != 401 && response.statusCode != 403) {
                    returnHomeAfterError(request.url.toString(), "HTTP ${response.statusCode}")
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
            }
        }

        site.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    val unread = parseUnreadCount(title)
                    updateUnreadBadge(unread)
                }
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null)
                chooser = callback
                return try {
                    startActivityForResult(params.createIntent(), fileChooserCode)
                    true
                } catch (_: Exception) {
                    chooser = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val resources = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()
                    if (resources.isEmpty()) { request.deny(); return@runOnUiThread }
                    val needed = mutableListOf<String>()
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources &&
                        checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.CAMERA)
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (needed.isEmpty()) request.grant(resources)
                    else {
                        pendingRequest = request
                        pendingResources = resources
                        requestPermissions(needed.toTypedArray(), permissionRequestCode)
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }

        site.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setTitle(filename).setDescription("Загрузка файла")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Загрузка началась", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(launcher, FrameLayout.LayoutParams(-1, -1))
        root.addView(site, FrameLayout.LayoutParams(-1, -1))
        site.visibility = View.GONE
        setContentView(root)

        if (savedInstanceState != null) {
            val showingSite = savedInstanceState.getBoolean("showing_site", false)
            val launcherState = savedInstanceState.getBundle("launcher_state")
            val siteState = savedInstanceState.getBundle("site_state")
            if (showingSite && siteState != null) site.restoreState(siteState)
            else if (launcherState != null) launcher.restoreState(launcherState)
            else launcher.loadUrl("file:///android_asset/index.html")
            if (showingSite && site.url != null) {
                activeSiteUrl = site.url ?: ""
                site.visibility = View.VISIBLE
                launcher.visibility = View.GONE
            } else {
                site.visibility = View.GONE
                launcher.visibility = View.VISIBLE
            }
        } else {
            launcher.loadUrl("file:///android_asset/index.html")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
    }

    private fun createUnreadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                unreadChannelId,
                "Непрочитанные сообщения",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Индикатор непрочитанных сообщений Majordomo"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun parseUnreadCount(title: String): Int {
        // Common title formats: "(3) Majordomo", "Majordomo (3)", "3 - Majordomo",
        // "[3] Majordomo", or "3 непрочитанных сообщения".
        val patterns = listOf(
            Regex("[\\[(](\\d{1,5})[\\])]"), // count anywhere in (3) or [3]
            Regex("^\\s*(\\d{1,5})\\s*[-:|—–]\\s*"),
            Regex("^\\s*(\\d{1,5})\\s+(?:непрочитан|unread)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(title) ?: continue
            return match.groupValues[1].toIntOrNull()?.coerceAtMost(99999) ?: 0
        }
        return 0
    }

    private fun updateUnreadBadge(count: Int) {
        runOnUiThread {
            if (count <= 0) {
                pendingUnreadCount = null
                if (Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(unreadNotificationId)
                }
                return@runOnUiThread
            }

            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingUnreadCount = count
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
                return@runOnUiThread
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
            val notification = NotificationCompat.Builder(this, unreadChannelId)
                .setSmallIcon(icon)
                .setContentTitle("Majordomo Message")
                .setContentText(if (count == 1) "1 непрочитанное сообщение" else "$count непрочитанных сообщений")
                .setNumber(count)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(unreadNotificationId, notification)
            } catch (_: SecurityException) {
                // Notifications may be disabled by the user or device policy.
            }
        }
    }

    private fun normalizeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val url = if (value.contains("://")) value else "https://$value"
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun openSite(rawUrl: String) {
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            launcher.evaluateJavascript("window.majordomoInvalidUrl && window.majordomoInvalidUrl()", null)
            return
        }
        activeSiteUrl = url
        launcher.visibility = View.GONE
        site.visibility = View.VISIBLE
        startBackgroundSession()
        site.loadUrl(url)
    }

    private fun startBackgroundSession() {
        val intent = Intent(this, BackgroundSessionService::class.java).setAction(BackgroundSessionService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Exception) {
            // The WebView remains usable even if background execution is restricted by the OS.
        }
    }

    private fun stopBackgroundSession() {
        try {
            startService(Intent(this, BackgroundSessionService::class.java).setAction(BackgroundSessionService.ACTION_STOP))
        } catch (_: Exception) {
            stopService(Intent(this, BackgroundSessionService::class.java))
        }
    }

    private fun returnHomeAfterError(url: String, reason: String) {
        runOnUiThread {
            // The callback is already restricted to main-frame failures / HTTP errors.
            // Do not compare against site.url here: during HTTP redirects WebView may
            // still report the previous URL, causing the fallback to be silently skipped.
            if (site.visibility != View.VISIBLE) return@runOnUiThread
            site.stopLoading()
            site.visibility = View.GONE
            launcher.visibility = View.VISIBLE
            stopBackgroundSession()
            launcher.evaluateJavascript(
                "window.majordomoShowError && window.majordomoShowError(" +
                    org.json.JSONObject.quote(url) + "," + org.json.JSONObject.quote(reason) + ")",
                null
            )
        }
    }

    inner class PageBridge {
        @android.webkit.JavascriptInterface
        fun openSite(url: String) {
            runOnUiThread { this@MainActivity.openSite(url) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fileChooserCode) {
            chooser?.onReceiveValue(if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null)
            chooser = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionRequestCode) {
            val count = pendingUnreadCount
            pendingUnreadCount = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && count != null) {
                updateUnreadBadge(count)
            }
            return
        }
        if (requestCode == permissionRequestCode) {
            val request = pendingRequest
            if (request != null) {
                val granted = pendingResources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }.toTypedArray()
                if (granted.isNotEmpty()) request.grant(granted) else request.deny()
            }
            pendingRequest = null
            pendingResources = emptyArray()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::launcher.isInitialized) {
            val launcherState = Bundle()
            launcher.saveState(launcherState)
            outState.putBundle("launcher_state", launcherState)
        }
        if (::site.isInitialized) {
            val siteState = Bundle()
            site.saveState(siteState)
            outState.putBundle("site_state", siteState)
        }
        outState.putBoolean("showing_site", ::site.isInitialized && site.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::site.isInitialized && site.visibility == View.VISIBLE) {
            if (site.canGoBack()) site.goBack() else {
                site.stopLoading()
                site.visibility = View.GONE
                launcher.visibility = View.VISIBLE
            }
        } else if (::launcher.isInitialized && launcher.canGoBack()) {
            launcher.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        // Persist session cookies when leaving the app; deliberately do not pause
        // WebView timers so supported web sessions can continue while backgrounded.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::site.isInitialized && site.visibility == View.VISIBLE) {
            site.onResume()
            site.resumeTimers()
        }
    }

    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        pendingRequest?.deny()
        CookieManager.getInstance().flush()
        if (::site.isInitialized) site.destroy()
        if (::launcher.isInitialized) launcher.destroy()
        super.onDestroy()
    }
}
