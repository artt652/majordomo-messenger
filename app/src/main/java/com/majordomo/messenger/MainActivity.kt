package com.majordomo.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.widget.SeekBar
import android.widget.VideoView
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.database.Cursor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.app.NotificationCompat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class MainActivity : Activity() {
    private lateinit var launcher: WebView
    private lateinit var site: WebView
    private lateinit var root: FrameLayout
    private var mediaDiagnosticsPanel: LinearLayout? = null
    private var mediaDiagnosticsText: TextView? = null
    private val mediaDiagnosticsLines = java.util.ArrayDeque<String>()
    private var qrScannerOverlay: FrameLayout? = null
    private var qrBarcodeView: DecoratedBarcodeView? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingCaptureUri: Uri? = null
    private var pendingCaptureFile: File? = null
    private data class PendingMediaRequest(
        val request: PermissionRequest,
        val resources: Array<String>
    )
    private val queuedMediaRequests = java.util.ArrayDeque<PendingMediaRequest>()
    private var pendingRequest: PermissionRequest? = null
    private var pendingResources = emptyArray<String>()
    private var pendingAndroidPermissions = emptyList<String>()
    private var permissionRequestInProgress = false
    @Volatile private var activeSiteUrl: String = ""
    @Volatile private var lastSuccessfulSiteUrl: String = ""
    private var offlineCacheFallbackInProgress = false
    private val permissionRequestCode = 301
    private val qrCameraPermissionRequestCode = 302
    private val fileChooserCode = 401
    private val cameraCaptureCode = 402
    private val notificationPermissionRequestCode = 501
    private var pendingUnreadCount: Int? = null
    private val unreadNotificationId = 7301
    private val unreadChannelId = "majordomo_unread_messages"

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION") // Keep status/navigation bar colors for devices below API 35.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android's system information row (time, battery, network) visible.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(17, 19, 24)
        window.navigationBarColor = Color.rgb(17, 19, 24)
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
        launcher.webChromeClient = createMediaWebChromeClient(launcher)
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
                    handleMainFrameNetworkError(
                        request.url.toString(),
                        error.description?.toString() ?: "Ошибка сети"
                    )
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
                if (view === site && !url.isNullOrBlank() && url.startsWith("http")) {
                    installMediaJavaScriptDiagnostics(view)
                    if (offlineCacheFallbackInProgress) {
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                        offlineCacheFallbackInProgress = false
                    }
                    lastSuccessfulSiteUrl = url
                }
            }
        }

        site.webChromeClient = createMediaWebChromeClient(site)

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
        createQrScannerOverlay()
        createMediaDiagnosticsUi()
        addMediaDiagnostic("Диагностика запущена. Нажмите «Диагностика», затем повторите запрос камеры или микрофона.")
        logMediaEnvironment()
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
    @Suppress("DEPRECATION") // Keep legacy WebView database setting for older WebView versions.
    private fun configureWebView(view: WebView) {
        view.setBackgroundColor(Color.rgb(24, 26, 32))
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

    private fun createMediaWebChromeClient(owner: WebView): WebChromeClient {
        return object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) updateUnreadBadge(parseUnreadCount(title))
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = "JS console: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                addMediaDiagnostic(message)
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                chooser?.onReceiveValue(null)
                chooser = callback
                pendingChooserParams = params

                // capture is only a hint to WebView. Launch the camera explicitly because
                // some Android file pickers ignore it and open the gallery instead.
                if (params.isCaptureEnabled) {
                    val mime = captureMimeType(params.acceptTypes)
                    if (mime != null && launchCameraCapture(mime)) return true
                }
                return launchFileChooser()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    addMediaDiagnostic(
                        "Запрос WebView: view=${if (owner === site) "site" else if (owner === launcher) "launcher" else "unknown"}\nСтраница: ${owner.url}\nOrigin: ${request.origin}\nРесурсы: ${request.resources.joinToString()}"
                    )
                    // Chromium only permits getUserMedia on secure origins:
                    // HTTPS, plus loopback addresses which Chromium treats as trustworthy.
                    val originUri = Uri.parse(request.origin.toString())
                    val originHost = originUri.host?.lowercase()
                    val secureOrigin = originUri.scheme == "https" ||
                        (originUri.scheme == "http" &&
                            (originHost == "localhost" || originHost == "127.0.0.1" || originHost == "::1"))
                    if (!secureOrigin) {
                        addMediaDiagnostic("ОТКАЗ: небезопасный origin. origin=${request.origin}; страница=${owner.url}; схема=${originUri.scheme}; host=$originHost")
                        request.deny()
                        Toast.makeText(
                            this@MainActivity,
                            "Для микрофона и камеры откройте сервер по HTTPS. Android WebView блокирует их на обычном HTTP.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    val resources = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()
                    if (resources.isEmpty()) {
                        addMediaDiagnostic("ОТКАЗ: нет поддерживаемых ресурсов. Запрошено: ${request.resources.joinToString()}")
                        request.deny()
                        return@runOnUiThread
                    }
                    val needed = mutableListOf<String>()
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources &&
                        checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.CAMERA)
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (needed.isEmpty()) {
                        grantMediaRequest(request, resources)
                    } else if (permissionRequestInProgress) {
                        // Pages can request audio and video separately. Queue the second
                        // request instead of rejecting it while Android's dialog is visible.
                        queuedMediaRequests.addLast(PendingMediaRequest(request, resources))
                    } else {
                        startAndroidMediaPermissionRequest(request, resources, needed)
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                val iterator = queuedMediaRequests.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().request === request) iterator.remove()
                }
                if (pendingRequest === request) {
                    // Keep the Android-dialog-in-progress flag until its result arrives;
                    // otherwise another request could launch a competing system dialog.
                    pendingRequest = null
                    pendingResources = emptyArray()
                    pendingAndroidPermissions = emptyList()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
    }

    private fun grantMediaRequest(request: PermissionRequest, resources: Array<String>) {
        try {
            request.grant(resources)
            addMediaDiagnostic("РАЗРЕШЕНО WebView: ${resources.joinToString()}")
        } catch (error: Exception) {
            addMediaDiagnostic("ОШИБКА выдачи разрешения: ${error.javaClass.simpleName}: ${error.message}")
            try { request.deny() } catch (_: Exception) {}
        }
    }

    private fun startAndroidMediaPermissionRequest(
        request: PermissionRequest,
        resources: Array<String>,
        needed: List<String>
    ) {
        pendingRequest = request
        pendingResources = resources
        pendingAndroidPermissions = needed
        permissionRequestInProgress = true
        addMediaDiagnostic("Запрос разрешений Android: origin=${request.origin}; ресурсы=${resources.joinToString()}; не хватает=${needed.joinToString()}")
        requestPermissions(needed.toTypedArray(), permissionRequestCode)
    }

    private fun processQueuedMediaRequests() {
        if (permissionRequestInProgress) return
        while (!queuedMediaRequests.isEmpty()) {
            val queued = queuedMediaRequests.removeFirst()
            val needed = mutableListOf<String>()
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in queued.resources &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA)
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in queued.resources &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
            if (needed.isEmpty()) {
                grantMediaRequest(queued.request, queued.resources)
            } else {
                startAndroidMediaPermissionRequest(queued.request, queued.resources, needed)
                return
            }
        }
    }

    private fun logMediaEnvironment() {
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName} (versionCode=${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" }
                ?: "provider unavailable"
        } else {
            "Android API < 26; provider API unavailable"
        }
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString {
            "type=${it.type}, product=${it.productName}"
        }.ifBlank { "(none reported)" }
        addMediaDiagnostic(
            "Среда: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
                "device=${Build.MANUFACTURER} ${Build.MODEL}; targetSdk=${applicationInfo.targetSdkVersion}; WebView=$provider"
        )
        addMediaDiagnostic(
            "Аудиосреда: mode=${audioManager.mode}; micMute=${audioManager.isMicrophoneMute}; " +
                "входы=[${inputDevices}]"
        )
    }

    private fun runNativeMicrophoneTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addMediaDiagnostic("Native AudioRecord: ОТКАЗ — RECORD_AUDIO не выдан Android")
            return
        }
        Thread {
            var recorder: AudioRecord? = null
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                addMediaDiagnostic(
                    "Native audio state: mode=${audioManager.mode}; micMute=${audioManager.isMicrophoneMute}; " +
                        "musicActive=${audioManager.isMusicActive}; activeRecordings=${audioManager.activeRecordingConfigurations.map { config -> "source=${config.clientAudioSource}, session=${config.clientAudioSessionId}" }}"
                )
                val sampleRate = 16000
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) {
                    addMediaDiagnostic("Native AudioRecord: getMinBufferSize вернул $minBuffer")
                    return@Thread
                }
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, sampleRate / 2)
                )
                addMediaDiagnostic("Native AudioRecord: state=${recorder.state} (STATE_INITIALIZED=1), buffer=$minBuffer")
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    addMediaDiagnostic("Native AudioRecord: ОШИБКА — не удалось инициализировать запись")
                    return@Thread
                }
                recorder.startRecording()
                addMediaDiagnostic("Native AudioRecord: recordingState=${recorder.recordingState} (RECORDSTATE_RECORDING=3)")
                val buffer = ShortArray(maxOf(minBuffer / 2, 1024))
                val read = recorder.read(buffer, 0, buffer.size)
                addMediaDiagnostic(
                    if (read > 0) "Native AudioRecord: УСПЕХ — прочитано $read аудиосэмплов. Звук не сохранялся."
                    else "Native AudioRecord: ОШИБКА чтения, код=$read"
                )
            } catch (e: SecurityException) {
                addMediaDiagnostic("Native AudioRecord SecurityException: ${e.message}")
            } catch (e: Exception) {
                addMediaDiagnostic("Native AudioRecord ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun installMediaJavaScriptDiagnostics(view: WebView) {
        val script = """
            (function() {
              try {
                if (window.__majordomoMediaDiagInstalled) {
                  console.log('[MediaDiag] instrumentation already installed');
                  return;
                }
                window.__majordomoMediaDiagInstalled = true;
                console.log('[MediaDiag] installed; url=' + location.href +
                  '; secureContext=' + window.isSecureContext +
                  '; mediaDevices=' + !!(navigator.mediaDevices) +
                  '; getUserMedia=' + !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia));
                if (navigator.permissions && navigator.permissions.query) {
                  ['microphone', 'camera'].forEach(function(name) {
                    navigator.permissions.query({name:name}).then(function(p) {
                      console.log('[MediaDiag] permission ' + name + '=' + p.state);
                      p.onchange = function() { console.log('[MediaDiag] permission changed ' + name + '=' + p.state); };
                    }).catch(function(e) {
                      console.log('[MediaDiag] permission query ' + name + ' failed: ' + e.name + ': ' + e.message);
                    });
                  });
                }
                if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                  var originalGum = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
                  navigator.mediaDevices.getUserMedia = function(constraints) {
                    console.log('[MediaDiag] getUserMedia called; constraints=' + JSON.stringify(constraints));
                    var started = Date.now();
                    return originalGum(constraints).then(function(stream) {
                      var tracks = stream.getTracks().map(function(t) {
                        return {kind:t.kind, enabled:t.enabled, muted:t.muted, readyState:t.readyState, label:t.label};
                      });
                      console.log('[MediaDiag] getUserMedia SUCCESS after ' + (Date.now()-started) +
                        'ms; tracks=' + JSON.stringify(tracks));
                      stream.getTracks().forEach(function(t) {
                        t.onended = function() { console.log('[MediaDiag] track ended; kind=' + t.kind); };
                        t.onmute = function() { console.log('[MediaDiag] track muted; kind=' + t.kind); };
                        t.onunmute = function() { console.log('[MediaDiag] track unmuted; kind=' + t.kind); };
                      });
                      return stream;
                    }).catch(function(e) {
                      console.log('[MediaDiag] getUserMedia FAILED after ' + (Date.now()-started) +
                        'ms; name=' + e.name + '; message=' + e.message + '; constraint=' + (e.constraint || ''));
                      throw e;
                    });
                  };
                }
                if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
                  navigator.mediaDevices.enumerateDevices().then(function(devices) {
                    console.log('[MediaDiag] enumerateDevices=' + JSON.stringify(devices.map(function(d) {
                      return {kind:d.kind, label:d.label, deviceIdPresent:!!d.deviceId};
                    })));
                  }).catch(function(e) {
                    console.log('[MediaDiag] enumerateDevices FAILED: ' + e.name + ': ' + e.message);
                  });
                }
                window.addEventListener('unhandledrejection', function(event) {
                  var e = event.reason || {};
                  console.log('[MediaDiag] unhandledrejection: ' + (e.name || '') + ': ' + (e.message || String(event.reason)));
                });
              } catch (e) {
                console.log('[MediaDiag] instrumentation ERROR: ' + e.name + ': ' + e.message);
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
        addMediaDiagnostic("Установлена JS-диагностика страницы: " + (view.url ?: "(URL неизвестен)"))
    }

    private fun addMediaDiagnostic(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$timestamp  $message"
        android.util.Log.d("MajordomoMedia", line)
        runOnUiThread {
            mediaDiagnosticsLines.addLast(line)
            while (mediaDiagnosticsLines.size > 150) mediaDiagnosticsLines.removeFirst()
            mediaDiagnosticsText?.text = mediaDiagnosticsLines.joinToString("\n\n")
        }
    }

    private fun createMediaDiagnosticsUi() {
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(Color.rgb(25, 28, 35))
            visibility = View.GONE
            elevation = 30f
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Диагностика камеры и микрофона"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { panel.visibility = View.GONE }
        }
        header.addView(close)
        panel.addView(header)

        val scroll = ScrollView(this)
        val logText = TextView(this).apply {
            text = "Ожидание событий…"
            setTextColor(Color.rgb(230, 233, 240))
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        mediaDiagnosticsText = logText
        scroll.addView(logText)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(Button(this).apply {
            text = "Тест микрофона"
            setOnClickListener { runNativeMicrophoneTest() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Копировать"
            setOnClickListener {
                val text = mediaDiagnosticsLines.joinToString("\n\n")
                if (text.isBlank()) {
                    Toast.makeText(this@MainActivity, "Диагностика пока пуста", Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Majordomo media diagnostics", text))
                    Toast.makeText(this@MainActivity, "Диагностика скопирована", Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                mediaDiagnosticsLines.clear()
                mediaDiagnosticsText?.text = "Журнал очищен. Повторите действие с камерой или микрофоном."
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(actions)

        root.addView(panel, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.72f).toInt(), android.view.Gravity.BOTTOM).apply {
            leftMargin = (8 * density).toInt()
            rightMargin = (8 * density).toInt()
            bottomMargin = (8 * density).toInt()
        })
        mediaDiagnosticsPanel = panel

        val toggle = Button(this).apply {
            text = "Диагностика"
            setOnClickListener {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (panel.visibility == View.VISIBLE) {
                    mediaDiagnosticsText?.text = if (mediaDiagnosticsLines.isEmpty()) "Журнал пуст. Повторите действие с камерой или микрофоном." else mediaDiagnosticsLines.joinToString("\n\n")
                }
            }
            elevation = 35f
        }
        root.addView(toggle, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = (8 * density).toInt()
            marginEnd = (8 * density).toInt()
        })
    }

    private fun createQrScannerOverlay() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(220, 8, 10, 14))
            visibility = View.GONE
            isClickable = true
        }
        val card = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(28, 30, 36))
            elevation = 12f
        }
        val scanner = DecoratedBarcodeView(this).apply {
            setStatusText("Наведите камеру на QR-код")
            setBackgroundColor(Color.BLACK)
        }
        qrBarcodeView = scanner

        val cancel = android.widget.Button(this).apply {
            text = "Закрыть"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(55, 58, 68))
            setOnClickListener { hideQrScanner() }
        }
        card.addView(scanner, FrameLayout.LayoutParams(-1, 0).apply {
            height = (resources.displayMetrics.density * 360).toInt()
            gravity = android.view.Gravity.TOP
        })
        card.addView(cancel, FrameLayout.LayoutParams(-1, (resources.displayMetrics.density * 48).toInt()).apply {
            gravity = android.view.Gravity.BOTTOM
            leftMargin = (resources.displayMetrics.density * 12).toInt()
            rightMargin = (resources.displayMetrics.density * 12).toInt()
            bottomMargin = (resources.displayMetrics.density * 10).toInt()
        })
        val cardParams = FrameLayout.LayoutParams(-1, (resources.displayMetrics.density * 430).toInt()).apply {
            gravity = android.view.Gravity.CENTER
            leftMargin = (resources.displayMetrics.density * 24).toInt()
            rightMargin = (resources.displayMetrics.density * 24).toInt()
        }
        overlay.addView(card, cardParams)
        overlay.setOnClickListener { hideQrScanner() }
        card.setOnClickListener { /* Keep taps inside the scanner card from closing it. */ }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        qrScannerOverlay = overlay
    }

    private fun showQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), qrCameraPermissionRequestCode)
            return
        }
        val scanner = qrBarcodeView ?: return
        val overlay = qrScannerOverlay ?: return
        overlay.visibility = View.VISIBLE
        scanner.resume()
        scanner.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val value = result?.text?.takeIf { it.isNotBlank() } ?: return
                runOnUiThread {
                    hideQrScanner()
                    launcher.evaluateJavascript(
                        "window.majordomoQrResult && window.majordomoQrResult(" +
                            org.json.JSONObject.quote(value) + ")",
                        null
                    )
                }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
        })
    }

    private fun hideQrScanner() {
        qrBarcodeView?.pause()
        qrScannerOverlay?.visibility = View.GONE
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

    private fun handleMainFrameNetworkError(url: String, reason: String) {
        runOnUiThread {
            if (site.visibility != View.VISIBLE) return@runOnUiThread
            if (offlineCacheFallbackInProgress) {
                offlineCacheFallbackInProgress = false
                site.settings.cacheMode = WebSettings.LOAD_DEFAULT
                returnHomeAfterError(url, reason)
                return@runOnUiThread
            }

            val cachedUrl = lastSuccessfulSiteUrl.takeIf { it.isNotBlank() } ?: url
            if (cachedUrl.isBlank()) {
                returnHomeAfterError(url, reason)
                return@runOnUiThread
            }

            // Retry from WebView's disk cache first. This keeps the last usable page
            // available during short network outages; dynamic server data may be stale.
            offlineCacheFallbackInProgress = true
            site.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            site.loadUrl(cachedUrl)
            Toast.makeText(
                this@MainActivity,
                "Нет сети. Пробуем открыть сохранённую страницу…",
                Toast.LENGTH_SHORT
            ).show()
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

        @android.webkit.JavascriptInterface
        fun scanQr() {
            runOnUiThread { showQrScanner() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            cameraCaptureCode -> {
                val outputUri = pendingCaptureUri
                val outputFile = pendingCaptureFile
                val capturedUri = when {
                    resultCode != RESULT_OK -> null
                    data?.data != null -> data.data
                    outputUri != null && outputFile?.exists() == true && outputFile.length() > 0L -> outputUri
                    else -> null
                }

                if (capturedUri != null) {
                    prepareMediaForUpload(capturedUri) { uploadUri ->
                        if (uploadUri != null) {
                            chooser?.onReceiveValue(arrayOf(uploadUri))
                            clearPendingCapture(deleteFile = uploadUri != capturedUri || capturedUri != outputUri)
                        } else {
                            chooser?.onReceiveValue(null)
                            clearPendingCapture(deleteFile = true)
                        }
                        chooser = null
                        pendingChooserParams = null
                    }
                } else {
                    // Some camera apps return RESULT_CANCELED or do not write the output URI.
                    // Fall back to the normal picker so the user can still select an existing file.
                    clearPendingCapture(deleteFile = true)
                    if (!launchFileChooser()) finishFileChooser(null)
                }
            }
            fileChooserCode -> {
                val result = if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
                if (result.isNullOrEmpty()) {
                    finishFileChooser(null)
                } else {
                    // Prepare selected images and oversized videos before returning them to WebView.
                    prepareMediaBatch(result) { prepared ->
                        finishFileChooser(prepared)
                    }
                }
            }
        }
    }


    /**
     * Prepare an item before returning it through WebView's file chooser.
     * Photos keep the existing compression path; videos over the current page limit
     * are sent to the native trimming dialog. Other files are untouched.
     */
    private fun prepareMediaForUpload(uri: Uri, callback: (Uri?) -> Unit) {
        when {
            isImageUri(uri) -> Thread {
                val prepared = prepareImageForUpload(uri)
                runOnUiThread { callback(prepared) }
            }.start()
            isVideoUri(uri) -> Thread {
                // Some content providers do not expose OpenableColumns.SIZE. Materialize
                // those videos into our cache so the real size is known and trimming can run.
                val initialSize = getUriSize(uri)
                val measured = if (initialSize > 0L) {
                    Pair(uri, initialSize)
                } else {
                    copyVideoToCache(uri)
                }
                runOnUiThread {
                    if (measured == null || measured.second <= 0L) {
                        Toast.makeText(this, "Не удалось определить размер видео", Toast.LENGTH_LONG).show()
                        callback(null)
                    } else if (measured.second <= MAX_UPLOAD_VIDEO_BYTES) {
                        callback(measured.first)
                    } else {
                        showVideoTrimDialog(measured.first, callback)
                    }
                }
            }.start()
            else -> callback(uri)
        }
    }

    private fun prepareMediaBatch(uris: Array<Uri>, callback: (Array<Uri>?) -> Unit) {
        val prepared = ArrayList<Uri>(uris.size)
        fun next(index: Int) {
            if (index >= uris.size) {
                callback(prepared.toTypedArray())
                return
            }
            prepareMediaForUpload(uris[index]) { uri ->
                if (uri == null) {
                    callback(null)
                } else {
                    prepared.add(uri)
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    private fun isVideoUri(uri: Uri): Boolean {
        val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        if (mime?.startsWith("video/", ignoreCase = true) == true) return true
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
        val extension = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return extension in setOf("mp4", "m4v", "mov", "3gp", "webm", "mkv")
    }

    private fun copyVideoToCache(uri: Uri): Pair<Uri, Long>? {
        val directory = File(cacheDir, "video-trim").apply { mkdirs() }
        val output = File.createTempFile("source_", ".mp4", directory)
        return try {
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open video URI")
            input.use { source ->
                output.outputStream().use { destination -> source.copyTo(destination) }
            }
            if (output.length() <= 0L) {
                output.delete()
                null
            } else {
                Pair(FileProvider.getUriForFile(this, "$packageName.fileprovider", output), output.length())
            }
        } catch (error: Exception) {
            output.delete()
            addMediaDiagnostic("Не удалось скопировать видео для проверки размера: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun getUriSize(uri: Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val size = cursor.getLong(0)
                    if (size > 0L) return size
                }
            }
        } catch (_: Exception) { }
        return try {
            if (uri.scheme == "file") File(uri.path ?: return -1L).length() else -1L
        } catch (_: Exception) { -1L }
    }

    private fun showVideoTrimDialog(uri: Uri, callback: (Uri?) -> Unit) {
        val durationMs = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        } catch (_: Exception) { 0L }

        if (durationMs < 1000L) {
            Toast.makeText(this, "Не удалось определить длительность видео для обрезки", Toast.LENGTH_LONG).show()
            callback(null)
            return
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val preview = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(210))
            setVideoURI(uri)
            setOnPreparedListener { player ->
                player.isLooping = true
                start()
            }
        }
        rootLayout.addView(preview)

        val sizeMb = getUriSize(uri).toDouble() / (1024.0 * 1024.0)
        val info = TextView(this).apply {
            text = "Размер: %.1f МБ. Лимит: %.0f МБ. Выберите фрагмент видео.".format(
                java.util.Locale.getDefault(), sizeMb, MAX_UPLOAD_VIDEO_BYTES / (1024.0 * 1024.0)
            )
            setPadding(0, dp(8), 0, dp(8))
        }
        rootLayout.addView(info)

        val startLabel = TextView(this).apply { text = "Начало: 00:00" }
        val startBar = SeekBar(this).apply {
            max = 1000
            progress = 0
        }
        val endLabel = TextView(this).apply { text = "Конец: " + formatVideoTime(durationMs) }
        val endBar = SeekBar(this).apply {
            max = 1000
            progress = 1000
        }
        rootLayout.addView(startLabel)
        rootLayout.addView(startBar)
        rootLayout.addView(endLabel)
        rootLayout.addView(endBar)

        var startMs = 0L
        var endMs = durationMs
        startBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                startMs = durationMs * progress / 1000L
                if (startMs >= endMs - 1000L) {
                    startMs = (endMs - 1000L).coerceAtLeast(0L)
                    startBar.progress = (startMs * 1000L / durationMs).toInt()
                }
                startLabel.text = "Начало: " + formatVideoTime(startMs)
                preview.seekTo(startMs.toInt())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        endBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                endMs = durationMs * progress / 1000L
                if (endMs <= startMs + 1000L) {
                    endMs = (startMs + 1000L).coerceAtMost(durationMs)
                    endBar.progress = (endMs * 1000L / durationMs).toInt()
                }
                endLabel.text = "Конец: " + formatVideoTime(endMs)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Обрезка видео")
            .setView(rootLayout)
            .setNegativeButton("Отмена") { _, _ ->
                preview.stopPlayback()
                callback(null)
            }
            .setPositiveButton("Обрезать") { _, _ ->
                preview.stopPlayback()
                Toast.makeText(this, "Обрезаем видео…", Toast.LENGTH_SHORT).show()
                Thread {
                    val output = try { trimVideo(uri, startMs, endMs) } catch (error: Exception) {
                        addMediaDiagnostic("Обрезка видео не удалась: ${error.javaClass.simpleName}: ${error.message}")
                        null
                    }
                    runOnUiThread {
                        if (output == null) {
                            Toast.makeText(this, "Не удалось обрезать видео. Попробуйте другой файл.", Toast.LENGTH_LONG).show()
                            callback(null)
                        } else {
                            val outputUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
                            if (output.length() > MAX_UPLOAD_VIDEO_BYTES) {
                                Toast.makeText(this, "Фрагмент всё ещё больше лимита. Выберите более короткий.", Toast.LENGTH_LONG).show()
                                showVideoTrimDialog(outputUri, callback)
                            } else {
                                callback(outputUri)
                            }
                        }
                    }
                }.start()
            }
            .create()
        dialog.setOnDismissListener { preview.stopPlayback() }
        dialog.show()
    }

    private fun trimVideo(uri: Uri, requestedStartMs: Long, requestedEndMs: Long): File {
        val directory = File(cacheDir, "video-trim").apply { mkdirs() }
        directory.listFiles()?.filter {
            System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
        }?.forEach { it.delete() }
        val output = File.createTempFile("trim_", ".mp4", directory)
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(this, uri, null)
            val retriever = MediaMetadataRetriever()
            val rotation = try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            val trackMap = HashMap<Int, Int>()
            var videoTrackFound = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    extractor.selectTrack(i)
                    if (mime.startsWith("video/")) videoTrackFound = true
                }
            }
            if (!videoTrackFound || trackMap.isEmpty()) throw IllegalArgumentException("No supported audio/video tracks")
            muxer.start()
            muxerStarted = true

            val startUs = requestedStartMs * 1000L
            val endUs = requestedEndMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstSampleTime = extractor.sampleTime.coerceAtLeast(0L)
            val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            val sampleInfo = MediaCodec.BufferInfo()
            while (true) {
                val sourceTrack = extractor.sampleTrackIndex
                if (sourceTrack < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break
                val outputTrack = trackMap[sourceTrack]
                if (outputTrack != null) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    sampleInfo.set(
                        0,
                        sampleSize,
                        (sampleTime - firstSampleTime).coerceAtLeast(0L),
                        extractor.sampleFlags
                    )
                    muxer.writeSampleData(outputTrack, buffer, sampleInfo)
                }
                if (!extractor.advance()) break
            }
            muxer.stop()
            muxerStarted = false
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun formatVideoTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()


    private companion object {
        const val MAX_UPLOAD_PHOTO_BYTES = 1_800_000L
        // Matches the current MajorDoMo media page's 50 MiB file/video limit.
        const val MAX_UPLOAD_VIDEO_BYTES = 50L * 1024L * 1024L
        const val MAX_UPLOAD_PHOTO_DIMENSION = 1920
        const val MIN_UPLOAD_PHOTO_DIMENSION = 640
    }

    /**
     * Return a cache-backed JPEG URI for images that can be decoded. Non-images and
     * any image that cannot be processed are returned unchanged to avoid breaking uploads.
     */
    private fun prepareImageForUpload(uri: Uri): Uri {
        if (!isImageUri(uri)) return uri

        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var working: Bitmap? = null
        try {
            val directory = File(cacheDir, "camera-capture").apply { mkdirs() }
            // Cache is temporary; remove prepared photos older than a day.
            directory.listFiles()?.filter {
                it.name.startsWith("upload_") && System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
            }?.forEach { it.delete() }

            val orientation = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return uri

            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_UPLOAD_PHOTO_DIMENSION)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return uri
            oriented = orientBitmap(decoded!!, orientation)
            if (oriented !== decoded) decoded?.recycle()
            decoded = null
            working = oriented
            oriented = null

            // First cap dimensions, then lower JPEG quality; if still oversized, scale
            // down gradually until the result fits the server's 2 MB limit with margin.
            working = scaleToMaxDimension(working!!, MAX_UPLOAD_PHOTO_DIMENSION)
            var bytes: ByteArray
            var quality: Int
            while (true) {
                quality = 88
                bytes = ByteArrayOutputStream().use { output ->
                    while (true) {
                        output.reset()
                        working!!.compress(Bitmap.CompressFormat.JPEG, quality, output)
                        if (output.size().toLong() <= MAX_UPLOAD_PHOTO_BYTES || quality <= 50) break
                        quality -= 7
                    }
                    output.toByteArray()
                }
                if (bytes.size.toLong() <= MAX_UPLOAD_PHOTO_BYTES) break
                val current = working!!
                val nextWidth = (current.width * 0.85f).toInt().coerceAtLeast(1)
                val nextHeight = (current.height * 0.85f).toInt().coerceAtLeast(1)
                if (current.width <= MIN_UPLOAD_PHOTO_DIMENSION && current.height <= MIN_UPLOAD_PHOTO_DIMENSION) {
                    // Very unusual image; return original rather than produce an oversized result.
                    return uri
                }
                val smaller = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                if (smaller !== current) current.recycle()
                working = smaller
            }

            val outputFile = File.createTempFile("upload_", ".jpg", directory)
            outputFile.outputStream().use { it.write(bytes) }
            return FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
        } catch (error: Exception) {
            addMediaDiagnostic("Фото не удалось сжать, отправляем оригинал: ${error.javaClass.simpleName}")
            return uri
        } finally {
            decoded?.recycle()
            oriented?.recycle()
            working?.recycle()
        }
    }

    private fun isImageUri(uri: Uri): Boolean {
        val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        if (mime?.startsWith("image/", ignoreCase = true) == true) return true
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
        val extension = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return extension in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension && height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        while (maxOf(width / sample, height / sample) > maxDimension * 2) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun orientBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun captureMimeType(acceptTypes: Array<String>): String? {
        val types = acceptTypes.map { it.substringBefore(';').trim().lowercase() }.filter { it.isNotEmpty() }
        return when {
            types.any { it == "image/*" || it.startsWith("image/") } -> "image/jpeg"
            types.any { it == "video/*" || it.startsWith("video/") } -> "video/mp4"
            else -> null
        }
    }

    private fun launchCameraCapture(mimeType: String): Boolean {
        val action = if (mimeType.startsWith("video/")) Intent(MediaStore.ACTION_VIDEO_CAPTURE) else Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return try {
            val directory = File(cacheDir, "camera-capture").apply { mkdirs() }
            val extension = if (mimeType.startsWith("video/")) ".mp4" else ".jpg"
            val outputFile = File.createTempFile("capture_", extension, directory)
            val outputUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outputFile)
            action.putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            action.clipData = android.content.ClipData.newUri(contentResolver, "capture", outputUri)
            if (action.resolveActivity(packageManager) == null) {
                outputFile.delete()
                return false
            }
            pendingCaptureUri = outputUri
            pendingCaptureFile = outputFile
            startActivityForResult(action, cameraCaptureCode)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            clearPendingCapture(deleteFile = true)
            false
        } catch (_: Exception) {
            clearPendingCapture(deleteFile = true)
            false
        }
    }

    private fun launchFileChooser(): Boolean {
        val params = pendingChooserParams
        return try {
            if (params == null) {
                finishFileChooser(null)
                false
            } else {
                startActivityForResult(params.createIntent(), fileChooserCode)
                true
            }
        } catch (_: Exception) {
            finishFileChooser(null)
            false
        }
    }

    private fun finishFileChooser(result: Array<Uri>?) {
        chooser?.onReceiveValue(result)
        chooser = null
        pendingChooserParams = null
        clearPendingCapture(deleteFile = result == null)
    }

    private fun clearPendingCapture(deleteFile: Boolean) {
        if (deleteFile) pendingCaptureFile?.let { try { it.delete() } catch (_: Exception) { } }
        pendingCaptureUri = null
        pendingCaptureFile = null
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
        if (requestCode == qrCameraPermissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showQrScanner()
            } else {
                Toast.makeText(this, "Для сканирования QR-кода нужен доступ к камере", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == permissionRequestCode) {
            val request = pendingRequest
            if (request != null) {
                val granted = pendingResources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }.toTypedArray()
                addMediaDiagnostic("Результат Android: ${permissions.zip(grantResults.toList())}; разрешено WebView=${granted.joinToString()}; CAMERA=${checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED}; RECORD_AUDIO=${checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}")
                if (granted.isNotEmpty()) grantMediaRequest(request, granted) else {
                    try { request.deny() } catch (_: Exception) {}
                }
            }
            pendingRequest = null
            pendingResources = emptyArray()
            pendingAndroidPermissions = emptyList()
            permissionRequestInProgress = false
            processQueuedMediaRequests()
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
    @Suppress("DEPRECATION") // Preserve existing back-navigation behavior on all supported API levels.
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
        clearPendingCapture(deleteFile = true)
        pendingChooserParams = null
        pendingRequest?.deny()
        while (!queuedMediaRequests.isEmpty()) {
            try { queuedMediaRequests.removeFirst().request.deny() } catch (_: Exception) {}
        }
        pendingRequest = null
        pendingResources = emptyArray()
        pendingAndroidPermissions = emptyList()
        permissionRequestInProgress = false
        CookieManager.getInstance().flush()
        if (::site.isInitialized) site.destroy()
        if (::launcher.isInitialized) launcher.destroy()
        super.onDestroy()
    }
}
