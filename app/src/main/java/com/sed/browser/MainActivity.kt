package com.sed.browser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Parcel
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var browserContainer: FrameLayout
    private lateinit var toolbar: View
    private lateinit var addressBar: EditText
    private val tabs = mutableListOf<WebView>()
    private var activeTab = 0
    private val hideHandler = Handler()
    private val sessionPrefs by lazy { getSharedPreferences("sed_browser_session", MODE_PRIVATE) }
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var horizontalGestureHandled = false

    private val hideToolbar = Runnable {
        toolbar.visibility = View.GONE
        addressBar.clearFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        browserContainer = findViewById(R.id.browserContainer)
        toolbar = findViewById(R.id.browserToolbar)
        addressBar = findViewById(R.id.address)
        CookieManager.getInstance().setAcceptCookie(true)

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openAddress()
                true
            } else false
        }

        val restored = restoreSession()
        if (!restored) {
            createTab("https://www.google.com")
            showSplash()
        }
    }

    private fun createTab(url: String, restoreIndex: Int? = null) {
        if (tabs.size >= 8) {
            browserContainer.removeView(tabs.first())
            tabs.first().destroy()
            tabs.removeAt(0)
            if (activeTab > 0) activeTab--
        }

        val webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                saveFormData = true
                allowFileAccess = false
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val requestUrl = request?.url?.toString() ?: return null
                    if (SedAdBlocker.shouldBlock(requestUrl)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    if (this@MainActivity.tabs.getOrNull(activeTab) === view &&
                        pageUrl != null && !addressBar.hasFocus()
                    ) addressBar.setText(pageUrl)
                }
            }
            webChromeClient = WebChromeClient()
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        tabs.add(webView)
        browserContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        activeTab = tabs.lastIndex
        attachGestures(webView)
        showActiveTab()

        val restored = restoreWebViewState(webView, restoreIndex)
        if (!restored) webView.loadUrl(url)
    }

    /**
     * Restores the saved tab list and each WebView's navigation state.
     * WebView can restore history, cookies and cached resources, but Android's
     * WebView does not guarantee that the rendered page bitmap survives process death.
     */
    private fun restoreSession(): Boolean {
        val count = sessionPrefs.getInt("tab_count", 0).coerceIn(0, 8)
        if (count == 0) return false

        for (index in 0 until count) {
            val url = sessionPrefs.getString("tab_url_$index", "https://www.google.com")
                ?: "https://www.google.com"
            createTab(url, index)
        }

        activeTab = sessionPrefs.getInt("active_tab", 0).coerceIn(0, tabs.lastIndex)
        showActiveTab()
        return tabs.isNotEmpty()
    }

    private fun restoreWebViewState(webView: WebView, index: Int?): Boolean {
        if (index == null) return false
        val file = File(filesDir, "sed_tab_state_$index.bin")
        if (!file.exists()) return false

        return try {
            val bytes = file.readBytes()
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = parcel.readBundle(javaClass.classLoader) ?: return false
                webView.restoreState(state) != null
            } finally {
                parcel.recycle()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun persistSession() {
        if (tabs.isEmpty()) return

        val editor = sessionPrefs.edit()
            .putInt("tab_count", tabs.size)
            .putInt("active_tab", activeTab.coerceIn(0, tabs.lastIndex))

        tabs.forEachIndexed { index, webView ->
            val currentUrl = webView.url ?: sessionPrefs.getString(
                "tab_url_$index", "https://www.google.com"
            )
            editor.putString("tab_url_$index", currentUrl)

            try {
                val state = Bundle()
                if (webView.saveState(state) != null) {
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeBundle(state)
                        val bytes = parcel.marshall()
                        val target = File(filesDir, "sed_tab_state_$index.bin")
                        val temporary = File(filesDir, "sed_tab_state_$index.tmp")
                        temporary.writeBytes(bytes)
                        if (!temporary.renameTo(target)) {
                            target.writeBytes(bytes)
                            temporary.delete()
                        }
                    } finally {
                        parcel.recycle()
                    }
                }
            } catch (_: Exception) {
                // The URL remains saved as a fallback if WebView state cannot be serialized.
            }
        }

        editor.apply()
        CookieManager.getInstance().flush()
    }

    private fun attachGestures(webView: WebView) {
        val doubleTapDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    showTabList()
                    return true
                }
            })

        webView.setOnTouchListener { _, event ->
            doubleTapDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        gestureStartX = (event.getX(0) + event.getX(1)) / 2f
                        gestureStartY = (event.getY(0) + event.getY(1)) / 2f
                        horizontalGestureHandled = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && !horizontalGestureHandled) {
                        val x = (event.getX(0) + event.getX(1)) / 2f
                        val y = (event.getY(0) + event.getY(1)) / 2f
                        val dx = x - gestureStartX
                        val dy = y - gestureStartY
                        if (abs(dx) > 120f && abs(dx) > abs(dy) * 1.25f) {
                            horizontalGestureHandled = true
                            if (dx < 0) {
                                if (activeTab < tabs.lastIndex) {
                                    activeTab++
                                    showActiveTab()
                                } else if (tabs.size < 8) {
                                    createTab("https://www.google.com")
                                } else {
                                    activeTab = 0
                                    showActiveTab()
                                }
                            } else if (activeTab > 0) {
                                activeTab--
                                showActiveTab()
                            }
                        } else if (abs(dy) > 100f && abs(dy) > abs(dx) * 1.25f) {
                            if (dy > 0) showToolbar()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    horizontalGestureHandled = false
                }
            }
            false
        }
    }

    private fun showTabList() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, webView ->
            val title = webView.title?.takeIf { it.isNotBlank() } ?: "New tab"
            val host = webView.url?.removePrefix("https://")?.removePrefix("http://") ?: ""
            (if (index == activeTab) "✓ " else "") + title + if (host.isNotBlank()) "\n$host" else ""
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Open tabs  •  ${tabs.size}/8")
            .setItems(labels) { dialog, which ->
                activeTab = which
                showActiveTab()
                dialog.dismiss()
            }
            .setPositiveButton("New tab") { _, _ ->
                if (tabs.size < 8) createTab("https://www.google.com")
                else showTabLimitMessage()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTabLimitMessage() {
        AlertDialog.Builder(this)
            .setMessage("Maximum 8 tabs are open. Close a tab before creating another.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showActiveTab() {
        tabs.forEachIndexed { index, webView ->
            webView.visibility = if (index == activeTab) View.VISIBLE else View.GONE
        }
        addressBar.setText(tabs.getOrNull(activeTab)?.url ?: "")
        if (tabs.isNotEmpty()) {
            sessionPrefs.edit().putInt("active_tab", activeTab).apply()
        }
    }

    private fun showToolbar() {
        hideHandler.removeCallbacks(hideToolbar)
        toolbar.visibility = View.VISIBLE
        addressBar.setText(tabs.getOrNull(activeTab)?.url ?: "")
        hideHandler.postDelayed(hideToolbar, 5000)
    }

    private fun openAddress() {
        val text = addressBar.text.toString().trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("https://") || text.startsWith("http://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        }
        tabs.getOrNull(activeTab)?.loadUrl(target)
        hideHandler.postDelayed(hideToolbar, 300)
    }

    private fun showSplash() {
        val splash = TextView(this).apply {
            text = "SED"
            textSize = 54f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(9, 14, 24))
            alpha = 1f
            scaleX = 0.72f
            scaleY = 0.72f
        }
        addContentView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        splash.animate().scaleX(1.28f).scaleY(1.28f).alpha(0f)
            .setDuration(1250).withEndAction {
                (splash.parent as? ViewGroup)?.removeView(splash)
            }.start()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        val current = tabs.getOrNull(activeTab)
        if (current?.canGoBack() == true) current.goBack()
        else if (activeTab > 0) {
            activeTab--
            showActiveTab()
        } else super.onBackPressed()
    }

    override fun onPause() {
        persistSession()
        super.onPause()
    }

    override fun onStop() {
        persistSession()
        super.onStop()
    }

    override fun onDestroy() {
        persistSession()
        hideHandler.removeCallbacksAndMessages(null)
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
