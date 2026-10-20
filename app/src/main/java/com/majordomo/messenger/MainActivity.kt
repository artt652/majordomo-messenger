package com.majordomo.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(63, 81, 181)
        window.navigationBarColor = Color.BLACK

        CookieManager.getInstance().setAcceptCookie(true)

        root = FrameLayout(this)
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
                    error.errorCode != WebViewClient.ERROR_ABORTED &&
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
            val restored = if (showingSite) site.restoreState(savedInstanceState) else launcher.restoreState(savedInstanceState)
            if (restored == null) launcher.loadUrl("file:///android_asset/index.html")
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
        site.loadUrl(url)
    }

    private fun returnHomeAfterError(url: String, reason: String) {
        runOnUiThread {
            if (site.visibility != View.VISIBLE) return@runOnUiThread
            // Ignore late errors from requests that are no longer the current top-level page.
            val current = site.url ?: activeSiteUrl
            if (url != current && url != activeSiteUrl) return@runOnUiThread
            site.stopLoading()
            site.visibility = View.GONE
            launcher.visibility = View.VISIBLE
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
            runOnUiThread { openSite(url) }
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
        super.onSaveInstanceState(outState)
        if (::launcher.isInitialized) launcher.saveState(outState)
        if (::site.isInitialized) site.saveState(outState)
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

    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        pendingRequest?.deny()
        CookieManager.getInstance().flush()
        if (::site.isInitialized) site.destroy()
        if (::launcher.isInitialized) launcher.destroy()
        super.onDestroy()
    }
}
