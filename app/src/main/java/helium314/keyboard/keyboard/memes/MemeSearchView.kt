// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.memes

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fork feature: Google Images inside the keyboard.
 *
 * Flow: type a query in the chat field -> press the meme toolbar key -> the typed line is removed
 * and used as the search -> long-press any image -> it's downloaded and inserted with commitContent.
 *
 * The WebView only exists while the panel is open, and is destroyed on close to free RAM.
 * It is not focusable, so Google's own search box can't grab the keyboard (an IME can't type into itself).
 * Instead, tapping the header or Google's search box asks KeyboardSwitcher for "typing mode":
 * this panel hides (WebView kept alive), the keys come back with a [MemeQueryBar] above them,
 * and Enter calls [search] with the new query.
 */
class MemeSearchView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private var webView: WebView? = null
    private val label: TextView
    private val backButton: ImageButton
    private val closeButton: ImageButton
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: KeyboardActionListener? = null
    private var editorInfo: EditorInfo? = null
    private var query = ""
    @Volatile private var busy = false

    /** set by KeyboardSwitcher, called when the panel wants to be closed */
    var onCloseRequested: Runnable? = null

    /** set by KeyboardSwitcher, called with the current query when the user wants to type a new search */
    var onTypeRequested: androidx.core.util.Consumer<String>? = null

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            setImageResource(R.drawable.ic_arrow_left)
            contentDescription = "Back"
            setOnClickListener { webView?.let { if (it.canGoBack()) it.goBack() } }
        }
        closeButton = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close"
            setOnClickListener { onCloseRequested?.run() }
        }
        label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onTypeRequested?.accept(this@MemeSearchView.query) }
        }
        header.addView(backButton, LayoutParams(dp(HEADER_DP), dp(HEADER_DP)))
        header.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(closeButton, LayoutParams(dp(HEADER_DP), dp(HEADER_DP)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_DP)))
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        this.listener = listener
    }

    /** @param heightPx height of the keyboard this panel replaces */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(query: String, heightPx: Int, editorInfo: EditorInfo?) {
        stop()
        this.query = query.ifBlank { DEFAULT_QUERY }
        this.editorInfo = editorInfo
        applyColors()
        setLabel(null)

        val wv = try {
            WebView(context)
        } catch (e: Throwable) { // e.g. WebView missing/updating, or device still locked (direct boot)
            Log.e(TAG, "could not create WebView", e)
            toast("Meme search unavailable: WebView failed to start")
            post { onCloseRequested?.run() }
            return
        }
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.isLongClickable = true
        wv.settings.apply {
            javaScriptEnabled = true // Google Images needs JS
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                handleUrl(request.url)

            @Deprecated("needed for API < 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = handleUrl(Uri.parse(url))

            override fun onPageFinished(view: WebView, url: String?) {
                // route taps on Google's own search box to our typing mode
                view.evaluateJavascript(HOOK_SEARCH_BOX_JS, null)
            }
        }
        wv.setOnLongClickListener { handleLongPress(wv) }

        val webHeight = (heightPx - dp(HEADER_DP)).coerceAtLeast(dp(MIN_WEB_DP))
        addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, webHeight))
        webView = wv
        wv.loadUrl(SEARCH_URL + URLEncoder.encode(this.query, "UTF-8"))
    }

    /** load a new search in the existing WebView */
    fun search(newQuery: String) {
        if (newQuery.isBlank()) return
        query = newQuery
        setLabel(null)
        webView?.loadUrl(SEARCH_URL + URLEncoder.encode(query, "UTF-8"))
    }

    /** @return true if the WebView should NOT load this url */
    private fun handleUrl(uri: Uri): Boolean {
        if (uri.scheme == TYPE_SCHEME) {
            val prefill = uri.getQueryParameter("q")?.takeIf { it.isNotBlank() } ?: query
            post { onTypeRequested?.accept(prefill) }
            return true
        }
        return !isWebUrl(uri)
    }

    fun stop() {
        busy = false
        webView?.let {
            removeView(it)
            try {
                it.stopLoading()
                it.webViewClient = WebViewClient()
                it.destroy()
            } catch (e: Throwable) {
                Log.w(TAG, "error destroying WebView", e)
            }
        }
        webView = null
        editorInfo = null
    }

    private fun handleLongPress(wv: WebView): Boolean {
        val result = wv.hitTestResult
        return when (result.type) {
            WebView.HitTestResult.IMAGE_TYPE -> {
                result.extra?.let { fetchAndSend(it) }
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // extra is the link target here, ask for the actual <img> src instead
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val src = msg.data.getString("src") ?: msg.data.getString("url") ?: result.extra
                    src?.let { fetchAndSend(it) }
                    true
                }
                wv.requestImageRef(handler.obtainMessage())
                true
            }
            else -> false
        }
    }

    private fun fetchAndSend(url: String) {
        if (busy) return
        busy = true
        setLabel("Grabbing image…")
        val appContext = context.applicationContext
        Thread {
            try {
                val (bytes, headerMime) = if (url.startsWith("data:")) decodeDataUrl(url) else download(url)
                val mime = sniffMime(bytes) ?: headerMime?.takeIf { it.startsWith("image/") }
                    ?: throw IllegalStateException("not an image")
                val file = saveFile(appContext, bytes, mime)
                val uri = FileProvider.getUriForFile(appContext, appContext.getString(R.string.clipboard_provider_authority), file)
                mainHandler.post { send(uri, mime) }
            } catch (e: Throwable) {
                Log.w(TAG, "failed to get image from ${url.take(100)}", e)
                mainHandler.post {
                    busy = false
                    setLabel(null)
                    toast("Couldn't grab that one. Open it bigger and try again")
                }
            }
        }.start()
    }

    private fun send(uri: Uri, mime: String) {
        busy = false
        val l = listener ?: return
        val accepted = editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: emptyArray()
        val content = InputContentInfoCompat(uri, ClipDescription("meme", arrayOf(mime)), null)
        if (accepted.isNotEmpty() && accepted.none { ClipDescription.compareMimeTypes(mime, it) }) {
            setLabel(null)
            toast("This app doesn't accept $mime")
            return
        }
        // if the app declares no types, HeliBoard falls back to pasting via clipboard
        l.onContent(content)
        onCloseRequested?.run()
    }

    private fun setLabel(status: String?) {
        label.text = status ?: "🔍 $query  ✎  ·  tap to search · hold an image to send"
    }

    private fun applyColors() {
        val colors = Settings.getValues()?.mColors ?: return
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)
        label.setTextColor(colors.get(ColorType.KEY_TEXT))
        label.typeface = Typeface.DEFAULT
        colors.setColor(backButton, ColorType.TOOL_BAR_KEY)
        colors.setColor(closeButton, ColorType.TOOL_BAR_KEY)
    }

    private fun toast(text: String) = KeyboardSwitcher.getInstance().showToast(text, true)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MemeSearchView"
        private const val HEADER_DP = 40
        private const val MIN_WEB_DP = 180
        private const val DEFAULT_QUERY = "meme"
        private const val MAX_BYTES = 20 * 1024 * 1024
        private const val KEEP_FILES = 10
        // udm=2 is Google's "Images" tab
        private const val SEARCH_URL = "https://www.google.com/search?udm=2&q="
        private const val TYPE_SCHEME = "memesearch"
        private const val HOOK_SEARCH_BOX_JS = """(function(){
            if (window.__memeHooked) return; window.__memeHooked = true;
            var handler = function(e){
                var t = e.target && e.target.closest && e.target.closest('input[name=q],textarea[name=q]');
                if (!t) return;
                e.preventDefault(); e.stopPropagation();
                location.href = 'memesearch://type?q=' + encodeURIComponent(t.value || '');
            };
            document.addEventListener('click', handler, true);
            document.addEventListener('touchend', handler, true);
        })();"""

        private fun isWebUrl(uri: Uri) = uri.scheme == "https" || uri.scheme == "http"

        private fun decodeDataUrl(url: String): Pair<ByteArray, String?> {
            val comma = url.indexOf(',')
            val header = url.substring(5, comma) // after "data:"
            val mime = header.substringBefore(';').ifBlank { null }
            val data = url.substring(comma + 1)
            val bytes = if (header.endsWith(";base64")) Base64.decode(data, Base64.DEFAULT)
                else Uri.decode(data).toByteArray()
            return bytes to mime
        }

        private fun download(url: String): Pair<ByteArray, String?> {
            var current = url
            repeat(5) { // follow redirects manually, HttpURLConnection won't switch http <-> https
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                    val code = conn.responseCode
                    if (code in 300..399) {
                        current = URL(URL(current), conn.getHeaderField("Location")).toString()
                        return@repeat
                    }
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) throw IllegalStateException("image too big")
                        }
                    }
                    return out.toByteArray() to conn.contentType?.substringBefore(';')?.trim()
                } finally {
                    conn.disconnect()
                }
            }
            throw IllegalStateException("too many redirects")
        }

        private fun sniffMime(b: ByteArray): String? = when {
            b.size < 12 -> null
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

        private fun saveFile(context: Context, bytes: ByteArray, mime: String): File {
            val dir = File(context.filesDir, "memes").apply { mkdirs() }
            // keep only the last few memes around
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES - 1)?.forEach { it.delete() }
            val ext = when (mime) {
                "image/gif" -> "gif"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            return File(dir, "meme_${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }
        }

        // a plain mobile Chrome UA, some image hosts reject Java's default one
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"
    }
}

