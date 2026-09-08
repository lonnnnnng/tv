package com.example.tv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 直播网页宿主复刻原版 ChannelPlayerView 的固定层级：WebView 在下，HTML5 custom view 在上。
 * 页面进入视频全屏后只替换覆盖层内容，横竖屏调整宿主尺寸时不会重建网页或视频实例。作者：long
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class TvWebPlayerHost(
  context: Context,
  private val pageUrl: String,
  configuredUserAgent: String?,
  private val onMainFrameError: (String?) -> Unit,
  private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
  private val onPageProgress: (Int) -> Unit = {},
  private val onMediaUrl: (String) -> Unit = {},
  private val onVideoPlaybackReady: () -> Unit = {},
  private val onPlaybackRecovery: () -> Unit = {},
) : FrameLayout(context) {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val fullscreenContainer = FrameLayout(context).apply {
    setBackgroundColor(Color.BLACK)
    visibility = View.GONE
  }
  private val firstFrameCover = View(context).apply {
    setBackgroundColor(Color.BLACK)
  }
  private var customView: View? = null
  private var customViewCallback: WebChromeClient.CustomViewCallback? = null
  private var keepVideoCustomView = false
  private var liveManifestRequested = false
  private var reenterGeneration = 0
  private var videoWidth = 16
  private var videoHeight = 9
  private var aspectRatioOverride = 0f
  private var containerWidth = 0
  private var containerHeight = 0
  private var injectedUrl = ""
  private var zoomAdjustedUrl = ""
  private var videoPlaybackReadyNotified = false
  private var playbackRecoveryScheduled = false
  private var lastPlaybackRecoveryAt = 0L

  val webView = WebView(context).apply {
    tag = "zhuiju_live_preview_web_view"
    setBackgroundColor(Color.BLACK)
  }

  init {
    setBackgroundColor(Color.BLACK)
    addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(fullscreenContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    // 网页在真正出首帧前会短暂绘制 Chromium 默认的大播放按钮；电视端改由底部加载浮层反馈状态，
    // 这里先保持纯黑首帧遮罩，避免网页临时控件成为用户可见的过渡画面。作者：long
    addView(firstFrameCover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    configureWebView(configuredUserAgent)
    Log.i(LOG_TAG, "webview create page=$pageUrl")
    webView.loadUrl(pageUrl)
  }

  @SuppressLint("JavascriptInterface")
  private fun configureWebView(configuredUserAgent: String?) = with(webView) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mediaPlaybackRequiresUserGesture = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    // 原版 X5 WebView 开启缩放后主动缩小到页面全宽；系统 WebView 也要保留这条路径，
    // 否则桌面版直播页会按过大的 CSS viewport 渲染，只露出左上角局部内容。作者：long
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // 原版 X5 在桌面直播页使用约 50% 的初始比例，才能让固定宽度网页和右侧节目表
    // 同时落入播放器窗口；系统 WebView 不会自动继承该比例，因此显式对齐。作者：long
    setInitialScale(50)
    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
    settings.userAgentString = configuredUserAgent?.takeIf(String::isNotBlank) ?: DESKTOP_USER_AGENT
    addJavascriptInterface(LiveJavascriptBridge(), "main")
    webChromeClient = object : WebChromeClient() {
      override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onPageProgress(newProgress.coerceIn(0, 100))
        if (newProgress > 0 && view.url != "about:blank" && zoomAdjustedUrl != view.url) {
          zoomAdjustedUrl = view.url.orEmpty()
          scheduleOriginalWebScale(view, view.url.orEmpty())
        }
        if (newProgress == 100 && view.url != "about:blank" && injectedUrl != view.url) {
          injectedUrl = view.url.orEmpty()
          injectOriginalVideoAdapter()
        }
      }

      override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
          callback.onCustomViewHidden()
          return
        }
        keepVideoCustomView = true
        reenterGeneration += 1
        // 原版直接把 Chromium 返回的视频 View 加入播放器覆盖层，网页菜单因此不会进入画面。作者：long
        customView = view
        customViewCallback = callback
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(
          view,
          LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.bringToFront()
        // fullScreen 容器会被提升到最上层；首帧遮罩也必须随之置顶，
        // 否则 Chromium 的默认播放按钮仍会覆盖在遮罩之上。作者：long
        firstFrameCover.bringToFront()
        // Chromium 可能先于横屏配置完成回调；等容器布局落定后再按横屏真实尺寸重排，避免沿用竖屏 1080x608。作者：long
        fullscreenContainer.post { applyFullscreenLayout() }
        // custom view 出现只表示 Chromium 已创建全屏容器，尚可能是默认播放按钮；
        // 必须等待网页桥接确认媒体时间持续推进后，才允许移除黑色首帧遮罩。作者：long
        Log.i(
          LOG_TAG,
          "custom view show type=${view.javaClass.name} video=${videoWidth}x$videoHeight " +
            "host=${width}x$height requested=${containerWidth}x$containerHeight",
        )
      }

      override fun onHideCustomView() {
        hideCustomView(reenterFullscreen = true)
      }
    }
    webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
        val url = request.url.toString()
        if (!request.isForMainFrame && isHlsManifest(url)) {
          // 央视频页面会先通过官方脚本生成短期签名，再请求真实 HLS 清单；捕获该地址交给原生播放器，
          // 避免 Android 12 WebView 91 继续承担 WASM/MSE 解码。作者：long
          Log.i(LOG_TAG, "media manifest url=${url.take(700)}")
          onMediaUrl(url)
          markLiveManifestRequested()
        }
        return super.shouldInterceptRequest(view, request)
      }

      @Suppress("DEPRECATION")
      override fun shouldInterceptRequest(view: WebView, url: String): android.webkit.WebResourceResponse? {
        if (isHlsManifest(url)) {
          Log.i(LOG_TAG, "media manifest legacy url=${url.take(700)}")
          onMediaUrl(url)
          markLiveManifestRequested()
        }
        return super.shouldInterceptRequest(view, url)
      }

      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        injectedUrl = ""
        zoomAdjustedUrl = ""
        onMainFrameError(null)
        Log.i(LOG_TAG, "page started url=$url")
      }

      override fun onPageFinished(view: WebView, url: String) {
        // 页面完成后仍可能被站点脚本重新设置 viewport；分阶段重应用原版缩放，避免只显示网页内部固定尺寸区域。作者：long
        scheduleOriginalWebScale(view, url)
        Log.i(LOG_TAG, "page finished url=$url")
      }

      override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onMainFrameError("网页加载失败：${error.description}")
      }

      override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: android.webkit.WebResourceResponse,
      ) {
        if (request.isForMainFrame) onMainFrameError("网页加载失败：HTTP ${errorResponse.statusCode}")
      }

      override fun onReceivedSslError(
        view: WebView,
        handler: android.webkit.SslErrorHandler,
        error: android.net.http.SslError,
      ) {
        handler.cancel()
        onMainFrameError("网页证书校验失败")
      }
    }
  }

  private fun scheduleOriginalWebScale(view: WebView, url: String) {
    if (url.isBlank() || url == "about:blank") return
    listOf(0L, 120L, 420L, 900L).forEach { delayMs ->
      mainHandler.postDelayed({
        if (view.url != url || view.visibility != View.VISIBLE) return@postDelayed
        applyOriginalWebScale(view)
      }, delayMs)
    }
  }

  private fun applyOriginalWebScale(view: WebView) {
    // 系统 WebView 不会稳定继承移动端 X5 的页面缩放；先恢复 50% 初始比例，再补 zoomOut 直到网页全宽。作者：long
    view.setInitialScale(50)
    repeat(3) {
      if (view.canZoomOut()) view.zoomOut()
    }
  }

  private fun injectOriginalVideoAdapter() {
    val script = runCatching {
      context.assets.open(ADAPTER_ASSET).bufferedReader().use { it.readText() }
    }.onFailure { Log.e(LOG_TAG, "read adapter failed", it) }.getOrNull() ?: return
    webView.evaluateJavascript(
      "var style=document.createElement('style');style.innerHTML='video::-webkit-media-controls,video::-webkit-media-controls-enclosure,video::-webkit-media-controls-overlay-play-button{display:none!important;}';document.head.appendChild(style);",
      null,
    )
    webView.evaluateJavascript(script) { Log.i(LOG_TAG, "video adapter injected url=${webView.url}") }
  }

  private fun revealFirstFrameWhenReady() {
    // 只有官方直播 HLS 已请求、custom view 已接管且浏览器提交首帧后才揭开遮罩，避免暂停图标闪现。作者：long
    if (!liveManifestRequested || customView == null || firstFrameCover.visibility != View.VISIBLE) return
    firstFrameCover.animate()
      .alpha(0f)
      .setDuration(220L)
      .withEndAction {
        firstFrameCover.visibility = View.GONE
        firstFrameCover.alpha = 1f
        // 画面已越过黑色首帧遮罩才通知界面层进入完成态，不能把网页 100% 误当成直播已播放。作者：long
        notifyVideoPlaybackReady()
      }
      .start()
  }

  private fun notifyVideoPlaybackReady() {
    if (videoPlaybackReadyNotified) return
    videoPlaybackReadyNotified = true
    onVideoPlaybackReady()
  }

  private fun recoverPlayback(reason: String) {
    val now = SystemClock.uptimeMillis()
    if (playbackRecoveryScheduled || now - lastPlaybackRecoveryAt < 10_000L) return
    playbackRecoveryScheduled = true
    lastPlaybackRecoveryAt = now
    Log.w(LOG_TAG, "playback stalled reason=$reason; reload page=$pageUrl")
    onPlaybackRecovery()
    // 签名 HLS 清单可能已经失效；销毁旧 custom view 后重新加载官方页面，才能重新生成会话地址。作者：long
    keepVideoCustomView = false
    hideCustomView(reenterFullscreen = false)
    firstFrameCover.animate().cancel()
    firstFrameCover.alpha = 1f
    firstFrameCover.visibility = View.VISIBLE
    fullscreenContainer.visibility = View.GONE
    liveManifestRequested = false
    videoPlaybackReadyNotified = false
    mainHandler.postDelayed({
      if (!isAttachedToWindow) {
        playbackRecoveryScheduled = false
        return@postDelayed
      }
      webView.loadUrl(pageUrl)
      playbackRecoveryScheduled = false
    }, 250L)
  }

  private fun markLiveManifestRequested() {
    mainHandler.post {
      if (liveManifestRequested) return@post
      liveManifestRequested = true
    }
  }

  private fun requestVideoCustomView() {
    if (customView != null) return
    val downTime = SystemClock.uptimeMillis()
    webView.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0))
    mainHandler.postDelayed({
      webView.dispatchKeyEvent(
        KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0),
      )
    }, 50L)
  }

  /**
   * 横竖屏切换时 Chromium 会先回调隐藏 custom view，再异步完成 WebView 重排。
   * 立刻重新按键会被这次重排吞掉，导致网页导航层露出；延迟重试直到视频覆盖层重新接管。作者：long
   */
  private fun scheduleVideoCustomViewReentry() {
    val generation = ++reenterGeneration
    listOf(120L, 420L, 900L, 1_600L).forEach { delayMs ->
      mainHandler.postDelayed({
        if (!keepVideoCustomView || generation != reenterGeneration || customView != null) return@postDelayed
        requestVideoCustomView()
      }, delayMs)
    }
  }

  private fun updateVideoSize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    videoWidth = width
    videoHeight = height
    onVideoSizeChanged(width, height)
    applyFullscreenLayout()
  }

  /** 原版设置面板会在全屏状态下直接重排网页 custom view，保留播放器实例不重新加载频道。作者：long */
  fun setAspectRatioOverride(ratio: Float) {
    val resolvedRatio = ratio.takeIf { it > 0f } ?: 0f
    if (aspectRatioOverride == resolvedRatio) return
    aspectRatioOverride = resolvedRatio
    applyFullscreenLayout()
  }

  /**
   * Compose 横竖屏重测量完成后显式传入播放器像素尺寸，等价于原版在 onConfigurationChanged 中
   * 同步更新 playerView、fullscreenContainer 和 custom video 子视图。作者：long
   */
  fun setContainerSize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val changed = containerWidth != width || containerHeight != height
    containerWidth = width
    containerHeight = height
    applyFullscreenLayout()
    if (changed) {
      // 部分 Chromium custom view 会在当前布局帧末尾覆盖 LayoutParams，再投递一次保证最终尺寸生效。作者：long
      fullscreenContainer.post { applyFullscreenLayout() }
      Log.i(LOG_TAG, "container size=${width}x$height host=${this.width}x${this.height}")
    }
  }

  private fun applyFullscreenLayout() {
    val targetContainerWidth = containerWidth.takeIf { it > 0 } ?: width
    val targetContainerHeight = containerHeight.takeIf { it > 0 } ?: height
    if (targetContainerWidth <= 0 || targetContainerHeight <= 0) return

    val containerParams = fullscreenContainer.layoutParams as? LayoutParams
    if (containerParams == null || containerParams.width != targetContainerWidth || containerParams.height != targetContainerHeight) {
      fullscreenContainer.layoutParams = LayoutParams(targetContainerWidth, targetContainerHeight, Gravity.CENTER)
    }
    customView?.let { view ->
      view.layoutParams = customViewLayoutParams(targetContainerWidth, targetContainerHeight)
      view.requestLayout()
    }
    fullscreenContainer.requestLayout()
  }

  private fun customViewLayoutParams(containerWidth: Int, containerHeight: Int): LayoutParams {
    if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
      return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
    }
    val videoRatio = if (aspectRatioOverride > 0f) aspectRatioOverride else videoWidth.toFloat() / videoHeight
    val containerRatio = containerWidth.toFloat() / containerHeight
    val targetWidth: Int
    val targetHeight: Int
    if (containerRatio > videoRatio) {
      targetHeight = containerHeight
      targetWidth = (containerHeight * videoRatio).roundToInt()
    } else {
      targetWidth = containerWidth
      targetHeight = (containerWidth / videoRatio).roundToInt()
    }
    return LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    setContainerSize(width, height)
  }

  private fun hideCustomView(reenterFullscreen: Boolean) {
    val view = customView ?: return
    fullscreenContainer.removeView(view)
    customViewCallback?.onCustomViewHidden()
    customView = null
    customViewCallback = null
    fullscreenContainer.visibility = View.GONE
    Log.i(LOG_TAG, "custom view hidden")
    // 原版在网页自行退出 custom view 后立即恢复视频覆盖层，避免菜单重新露出。作者：long
    if (reenterFullscreen && keepVideoCustomView) scheduleVideoCustomViewReentry()
  }

  fun release() {
    keepVideoCustomView = false
    reenterGeneration += 1
    // 切台销毁旧宿主时取消首帧淡出，避免旧频道的动画结束回调误把新频道标记为可播放。作者：long
    firstFrameCover.animate().cancel()
    mainHandler.removeCallbacksAndMessages(null)
    hideCustomView(reenterFullscreen = false)
    webView.removeJavascriptInterface("main")
    webView.stopLoading()
    webView.destroy()
  }

  private inner class LiveJavascriptBridge {
    @JavascriptInterface fun notifyVideoFrameReady() {
      mainHandler.post { revealFirstFrameWhenReady() }
    }

    @JavascriptInterface fun notifyVideoStalled(reason: String?) {
      mainHandler.post { recoverPlayback(reason.orEmpty()) }
    }

    @JavascriptInterface fun enablePlayCheck() = Unit

    @JavascriptInterface fun disablePlayCheck() = Unit

    @JavascriptInterface fun schemeEnterFullscreen() {
      mainHandler.post { requestVideoCustomView() }
    }

    @JavascriptInterface fun setVideoSize(width: Int, height: Int) {
      mainHandler.post { updateVideoSize(width, height) }
    }
  }

  private fun isHlsManifest(url: String): Boolean =
    url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) &&
      (url.startsWith("https://hlslive-", ignoreCase = true) || url.contains("yangshipin", ignoreCase = true))

  private companion object {
    const val LOG_TAG = "ZhuijuLiveWeb"
    const val ADAPTER_ASSET = "tita_live_video_adapter.js"
    const val DESKTOP_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
  }
}
