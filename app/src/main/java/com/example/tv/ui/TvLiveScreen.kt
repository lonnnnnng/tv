package com.example.tv.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tv.data.ChannelCatalog
import com.example.tv.data.TvChannel
import com.example.tv.player.TvWebPlayerHost
import android.util.Log

private val TvBackground = Color(0xFF080A0D)
private val TvPanel = Color(0xE6191E25)
private val TvGreen = Color(0xFF62D7A4)
private val TvMuted = Color(0xFFAAB3BD)

@Composable
fun TvLiveScreen() {
  val context = LocalContext.current
  val channels = remember { ChannelCatalog.load(context) }
  var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
  var drawerSelectedIndex by rememberSaveable { mutableIntStateOf(0) }
  var sourceIndex by rememberSaveable { mutableIntStateOf(0) }
  // 进入应用先把画面交给直播，频道抽屉只由 OK 键显式打开，避免频道列表遮住首帧。作者：long
  var drawerOpen by rememberSaveable { mutableStateOf(false) }
  var infoOpen by rememberSaveable { mutableStateOf(false) }
  var favorite by rememberSaveable { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var networkAvailable by remember { mutableStateOf(isNetworkAvailable(context)) }
  var reloadGeneration by remember { mutableIntStateOf(0) }
  var pageProgress by remember { mutableIntStateOf(0) }
  var loadingVisible by remember { mutableStateOf(true) }
  var videoPlaybackReadyUrl by remember { mutableStateOf("") }
  var drawerInteractionTick by remember { mutableIntStateOf(0) }
  val controllerRequester = remember { FocusRequester() }
  val selected = channels.getOrNull(selectedIndex) ?: channels.firstOrNull()
  val favorites = remember { FavoriteStore(context) }
  val activePageUrl = selected?.pages?.getOrNull(sourceIndex) ?: selected?.primaryPage.orEmpty()

  DisposableEffect(context) {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        networkAvailable = isNetworkAvailable(context)
      }

      override fun onLost(network: Network) {
        networkAvailable = isNetworkAvailable(context)
      }
    }
    connectivityManager?.registerDefaultNetworkCallback(callback)
    onDispose { if (connectivityManager != null) connectivityManager.unregisterNetworkCallback(callback) }
  }

  LaunchedEffect(selected?.name) {
    sourceIndex = 0
    favorite = selected?.let(favorites::contains) == true
    error = null
    Log.i("TvLiveAudit", "selected index=$selectedIndex name=${selected?.name.orEmpty()}")
    // WebView 每次重建都会尝试参与焦点导航；切台后立即把遥控器焦点还给 Compose 控制根节点。作者：long
    controllerRequester.requestFocus()
  }
  LaunchedEffect(drawerOpen) {
    if (drawerOpen) {
      // 打开频道浮窗时先定位到当前正在播放的频道；上下键只改变浮窗选择，不提前切换直播。作者：long
      drawerSelectedIndex = selectedIndex
      drawerInteractionTick++
    } else {
      // 浮窗关闭后把焦点交给全屏控制层，确保电视遥控器上下键直接切台而不是交给网页滚动。作者：long
      controllerRequester.requestFocus()
    }
  }
  LaunchedEffect(drawerOpen, drawerInteractionTick) {
    if (!drawerOpen) return@LaunchedEffect
    // 频道浮窗只在用户持续操作时保留；停止操作 5 秒后自动回到全屏播放，避免遮挡直播画面。作者：long
    kotlinx.coroutines.delay(5_000L)
    drawerOpen = false
  }
  LaunchedEffect(activePageUrl) {
    // 每次切台或切换备用源都重新展示加载浮层，避免上一频道的完成状态瞬间穿透到新页面。作者：long
    pageProgress = 0
    loadingVisible = true
    videoPlaybackReadyUrl = ""
  }
  LaunchedEffect(networkAvailable) {
    // 电视设备离线时不创建 WebView，避免 Chromium 把系统网络错误伪装成频道网页故障。作者：long
    if (!networkAvailable) loadingVisible = false
  }
  LaunchedEffect(videoPlaybackReadyUrl) {
    if (videoPlaybackReadyUrl.isBlank()) return@LaunchedEffect
    // 页面加载结束不代表直播有画面；只从视频稳定播放后的完成态开始计时，避免黑屏时提前收起浮层。作者：long
    kotlinx.coroutines.delay(3_000L)
    if (videoPlaybackReadyUrl == activePageUrl) loadingVisible = false
  }

  Box(
    Modifier
      .fillMaxSize()
      .background(TvBackground)
      .focusRequester(controllerRequester)
      .focusable()
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.DirectionUp -> if (!drawerOpen) {
            selectedIndex = previousIndex(selectedIndex, channels.size)
            true
          } else {
            // 浮窗内上下键只移动待确认频道，播放中的频道保持不变。作者：long
            drawerSelectedIndex = previousIndex(drawerSelectedIndex, channels.size)
            drawerInteractionTick++
            true
          }
          Key.DirectionDown -> if (!drawerOpen) {
            selectedIndex = nextIndex(selectedIndex, channels.size)
            true
          } else {
            // 浮窗内上下键只移动待确认频道，播放中的频道保持不变。作者：long
            drawerSelectedIndex = nextIndex(drawerSelectedIndex, channels.size)
            drawerInteractionTick++
            true
          }
          // 抽屉内左键收起；播放态不再用左键打开抽屉，避免和用户熟悉的 OK 菜单语义冲突。作者：long
          Key.DirectionLeft -> if (drawerOpen) { drawerOpen = false; true } else false
          Key.DirectionRight -> if (drawerOpen) false else { infoOpen = !infoOpen; true }
          // 抽屉打开时把 OK 留给当前聚焦的频道行提交选择；播放态 OK 才负责显示频道抽屉。作者：long
          Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> when {
            drawerOpen -> {
              // 浮窗内 OK 才提交频道；选中过程不触发网页重建，避免上下键浏览时反复加载直播。作者：long
              if (channels.isNotEmpty()) {
                selectedIndex = drawerSelectedIndex.coerceIn(0, channels.lastIndex)
              }
              drawerOpen = false
              true
            }
            !networkAvailable -> {
              networkAvailable = isNetworkAvailable(context)
              true
            }
            error != null -> {
              // 用户选择重试时必须重建 WebView；仅清理提示不会触发网页重新请求。作者：long
              error = null
              reloadGeneration += 1
              true
            }
            else -> {
              drawerSelectedIndex = selectedIndex
              drawerInteractionTick++
              drawerOpen = true
              true
            }
          }
          Key.Back, Key.Escape -> if (drawerOpen) { drawerOpen = false; true } else if (infoOpen) { infoOpen = false; true } else false
          else -> false
        }
      },
  ) {
    if (networkAvailable) selected?.let { channel ->
      // 切台时旧 WebView 可能还在主线程回调进度；只接收当前网页地址的状态，避免旧频道覆盖新浮层。作者：long
      val requestedPageUrl = activePageUrl
      TvWebPlayer(
        pageUrl = requestedPageUrl,
        onError = { message ->
          if (requestedPageUrl == activePageUrl) {
            error = message
            if (message != null) loadingVisible = false
          }
        },
        onPageProgress = { progress ->
          if (requestedPageUrl == activePageUrl) {
            val normalized = progress.coerceIn(0, 100)
            // 网页 DOM 已完成时播放器仍可能黑屏缓冲；在真实视频出画前永远不展示 100%。作者：long
            pageProgress = if (videoPlaybackReadyUrl == requestedPageUrl) 100 else normalized.coerceAtMost(99)
          }
        },
        onMediaUrl = { mediaUrl ->
          if (requestedPageUrl == activePageUrl) {
            Log.i("TvLiveMedia", "channel=${channel.name} url=${mediaUrl.take(700)}")
          }
        },
        onVideoPlaybackReady = {
          if (requestedPageUrl == activePageUrl) {
            // 宿主已移除首帧黑色遮罩，直播画面此刻对用户可见；再显示完成进度并保留 3 秒。作者：long
            pageProgress = 100
            videoPlaybackReadyUrl = requestedPageUrl
          }
        },
        onPlaybackRecovery = {
          if (requestedPageUrl == activePageUrl) {
            // 长播看门狗刷新官方页面时重新展示加载态，让用户知道正在获取新的直播会话。作者：long
            error = null
            pageProgress = 0
            loadingVisible = true
            videoPlaybackReadyUrl = ""
          }
        },
        reloadGeneration = reloadGeneration,
        modifier = Modifier.fillMaxSize(),
      )
    }
    if (loadingVisible && selected != null) {
      LiveLoadingOverlay(
        channelName = selected.name,
        pageUrl = activePageUrl,
        progress = pageProgress,
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
    if (drawerOpen) {
      ChannelDrawer(
        channels = channels,
        selectedIndex = drawerSelectedIndex,
        favorites = favorites,
        onSelected = { index ->
          selectedIndex = index
          drawerSelectedIndex = index
          drawerOpen = false
        },
        onFavoriteChanged = { channel, checked -> if (checked) favorites.add(channel) else favorites.remove(channel); favorite = checked },
        modifier = Modifier.align(Alignment.CenterStart),
      )
    }
    if (infoOpen) {
      InfoPanel(
        channel = selected,
        sourceIndex = sourceIndex,
        favorite = favorite,
        onFavorite = { checked -> selected?.let { if (checked) favorites.add(it) else favorites.remove(it) }; favorite = checked },
        onSource = { sourceIndex = (sourceIndex + 1) % (selected?.pages?.size?.coerceAtLeast(1) ?: 1) },
        modifier = Modifier.align(Alignment.BottomEnd),
      )
    }
    when {
      !networkAvailable -> PlayerError(
        message = "设备网络未连接，请连接 Wi-Fi 后按 OK 重试",
        onRetry = { networkAvailable = isNetworkAvailable(context) },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
      error != null -> PlayerError(
        message = error.orEmpty(),
        onRetry = {
          error = null
          reloadGeneration += 1
        },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

private fun previousIndex(current: Int, size: Int): Int = if (size == 0) 0 else if (current == 0) size - 1 else current - 1
private fun nextIndex(current: Int, size: Int): Int = if (size == 0) 0 else (current + 1) % size

@Composable
private fun TvWebPlayer(
  pageUrl: String,
  onError: (String?) -> Unit,
  onPageProgress: (Int) -> Unit,
  onMediaUrl: (String) -> Unit,
  onVideoPlaybackReady: () -> Unit,
  onPlaybackRecovery: () -> Unit,
  reloadGeneration: Int,
  modifier: Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  // 播放器切台会替换 WebView，但外层 TV 播放区域尺寸不变；保留尺寸状态，避免新宿主首帧拿到 0x0。作者：long
  key(pageUrl, reloadGeneration) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val host = remember {
      TvWebPlayerHost(
        context,
        pageUrl,
        null,
        onMainFrameError = { message ->
          // 页面重新开始加载时宿主会先回调 null；必须同步清理上一频道错误，否则旧错误浮层会覆盖新频道已经出画的页面。作者：long
          onError(message)
        },
        onPageProgress = onPageProgress,
        onMediaUrl = onMediaUrl,
        onVideoPlaybackReady = onVideoPlaybackReady,
        onPlaybackRecovery = onPlaybackRecovery,
      )
    }
    LaunchedEffect(host) {
      // 视频网页只负责渲染和播放，不应抢走电视遥控器的 D-pad 焦点。作者：long
      host.isFocusable = false
      host.isFocusableInTouchMode = false
      host.webView.isFocusable = false
      host.webView.isFocusableInTouchMode = false
    }
    LaunchedEffect(host, containerSize) {
      // 新频道 WebView 创建早于 AndroidView update 时，仍显式补一次父容器尺寸，保证 custom view 不会以 0x0 布局。作者：long
      host.setContainerSize(containerSize.width, containerSize.height)
    }
    DisposableEffect(host, lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) host.webView.onPause() else if (event == Lifecycle.Event.ON_RESUME) host.webView.onResume() }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer); host.release() }
    }
    AndroidView(
      factory = { host },
      modifier = modifier.onSizeChanged { containerSize = it },
      update = { player ->
        // 每个频道使用独立的 AndroidView 节点，避免旧 WebView 留在容器里而新宿主只有状态没有实际视图。作者：long
        player.setContainerSize(containerSize.width, containerSize.height)
      },
    )
  }
}

private fun isNetworkAvailable(context: Context): Boolean {
  val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
  val network = connectivityManager.activeNetwork ?: return false
  val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
  return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
private fun LiveLoadingOverlay(channelName: String, pageUrl: String, progress: Int, modifier: Modifier) {
  Column(
    modifier
      // 电视观看距离比手机更远，浮层需要保留更大的触达面积和视觉层级。作者：long
      .fillMaxWidth(0.96f)
      .padding(bottom = 38.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(Color(0xCC202020))
      .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          channelName,
          color = Color.White,
          fontSize = 24.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          pageUrl,
          color = Color(0xFFBDBDBD),
          fontSize = 15.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
      Text(
        "${progress.coerceIn(0, 100)}%",
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 18.dp),
      )
    }
    LinearProgressIndicator(
      progress = { progress.coerceIn(0, 100) / 100f },
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp),
      color = TvGreen,
      trackColor = Color(0x66555555),
    )
  }
}

@Composable
private fun ChannelDrawer(
  channels: List<TvChannel>, selectedIndex: Int, favorites: FavoriteStore,
  onSelected: (Int) -> Unit, onFavoriteChanged: (TvChannel, Boolean) -> Unit, modifier: Modifier,
) {
  val firstRequester = remember { FocusRequester() }
  Surface(modifier.widthIn(min = 330.dp, max = 420.dp).fillMaxHeight().padding(vertical = 20.dp, horizontal = 22.dp), color = TvPanel, shape = RoundedCornerShape(18.dp), tonalElevation = 8.dp) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("频道", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("  ${channels.size} 个", color = TvMuted, fontSize = 16.sp)
      }
      LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        itemsIndexed(channels, key = { _, channel -> channel.group + channel.name }) { index, channel ->
          val isFavorite = favorites.contains(channel)
          ChannelRow(channel, index == selectedIndex, isFavorite, Modifier.then(if (index == selectedIndex) Modifier.focusRequester(firstRequester) else Modifier), onClick = { onSelected(index) }, onFavorite = { onFavoriteChanged(channel, !isFavorite) })
        }
      }
      LaunchedEffect(Unit) { firstRequester.requestFocus() }
    }
  }
}

@Composable
private fun ChannelRow(channel: TvChannel, selected: Boolean, favorite: Boolean, modifier: Modifier, onClick: () -> Unit, onFavorite: () -> Unit) {
  Row(
    modifier
      .clip(RoundedCornerShape(10.dp))
      .background(if (selected) TvGreen.copy(alpha = .18f) else Color.Transparent)
      .border(if (selected) 2.dp else 1.dp, if (selected) TvGreen else Color(0x335D6874), RoundedCornerShape(10.dp))
      .focusable()
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter)) {
          onClick()
          true
        } else false
      }
      .padding(horizontal = 13.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(channel.name, color = if (selected) Color.White else Color(0xFFE0E6EB), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(channel.group, color = TvMuted, fontSize = 13.sp)
    }
    Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "收藏", tint = if (favorite) Color(0xFFFF8698) else TvMuted, modifier = Modifier.width(24.dp).focusable().onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { onFavorite(); true } else false })
  }
}

@Composable
private fun InfoPanel(channel: TvChannel?, sourceIndex: Int, favorite: Boolean, onFavorite: (Boolean) -> Unit, onSource: () -> Unit, modifier: Modifier) {
  Surface(modifier.width(370.dp).padding(28.dp), color = TvPanel, shape = RoundedCornerShape(16.dp)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = TvGreen); Text("播放信息", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
      Text(channel?.name ?: "未选择频道", color = Color.White, fontSize = 18.sp)
      Text("来源 ${sourceIndex + 1} / ${channel?.pages?.size ?: 0}", color = TvMuted, fontSize = 14.sp)
      Text("右键切换来源", color = TvMuted, fontSize = 13.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionChip(if (favorite) "取消收藏" else "收藏", if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder) { onFavorite(!favorite) }
        ActionChip("切换源", Icons.Default.Settings, onSource)
      }
    }
  }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
  Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x335E6A75)).focusable().padding(horizontal = 12.dp, vertical = 9.dp).onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { onClick(); true } else false }, verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = TvGreen, modifier = Modifier.width(18.dp)); Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
  }
}

@Composable
private fun PlayerError(message: String, onRetry: () -> Unit, modifier: Modifier) {
  Surface(modifier.padding(28.dp), color = Color(0xEE4A2025), shape = RoundedCornerShape(10.dp)) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(message, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text("  OK 重试", color = TvGreen, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp).focusable().onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { onRetry(); true } else false })
    }
  }
}

private class FavoriteStore(context: Context) {
  private val prefs = context.getSharedPreferences("tv_live", Context.MODE_PRIVATE)
  fun contains(channel: TvChannel): Boolean = prefs.getStringSet("favorites", emptySet()).orEmpty().contains(key(channel))
  fun add(channel: TvChannel) { val values = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet(); values += key(channel); prefs.edit().putStringSet("favorites", values).apply() }
  fun remove(channel: TvChannel) { val values = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet(); values -= key(channel); prefs.edit().putStringSet("favorites", values).apply() }
  private fun key(channel: TvChannel): String = channel.group + "|" + channel.name
}
