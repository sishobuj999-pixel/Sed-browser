package com.sed.browser

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.animation.DecelerateInterpolator
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

    private fun createTab(url: String, restoreIndex: Int? = null, activate: Boolean = true): WebView {
        if (tabs.size >= 8) {
            browserContainer.removeView(tabs.first())
            tabs.first().destroy()
            tabs.removeAt(0)
            if (activeTab > 0) activeTab--
        }

        val webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
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
                    if (view != null) {
                        applyTextStatus(view)
                        view.postDelayed({ captureTabPreview(view) }, 220)
                    }
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
        if (activate) activeTab = tabs.lastIndex
        webView.visibility = if (tabs.lastIndex == activeTab) View.VISIBLE else View.GONE
        attachGestures(webView)

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
            createTab(url, index, activate = false)
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

        tabs.getOrNull(activeTab)?.let { captureTabPreview(it) }
        editor.apply()
        CookieManager.getInstance().flush()
    }

    private fun previewFile(index: Int): File = File(filesDir, "sed_tab_preview_$index.png")

    private fun captureTabPreview(webView: WebView) {
        val index = tabs.indexOf(webView)
        if (index < 0 || webView.width <= 0 || webView.height <= 0) return
        try {
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))
            previewFile(index).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
            bitmap.recycle()
        } catch (_: Exception) {
            // Keep the last valid preview if capture is temporarily unavailable.
        }
    }

    private fun loadTabPreview(webView: WebView, index: Int): Bitmap? {
        val file = previewFile(index)
        if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            } catch (_: Exception) { }
        }
        if (webView.width > 0 && webView.height > 0) {
            return try {
                Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also {
                    webView.draw(Canvas(it))
                }
            } catch (_: Exception) { null }
        }
        return null
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
        if (tabs.isEmpty() || browserContainer.childCount == 0) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(112, 0, 0, 0))
            alpha = 0f
        }
        val cardViews = mutableListOf<FrameLayout>()
        val previewBitmaps = mutableListOf<Bitmap>()
        var selected = activeTab.coerceIn(0, tabs.lastIndex)
        var downX = 0f
        var downY = 0f
        var gestureHandled = false
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val cardWidth = (screenWidth * 0.84f).toInt()
        val cardHeight = (cardWidth.toFloat() * screenHeight / screenWidth).toInt()
        val spacing = cardWidth * 0.76f
        val interpolator = DecelerateInterpolator(1.7f)

        tabs.forEachIndexed { index, webView ->
            if (index == activeTab) captureTabPreview(webView)
            val bitmap = loadTabPreview(webView, index) ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
            }
            previewBitmaps.add(bitmap)
        }

        fun layoutCards(animate: Boolean) {
            cardViews.forEachIndexed { index, card ->
                val distance = index - selected
                val targetX = distance * spacing
                val targetScale = if (distance == 0) 1f else 0.88f
                val targetAlpha = if (abs(distance) > 1) 0f else if (distance == 0) 1f else 0.72f
                card.visibility = if (abs(distance) > 1) View.INVISIBLE else View.VISIBLE
                if (animate) {
                    card.animate().translationX(targetX).scaleX(targetScale).scaleY(targetScale)
                        .alpha(targetAlpha).setDuration(260).setInterpolator(interpolator).start()
                } else {
                    card.translationX = targetX
                    card.scaleX = targetScale
                    card.scaleY = targetScale
                    card.alpha = targetAlpha
                }
                card.elevation = dp(if (distance == 0) 18 else 8).toFloat()
            }
        }

        fun finishAndOpen(index: Int) {
            if (index !in tabs.indices) return
            selected = index
            val chosen = cardViews.getOrNull(index)
            chosen?.animate()?.translationY(dp(90).toFloat())?.scaleX(0.94f)?.scaleY(0.94f)
                ?.setDuration(190)?.setInterpolator(interpolator)?.start()
            overlay.animate().alpha(0f).setDuration(190).setInterpolator(interpolator)
                .withEndAction {
                    activeTab = index
                    showActiveTab()
                    browserContainer.removeView(overlay)
                    previewBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                }.start()
        }

        fun closeSelected() {
            if (selected !in tabs.indices) return
            val closingIndex = selected
            val closingCard = cardViews.getOrNull(closingIndex)
            closingCard?.animate()?.translationY(-screenHeight.toFloat())?.scaleX(0.82f)?.scaleY(0.82f)
                ?.alpha(0f)?.setDuration(230)?.setInterpolator(interpolator)?.withEndAction {
                    removeTabPermanently(closingIndex)
                    previewBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                    if (tabs.isEmpty()) {
                        browserContainer.removeView(overlay)
                        createTab("https://www.google.com")
                    } else {
                        // Recreate the visual stack from the surviving live WebViews.
                        browserContainer.removeView(overlay)
                        showTabList()
                    }
                }?.start()
        }

        previewBitmaps.forEachIndexed { index, bitmap ->
            val card = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(14, 18, 27))
                    cornerRadius = dp(24).toFloat()
                }
                clipToOutline = true
                elevation = dp(10).toFloat()
                alpha = 0f
            }
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
                contentDescription = null
            }
            card.addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            val params = FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER)
            overlay.addView(card, params)
            cardViews.add(card)
        }

        overlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    gestureHandled = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > dp(55) && abs(dx) > abs(dy) * 1.15f) {
                        selected = (selected + if (dx < 0) 1 else -1).coerceIn(0, tabs.lastIndex)
                        layoutCards(true)
                    } else if (dy < -dp(65) && abs(dy) > abs(dx)) {
                        closeSelected()
                    } else if (dy > dp(65) && abs(dy) > abs(dx)) {
                        finishAndOpen(selected)
                    } else if (abs(dx) < dp(18) && abs(dy) < dp(18)) {
                        finishAndOpen(selected)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        // Place the overlay above the live page, leaving the current tab visible underneath.
        browserContainer.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layoutCards(false)
        overlay.animate().alpha(1f).setDuration(220).setInterpolator(interpolator).start()
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

        // Preserve previews for surviving tabs while compacting their indices.
        val survivingPreviews = (0 until tabs.size).map { i ->
            try { previewFile(if (i < index) i else i + 1).takeIf { it.exists() }?.readBytes() }
            catch (_: Exception) { null }
        }
        for (i in 0..8) previewFile(i).delete()
        survivingPreviews.forEachIndexed { i, bytes ->
            if (bytes != null) try { previewFile(i).writeBytes(bytes) } catch (_: Exception) { }
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
            .setDuration(420).withEndAction {
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
