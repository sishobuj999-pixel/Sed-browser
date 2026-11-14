package com.sed.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isOnline = false

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
        isOnline = hasValidatedInternet()
        registerNetworkMonitor()

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openAddress()
                true
            } else false
        }

        val restored = restoreSession()
        if (!restored) {
            val tab = createTab("https://www.google.com")
            if (!isOnline) { /* keep the normal home/session view; only change appearance */ }
        }
        updateNetworkAppearance()
        showSplash()
    }

    private fun createTab(url: String, restoreIndex: Int? = null): WebView {
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
                cacheMode = if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
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
                    if (view != null) applyTextStatus(view)
                    if (this@MainActivity.tabs.getOrNull(activeTab) === view &&
                        pageUrl != null && !addressBar.hasFocus()
                    ) addressBar.setText(pageUrl)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    // Never replace the currently visible page merely because connectivity changed.
                    // Offline navigation is handled before a new URL is loaded.
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
        if (!restored) {
            webView.loadUrl(url)
        }
        return webView
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
            val currentUrl = if (webView.url?.startsWith("https://sed-browser.local/") == true) {
                sessionPrefs.getString("offline_last_url_$index", "https://www.google.com")
            } else webView.url ?: sessionPrefs.getString("tab_url_$index", "https://www.google.com")
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

    private fun hasValidatedInternet(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkMonitor() {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun sync() = runOnUiThread {
                val online = hasValidatedInternet()
                if (online != isOnline) {
                    isOnline = online
                    updateNetworkAppearance()
                }
            }
            override fun onAvailable(network: Network) = sync()
            override fun onLost(network: Network) = sync()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = sync()
        }
        networkCallback = callback
        try { manager.registerDefaultNetworkCallback(callback) } catch (_: Exception) { networkCallback = null }
    }

    private fun updateNetworkAppearance() {
        addressBar.setTextColor(if (isOnline) Color.rgb(32, 33, 36) else Color.rgb(235, 35, 45))
        addressBar.setHintTextColor(if (isOnline) Color.rgb(102, 112, 133) else Color.rgb(235, 35, 45))
        tabs.forEach { applyTextStatus(it) }
    }

    private fun applyTextStatus(webView: WebView) {
        val js = if (isOnline) "(function(){var s=document.getElementById('sed-offline-text-style');if(s)s.remove();})();" else "(function(){var s=document.getElementById('sed-offline-text-style');if(s)s.remove();s=document.createElement('style');s.id='sed-offline-text-style';s.textContent='*{color:#eb232d!important;-webkit-text-fill-color:#eb232d!important}';(document.head||document.documentElement).appendChild(s);})();"
        webView.evaluateJavascript(js, null)
    }

    private fun showOfflineGame(webView: WebView, lastUrl: String) {
        val tabIndex = tabs.indexOf(webView)
        if (tabIndex >= 0) sessionPrefs.edit().putString("offline_last_url_$tabIndex", lastUrl).apply()
        val safeUrl = lastUrl.replace("&", "&amp;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;")
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
            <meta charset="utf-8"><style>
            *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#080d18;color:#e8edf7;font-family:Arial,sans-serif;display:flex;align-items:center;justify-content:center;padding:20px;text-align:center}
            main{width:100%;max-width:420px}h1{letter-spacing:6px;margin:0 0 8px;font-size:40px}.status{color:#eb232d;font-weight:bold;margin-bottom:12px}
            p{opacity:.75;margin:0 0 18px;font-size:14px}#game{position:relative;width:100%;height:360px;background:#101827;border:1px solid #eb232d;border-radius:20px;overflow:hidden;touch-action:none}
            .coin{position:absolute;width:28px;height:28px;border-radius:50%;background:#ffd54a;box-shadow:0 0 16px #ffd54a}.player{position:absolute;width:42px;height:42px;border-radius:50%;background:#eb232d;box-shadow:0 0 16px #eb232d}
            .score{margin:14px 0;font-size:20px;color:#eb232d}button{border:1px solid #eb232d;border-radius:24px;background:transparent;color:#eb232d;padding:11px 22px;font-size:15px}.tip{font-size:12px;margin-top:12px;opacity:.7}
            </style></head><body><main><h1>SED</h1><div class="status">OFFLINE MODE</div><p>No internet. Play while you wait.</p>
            <div id="game"><div class="player" id="p"></div><div class="coin" id="c"></div></div><div class="score">Target: <b>10</b> coins in <span id="t">30</span>s • Score: <span id="s">0</span></div>
            <button onclick="location.href='$safeUrl'">Retry connection</button><div class="tip">Drag the red circle to collect coins</div>
            <script>
            const g=document.getElementById('game'),p=document.getElementById('p'),c=document.getElementById('c'),s=document.getElementById('s');
            const target=10,seconds=30;let left=seconds,x=150,y=150,score=0,drag=false,ox=0,oy=0;
            function pos(){p.style.left=x+'px';p.style.top=y+'px'}function coin(){c.style.left=Math.random()*(g.clientWidth-30)+'px';c.style.top=Math.random()*(g.clientHeight-30)+'px'}
            function move(e){if(!drag)return;let r=g.getBoundingClientRect(),t=e.touches?e.touches[0]:e;x=Math.max(0,Math.min(g.clientWidth-42,t.clientX-r.left-ox));y=Math.max(0,Math.min(g.clientHeight-42,t.clientY-r.top-oy));pos();let a=p.getBoundingClientRect(),b=c.getBoundingClientRect();if(Math.hypot((a.left+a.width/2)-(b.left+b.width/2),(a.top+a.height/2)-(b.top+b.height/2))<35){score++;s.textContent=score;coin();}}
            function down(e){drag=true;let t=e.touches?e.touches[0]:e,r=p.getBoundingClientRect();ox=t.clientX-r.left;oy=t.clientY-r.top}
            const timer=setInterval(()=>{left--;document.getElementById('t').textContent=left;if(left<=0){clearInterval(timer);drag=false;document.querySelector('.status').textContent=score>=target?'TARGET COMPLETE!':'TIME UP — TRY AGAIN';}},1000);
            p.addEventListener('mousedown',down);p.addEventListener('touchstart',down);window.addEventListener('mousemove',move);window.addEventListener('touchmove',move,{passive:false});window.addEventListener('mouseup',()=>drag=false);window.addEventListener('touchend',()=>drag=false);pos();coin();
            </script></main></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://sed-browser.local/", html, "text/html", "UTF-8", null)
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

        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(18))
            setBackgroundColor(Color.rgb(8, 13, 24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "TABS  •  ${tabs.size}/8"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1f))
        val add = TextView(this).apply {
            text = "+  NEW"
            textSize = 14f
            setTextColor(Color.rgb(120, 205, 255))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                if (tabs.size < 8) {
                    dialog.dismiss()
                    createTab("https://www.google.com")
                } else showTabLimitMessage()
            }
        }
        header.addView(add)
        root.addView(header)

        val hint = TextView(this).apply {
            text = "Swipe down to open  •  Swipe up to close permanently"
            textSize = 12f
            setTextColor(Color.rgb(155, 166, 185))
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(hint)

        val scroll = ScrollView(this)
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(stack)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        fun rebuildCards() {
            stack.removeAllViews()
            tabs.forEachIndexed { index, webView ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    setBackgroundColor(if (index == activeTab) Color.rgb(35, 52, 78) else Color.rgb(23, 32, 48))
                    elevation = dp(3).toFloat()
                }
                val top = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val title = TextView(this).apply {
                    val pageTitle = webView.title?.takeIf { it.isNotBlank() } ?: "New tab"
                    text = (if (index == activeTab) "●  " else "") + pageTitle
                    textSize = 17f
                    maxLines = 2
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }
                top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val close = TextView(this).apply {
                    text = "×"
                    textSize = 28f
                    setTextColor(Color.rgb(255, 105, 115))
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(4), 0)
                    setOnClickListener {
                        removeTabPermanently(index)
                        if (tabs.isEmpty()) {
                            dialog.dismiss()
                            createTab("https://www.google.com")
                        } else rebuildCards()
                    }
                }
                top.addView(close)
                card.addView(top)

                val pageUrl = TextView(this).apply {
                    text = webView.url ?: "No address"
                    textSize = 12f
                    maxLines = 2
                    setTextColor(Color.rgb(170, 185, 205))
                    setPadding(0, dp(8), 0, 0)
                }
                card.addView(pageUrl)
                val gestureHint = TextView(this).apply {
                    text = "↑ close tab     ↓ open tab"
                    textSize = 11f
                    setTextColor(Color.rgb(120, 205, 255))
                    setPadding(0, dp(12), 0, 0)
                }
                card.addView(gestureHint)

                var downY = 0f
                card.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val delta = event.rawY - downY
                            if (delta < -dp(55)) {
                                removeTabPermanently(index)
                                if (tabs.isEmpty()) {
                                    dialog.dismiss()
                                    createTab("https://www.google.com")
                                } else rebuildCards()
                            } else if (delta > dp(55)) {
                                activeTab = tabs.indexOf(webView).coerceAtLeast(0)
                                showActiveTab()
                                dialog.dismiss()
                            } else {
                                activeTab = tabs.indexOf(webView).coerceAtLeast(0)
                                showActiveTab()
                                dialog.dismiss()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> true
                        else -> true
                    }
                }

                val cardParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(142)
                )
                cardParams.bottomMargin = dp(14)
                stack.addView(card, cardParams)
            }
        }
        rebuildCards()

        val footer = TextView(this).apply {
            text = "Tap a card to open  •  × to close"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(155, 166, 185))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(footer)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun removeTabPermanently(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        browserContainer.removeView(removed)
        removed.stopLoading()
        removed.destroy()

        if (tabs.isEmpty()) {
            activeTab = 0
        } else {
            activeTab = when {
                index < activeTab -> activeTab - 1
                index == activeTab -> index.coerceAtMost(tabs.lastIndex)
                else -> activeTab
            }.coerceIn(0, tabs.lastIndex)
        }

        // Rebuild indexed state files so a closed tab cannot return after app restart.
        for (i in 0..8) {
            File(filesDir, "sed_tab_state_$i.bin").delete()
            File(filesDir, "sed_tab_state_$i.tmp").delete()
            sessionPrefs.edit().remove("offline_last_url_$i").apply()
        }
        persistSession()
        showActiveTab()
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
        updateNetworkAppearance()
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
        val current = tabs.getOrNull(activeTab)
        if (current != null) { if (isOnline) current.loadUrl(target) else showOfflineGame(current, target) }
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
        if (current?.url?.startsWith("https://sed-browser.local/") == true && current.canGoBack()) {
            current.goBack()
            Handler().postDelayed({ applyTextStatus(current) }, 100)
        } else if (current?.canGoBack() == true) current.goBack()
        else if (activeTab > 0) {
            activeTab--
            showActiveTab()
        } else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        val online = hasValidatedInternet()
        if (online != isOnline) { isOnline = online; updateNetworkAppearance() }
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
        networkCallback?.let { try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        networkCallback = null
        hideHandler.removeCallbacksAndMessages(null)
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
