package app.tingdian.listen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale

/**
 * 听点：一个全屏 WebView 外壳。
 * 界面和逻辑都在 assets/index.html，这里只补上网页在安卓里做不到的事：
 * 选择文件、系统 TTS 朗读、音量键标记、保持亮屏、震动、返回键、从其他 App 分享音频进来。
 */
class MainActivity : Activity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/index.html"
        private const val REQ_FILE = 1001
    }

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var volumeMark = false
    @Volatile private var pageReady = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingShare: Pair<String, String>? = null
    private val sharedTypes = mutableMapOf<String, String>()
    private val net = Executors.newFixedThreadPool(3)
    private val dlTypes = mutableMapOf<String, String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            // 把 assets 里的网页挂在一个 https 地址下，IndexedDB 和网络请求才能正常工作
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url
                if (url.host != HOST) return null
                val path = url.path ?: "/"
                if (path.startsWith("/__shared/")) {
                    val id = path.removePrefix("/__shared/")
                    val f = File(File(cacheDir, "shared"), id)
                    if (!f.exists()) return null
                    return WebResourceResponse(sharedTypes[id] ?: "audio/mpeg", null, FileInputStream(f))
                }
                if (path.startsWith("/__dl/")) {
                    val id = path.removePrefix("/__dl/")
                    val f = File(File(cacheDir, "dl"), id)
                    if (!f.exists()) return null
                    return WebResourceResponse(dlTypes[id] ?: "audio/mpeg", null, FileInputStream(f))
                }
                val name = path.trimStart('/').ifEmpty { "index.html" }
                return try {
                    val mime = when {
                        name.endsWith(".html") -> "text/html"
                        name.endsWith(".js") -> "application/javascript"
                        name.endsWith(".css") -> "text/css"
                        name.endsWith(".svg") -> "image/svg+xml"
                        name.endsWith(".png") -> "image/png"
                        else -> "application/octet-stream"
                    }
                    WebResourceResponse(mime, "utf-8", assets.open(name))
                } catch (e: Exception) { null }
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == HOST) return false
                // 外部链接交给浏览器
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                deliverShare()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/mp4", "video/webm"))
                    }
                    startActivityForResult(Intent.createChooser(intent, "选择英语音频"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = notifyDone(id)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = notifyDone(id)
                override fun onError(id: String?, errorCode: Int) = notifyDone(id)
                override fun onStop(id: String?, interrupted: Boolean) = notifyDone(id)
            })
            if (ttsReady) runOnUiThread { web.evaluateJavascript("window.onTtsReady && onTtsReady()", null) }
        }

        handleIntent(intent)
        web.loadUrl(START_URL)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        deliverShare()
    }

    /** 从其他 App“分享”或“打开方式”传进来的音频：复制到缓存，再交给网页导入 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri ?: return
        try {
            var name = "shared-audio"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val id = System.currentTimeMillis().toString()
            contentResolver.openInputStream(uri)?.use { input ->
                File(dir, id).outputStream().use { input.copyTo(it) }
            }
            sharedTypes[id] = contentResolver.getType(uri) ?: "audio/mpeg"
            pendingShare = "https://$HOST/__shared/$id" to name
        } catch (_: Exception) {}
        intent.action = null
    }

    /** 打开连接并手动跟随跳转（包括 http→https 的跳转） */
    private fun openFollowing(start: String): HttpURLConnection {
        var url = start
        repeat(8) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "TingDian/1.0 (Android podcast reader)")
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("跳转地址为空")
                url = URL(URL(url), loc).toString()
                c.disconnect()
            } else {
                if (code >= 400) { c.disconnect(); throw Exception("HTTP $code") }
                return c
            }
        }
        throw Exception("跳转次数太多")
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    /** 一段朗读结束，通知网页（用来在意群和整句之间停顿） */
    private fun notifyDone(id: String?) {
        id ?: return
        runOnUiThread { web.evaluateJavascript("window.onTtsDone && onTtsDone(${JSONObject.quote(id)})", null) }
    }

    private fun deliverShare() {
        val share = pendingShare ?: return
        if (!pageReady) return
        pendingShare = null
        val js = "window.onSharedAudio && onSharedAudio(${JSONObject.quote(share.first)}, ${JSONObject.quote(share.second)})"
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    /** 盲听播放时，音量键 = “听不懂” */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (volumeMark && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                web.evaluateJavascript("window.onVolumeKey && onVolumeKey()", null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? onAndroidBack() : false") { result ->
            if (result != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        tts?.stop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            pageReady = true
            deliverShare()
        }

        /** 系统里已安装的英文和中文声音 */
        @JavascriptInterface
        fun getVoices(): String {
            val arr = JSONArray()
            val engine = tts ?: return "[]"
            if (!ttsReady) return "[]"
            try {
                engine.voices?.filter { v ->
                    val lang = v.locale.language
                    (lang == "en" || lang == "zh") && !v.features.contains("notInstalled")
                }?.sortedBy { it.name }?.forEach { v ->
                    arr.put(JSONObject().apply {
                        put("name", v.name)
                        put("lang", v.locale.toLanguageTag())
                    })
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface
        fun speak(text: String, lang: String, rate: Float, voiceName: String, id: String) {
            val engine = tts
            if (engine == null || !ttsReady) { notifyDone(id); return }
            runOnUiThread {
                try {
                    val voice = if (voiceName.isNotEmpty()) engine.voices?.firstOrNull { it.name == voiceName } else null
                    if (voice != null) engine.voice = voice
                    else engine.language = Locale.forLanguageTag(lang)
                    engine.setSpeechRate(rate)
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                } catch (_: Exception) { notifyDone(id) }
            }
        }

        /** 抓取订阅源（网页直接请求会被跨域拦截，交给原生来做） */
        @JavascriptInterface
        fun fetchText(url: String, cb: String) {
            net.execute {
                try {
                    val c = openFollowing(url)
                    val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    c.disconnect()
                    js("window.onNativeFetch && onNativeFetch(${JSONObject.quote(cb)}, true, ${JSONObject.quote(text)})")
                } catch (e: Exception) {
                    js("window.onNativeFetch && onNativeFetch(${JSONObject.quote(cb)}, false, ${JSONObject.quote(e.message ?: e.toString())})")
                }
            }
        }

        /** 下载一期节目的音频到缓存，下载完挂到 https://HOST/__dl/<id>，网页再读成文件 */
        @JavascriptInterface
        fun download(url: String, cb: String) {
            net.execute {
                try {
                    val dir = File(cacheDir, "dl").apply { mkdirs() }
                    // 只保留最近几个缓存文件
                    dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(3)?.forEach { it.delete() }
                    val id = System.currentTimeMillis().toString()
                    val out = File(dir, id)
                    val c = openFollowing(url)
                    val total = c.contentLengthLong
                    dlTypes[id] = c.contentType?.substringBefore(';')?.trim() ?: "audio/mpeg"
                    var done = 0L
                    var lastReport = 0L
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 300) {
                                    lastReport = now
                                    js("window.onNativeProgress && onNativeProgress(${JSONObject.quote(cb)}, $done, $total)")
                                }
                            }
                        }
                    }
                    c.disconnect()
                    js("window.onNativeDownload && onNativeDownload(${JSONObject.quote(cb)}, true, ${JSONObject.quote("https://$HOST/__dl/$id")}, ${JSONObject.quote(dlTypes[id] ?: "")})")
                } catch (e: Exception) {
                    js("window.onNativeDownload && onNativeDownload(${JSONObject.quote(cb)}, false, ${JSONObject.quote(e.message ?: e.toString())}, \"\")")
                }
            }
        }

        @JavascriptInterface
        fun stopSpeak() { tts?.stop() }

        @JavascriptInterface
        fun setKeepScreenOn(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun setVolumeKeyMark(on: Boolean) { volumeMark = on }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                val v = getSystemService(Vibrator::class.java) ?: return
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}
        }
    }
}
