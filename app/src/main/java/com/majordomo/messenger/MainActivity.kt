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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.net.URLEncoder

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var back: Button
    private lateinit var forward: Button
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingRequest: PermissionRequest? = null
    private var pendingResources = emptyArray<String>()
    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private val history = mutableListOf<String>()
    private val bookmarks = mutableListOf<String>()
    private val blue = Color.rgb(63, 81, 181)
    private val requestPermissionsCode = 301
    private val fileChooserCode = 401

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = blue
        loadLists()
        buildUi()
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
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, uri)); true }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show(); true }
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                if (!url.isNullOrBlank() && !url.startsWith("data:")) address.setText(url)
                updateButtons()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                status.text = view.title?.takeIf { it.isNotBlank() } ?: (url ?: "Готово")
                if (!url.isNullOrBlank() && !url.startsWith("data:") && !url.contains("majordomo.local")) addHistory(url)
                if (!url.isNullOrBlank() && !url.startsWith("data:") && !url.contains("majordomo.local")) address.setText(url)
                CookieManager.getInstance().flush()
                updateButtons()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) { progress.visibility = View.GONE; status.text = "Ошибка загрузки: ${error.description}" }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { progress.progress = value; progress.visibility = if (value in 1..99) View.VISIBLE else View.GONE }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null); chooser = callback
                return try { startActivityForResult(params.createIntent(), fileChooserCode); true }
                catch (_: Exception) { chooser = null; false }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val resources = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toTypedArray()
                    if (resources.isEmpty()) { request.deny(); return@runOnUiThread }
                    val needed = mutableListOf<String>()
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
                    if (needed.isEmpty()) request.grant(resources) else { pendingRequest = request; pendingResources = resources; requestPermissions(needed.toTypedArray(), requestPermissionsCode) }
                }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
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
            } catch (e: Exception) { Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show() }
        })
        if (savedInstanceState != null) { web.restoreState(savedInstanceState); address.setText(web.url ?: "") } else showHome()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), dp(5), dp(7), dp(3)); setBackgroundColor(blue) }
        val addressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            hint = "Адрес сайта или поисковый запрос"; setSingleLine(true); textSize = 14f
            setTextColor(Color.BLACK); setHintTextColor(Color.GRAY); setPadding(dp(9), 0, dp(8), 0); setBackgroundColor(Color.WHITE)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) { navigate(text.toString()); true } else false
            }
        }
        addressRow.addView(address); addressRow.addView(button("Открыть") { navigate(address.text.toString()) }); toolbar.addView(addressRow)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back = button("‹") { if (web.canGoBack()) web.goBack() else showHome() }
        forward = button("›") { if (web.canGoForward()) web.goForward() }
        controls.addView(back); controls.addView(forward)
        controls.addView(button("↻") { web.reload() }); controls.addView(button("Домой") { showHome() })
        controls.addView(button("История") { showList(false) }); controls.addView(button("★") { toggleBookmark() }); controls.addView(button("Закладки") { showList(true) })
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(controls) }
        toolbar.addView(horizontal)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        status = TextView(this).apply { text = "Готово"; textSize = 11f; setTextColor(Color.DKGRAY); setPadding(dp(7), dp(3), dp(7), dp(3)); maxLines = 1 }
        web = WebView(this).apply { setBackgroundColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        root.addView(toolbar); root.addView(progress, LinearLayout.LayoutParams(-1, dp(3))); root.addView(status); root.addView(web)
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; minWidth = dp(38); minimumWidth = dp(38); minHeight = dp(42); minimumHeight = dp(42)
        setPadding(dp(4), 0, dp(4), 0); setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(blue)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); setOnClickListener { action() }
    }

    private fun navigate(raw: String) {
        val value = raw.trim(); if (value.isEmpty()) return
        val url = when { value.contains("://") -> value; value.contains(' ') -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8"); else -> "https://$value" }
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") { Toast.makeText(this, "Введите адрес http:// или https://", Toast.LENGTH_LONG).show(); return }
        address.setText(url); web.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0); address.clearFocus()
    }

    private fun showHome() {
        val html = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:Arial,sans-serif;background:#f5f7fc;color:#202124;margin:0;padding:24px;text-align:center}.logo{font-size:24px;font-weight:bold;color:#3f51b5;margin-top:12vh}.hint{background:white;padding:18px;border-radius:12px;margin:24px auto;max-width:360px;box-shadow:0 2px 10px #0001}</style></head><body><div class="logo">Majordomo Message</div><p>Введите адрес сайта в верхней строке.</p><div class="hint">Сайт откроется в этом же окне. Панель навигации останется доступной.</div></body></html>"""
        web.loadDataWithBaseURL("https://majordomo.local/", html, "text/html", "UTF-8", null)
        address.setText(""); status.text = "Домашняя страница"; updateButtons()
    }

    private fun updateButtons() { if (!::back.isInitialized) return; back.alpha = if (web.canGoBack()) 1f else 0.6f; forward.isEnabled = web.canGoForward(); forward.alpha = if (web.canGoForward()) 1f else 0.6f }
    private fun addHistory(url: String) { history.remove(url); history.add(0, url); while (history.size > 100) history.removeAt(history.lastIndex); saveLists() }
    private fun toggleBookmark() {
        val url = web.url ?: address.text.toString().trim()
        if (url.isBlank() || url.startsWith("data:") || url.contains("majordomo.local")) { Toast.makeText(this, "Сначала откройте сайт", Toast.LENGTH_SHORT).show(); return }
        if (bookmarks.remove(url)) Toast.makeText(this, "Закладка удалена", Toast.LENGTH_SHORT).show() else { bookmarks.add(0, url); Toast.makeText(this, "Добавлено в закладки", Toast.LENGTH_SHORT).show() }
        saveLists()
    }
    private fun showList(isBookmarks: Boolean) {
        val items = (if (isBookmarks) bookmarks else history).toList()
        if (items.isEmpty()) { Toast.makeText(this, if (isBookmarks) "Закладок пока нет" else "История пока пуста", Toast.LENGTH_SHORT).show(); return }
        val dialog = android.app.AlertDialog.Builder(this).setTitle(if (isBookmarks) "Закладки" else "История").setPositiveButton("Закрыть", null).setNeutralButton("Очистить") { _, _ -> if (isBookmarks) bookmarks.clear() else history.clear(); saveLists() }.create()
        val scroll = ScrollView(this); val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        items.forEach { url ->
            val row = TextView(this).apply { text = url; textSize = 14f; setTextColor(Color.BLACK); setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { dialog.dismiss(); navigate(url) } }
            list.addView(row); list.addView(View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        scroll.addView(list); dialog.setView(scroll); dialog.show()
    }
    private fun loadLists() {
        history.clear(); bookmarks.clear()
        try { val h = JSONArray(prefs.getString("history", "[]")); for (i in 0 until h.length()) history.add(h.getString(i)); val b = JSONArray(prefs.getString("bookmarks", "[]")); for (i in 0 until b.length()) bookmarks.add(b.getString(i)) } catch (_: Exception) { }
    }
    private fun saveLists() { val h = JSONArray(); history.forEach { h.put(it) }; val b = JSONArray(); bookmarks.forEach { b.put(it) }; prefs.edit().putString("history", h.toString()).putString("bookmarks", b.toString()).apply() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fileChooserCode) { chooser?.onReceiveValue(if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null); chooser = null }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionsCode) {
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
            pendingRequest = null; pendingResources = emptyArray()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (web.canGoBack()) web.goBack() else showHome() }
    override fun onDestroy() { chooser?.onReceiveValue(null); pendingRequest?.deny(); CookieManager.getInstance().flush(); web.destroy(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
