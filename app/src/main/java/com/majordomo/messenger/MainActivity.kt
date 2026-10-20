package com.majordomo.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Toast
import android.graphics.Color

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingRequest: PermissionRequest? = null
    private var pendingResources = emptyArray<String>()
    @Volatile private var activeFrameUrl: String = ""
    private val permissionRequestCode = 301
    private val fileChooserCode = 401

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(63, 81, 181)
        window.navigationBarColor = Color.BLACK
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
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
        web.addJavascriptInterface(PageBridge(), "MajordomoApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame && url != "file:///android_asset/index.html" && !url.startsWith("file:///android_asset/")) {
                    // Keep the launcher as the top-level document; show navigated sites in its full-screen frame.
                    runOnUiThread { loadFrame(url) }
                    return true
                }
                val scheme = request.url.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    // Navigations within the iframe (including server redirects) identify the
                    // currently displayed page. Subresources do not enter shouldOverrideUrlLoading.
                    if (!request.isForMainFrame) activeFrameUrl = url
                    return false
                }
                if (scheme == "file") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true }
                catch (_: Exception) { true }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (isTargetFrameError(request.url.toString(), error.errorCode)) {
                    runOnUiThread { showHomeAfterError(request.url.toString(), error.description?.toString() ?: "Ошибка сети") }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                super.onReceivedHttpError(view, request, response)
                if (request.url.toString() == activeFrameUrl && response.statusCode >= 400) {
                    runOnUiThread { showHomeAfterError(request.url.toString(), "HTTP ${response.statusCode}") }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
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
        web.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
        setContentView(web)
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
            if (web.url == null) web.loadUrl("file:///android_asset/index.html")
        } else {
            web.loadUrl("file:///android_asset/index.html")
        }
    }

    private fun loadFrame(rawUrl: String) {
        val url = normalizeUrl(rawUrl) ?: return
        activeFrameUrl = url
        web.evaluateJavascript("window.majordomoLoad && window.majordomoLoad(" + org.json.JSONObject.quote(url) + ")", null)
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

    private fun isTargetFrameError(url: String, errorCode: Int): Boolean {
        if (url != activeFrameUrl) return false
        // Ignore certificate-related errors here; Android's normal SSL error flow is handled separately.
        return errorCode != android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME
    }

    private fun showHomeAfterError(url: String, reason: String) {
        if (url != activeFrameUrl) return
        web.evaluateJavascript(
            "window.majordomoShowError && window.majordomoShowError(" +
                org.json.JSONObject.quote(url) + "," + org.json.JSONObject.quote(reason) + ")",
            null
        )
    }

    inner class PageBridge {
        @android.webkit.JavascriptInterface
        fun openSite(url: String) {
            runOnUiThread {
                val normalized = normalizeUrl(url)
                if (normalized == null) {
                    web.evaluateJavascript("window.majordomoInvalidUrl && window.majordomoInvalidUrl()", null)
                } else {
                    loadFrame(normalized)
                }
            }
        }
        @android.webkit.JavascriptInterface
        fun setActiveUrl(url: String) {
            activeFrameUrl = normalizeUrl(url) ?: ""
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
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.majordomoGoBack && window.majordomoGoBack()", null)
    }

    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        pendingRequest?.deny()
        CookieManager.getInstance().flush()
        web.destroy()
        super.onDestroy()
    }
}
