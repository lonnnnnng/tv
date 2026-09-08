(function () {
  if (window.wvt_javascriptInjected) return;
  window.wvt_javascriptInjected = true;
  window.wvt_video = null;
  window.wvt_lastTimeUpdate = 0;
  window.wvt_lastPresentedTime = -1;
  window.wvt_firstStableMediaTime = null;
  window.wvt_lastStableMediaTime = null;
  window.wvt_lastMediaAdvanceAt = 0;
  window.wvt_videoFrameReadyNotified = false;
  window.wvt_lastHealthMediaTime = null;
  window.wvt_healthStalledAt = 0;
  window.wvt_healthStallReported = false;
  window.wvt_lastStallReportAt = 0;

  function resetStablePlaybackCheck() {
    window.wvt_firstStableMediaTime = null;
    window.wvt_lastStableMediaTime = null;
    window.wvt_lastMediaAdvanceAt = 0;
  }

  function notifyVideoFrameReady(video) {
    if (window.wvt_videoFrameReadyNotified) return;
    if (video.readyState < 3 || video.paused || video.videoWidth <= 0) {
      resetStablePlaybackCheck();
      return;
    }

    var now = Date.now();
    var mediaTime = video.currentTime;
    if (!isFinite(mediaTime)) return;
    if (window.wvt_lastStableMediaTime === null || mediaTime < window.wvt_lastStableMediaTime) {
      window.wvt_firstStableMediaTime = mediaTime;
      window.wvt_lastStableMediaTime = mediaTime;
      window.wvt_lastMediaAdvanceAt = now;
      return;
    }
    if (mediaTime > window.wvt_lastStableMediaTime + 0.08) {
      window.wvt_lastStableMediaTime = mediaTime;
      window.wvt_lastMediaAdvanceAt = now;
    } else if (now - window.wvt_lastMediaAdvanceAt > 1200) {
      // 官方页会先提交预览帧再回到暂停态；一旦媒体时间停滞，重新开始统计，避免提前露出 Chromium 播放按钮。作者：long
      resetStablePlaybackCheck();
      return;
    }
    // 不按网络或固定秒数开遮罩，只在视频自身已连续播放足够长时切入真实画面。作者：long
    if (mediaTime - window.wvt_firstStableMediaTime >= 1.2) {
      window.wvt_videoFrameReadyNotified = true;
      window.main.notifyVideoFrameReady();
    }
  }

  function resetPlaybackHealth(video) {
    window.wvt_lastHealthMediaTime = isFinite(video.currentTime) ? video.currentTime : null;
    window.wvt_healthStalledAt = 0;
    window.wvt_healthStallReported = false;
  }

  function notifyPlaybackStalled(reason) {
    var now = Date.now();
    // 网络短抖动不重载页面；同一页面至少间隔 15 秒才允许再次请求新播放会话。作者：long
    if (window.wvt_healthStallReported || now - window.wvt_lastStallReportAt < 15000) return;
    window.wvt_healthStallReported = true;
    window.wvt_lastStallReportAt = now;
    window.main.notifyVideoStalled(reason || 'media_stalled');
  }

  function checkPlaybackHealth(video) {
    if (!video || !video.isConnected) return;
    if (video.error || video.ended) {
      notifyPlaybackStalled(video.error ? 'media_error' : 'media_ended');
      return;
    }
    if (video.paused || video.readyState < 2 || video.videoWidth <= 0) {
      resetPlaybackHealth(video);
      return;
    }

    var now = Date.now();
    var currentTime = video.currentTime;
    if (!isFinite(currentTime)) return;
    if (window.wvt_lastHealthMediaTime === null || Math.abs(currentTime - window.wvt_lastHealthMediaTime) > 0.05) {
      // 只要媒体时间持续推进，就清除此前的停滞状态。作者：long
      window.wvt_lastHealthMediaTime = currentTime;
      window.wvt_healthStalledAt = 0;
      window.wvt_healthStallReported = false;
      return;
    }
    if (!window.wvt_healthStalledAt) window.wvt_healthStalledAt = now;
    if (now - window.wvt_healthStalledAt >= 12000) notifyPlaybackStalled('media_time_stalled');
  }

  function startPlaybackHealthWatch(video) {
    if (video.wvt_healthWatchStarted) return;
    video.wvt_healthWatchStarted = true;
    resetPlaybackHealth(video);
    video.addEventListener('playing', function () { resetPlaybackHealth(video); });
    video.addEventListener('waiting', function () {
      if (!window.wvt_healthStalledAt) window.wvt_healthStalledAt = Date.now();
    });
    video.addEventListener('stalled', function () {
      if (!window.wvt_healthStalledAt) window.wvt_healthStalledAt = Date.now();
    });
    video.addEventListener('error', function () { notifyPlaybackStalled('media_error'); });
    (function healthLoop() {
      if (!video.isConnected || window.wvt_video !== video) return;
      checkPlaybackHealth(video);
      setTimeout(healthLoop, 2000);
    })();
  }

  function requestPresentedFrame(video) {
    if (typeof video.requestVideoFrameCallback !== 'function') return;
    video.requestVideoFrameCallback(function (now, metadata) {
      var presentedFrames = metadata && metadata.presentedFrames ? metadata.presentedFrames : 0;
      if (presentedFrames !== window.wvt_lastPresentedTime) {
        window.wvt_lastPresentedTime = presentedFrames;
        notifyVideoFrameReady(video);
      }
      requestPresentedFrame(video);
    });
  }

  function reportVideoSize(video) {
    window.main.setVideoSize(video.videoWidth, video.videoHeight);
  }

  function ensureVideoVolume(video) {
    video.muted = false;
    video.defaultMuted = false;
    video.volume = 1;
  }

  function makeVideoFullscreen() {
    if (document.fullscreenElement || !window.wvt_video) return;
    if (!document.documentElement.contains(window.wvt_video) && !window.wvt_video.getRootNode().host) return;
    window.wvt_video.requestFullscreen().catch(function (error) {
      console.error('[WebViewTV] ' + error.message);
    });
  }

  function setupVideo(video) {
    if (video.wvt_setup) return;
    if (document.fullscreenElement) document.exitFullscreen();
    window.main.enablePlayCheck();
    ensureVideoVolume(video);
    video.autoplay = true;
    video.style.objectFit = 'fill';
    video.removeAttribute('controls');
    video.addEventListener('play', function () {
      window.main.schemeEnterFullscreen();
      setTimeout(function () { ensureVideoVolume(video); }, 1000);
    });
    video.addEventListener('timeupdate', function () {
      if (typeof video.requestVideoFrameCallback === 'function') return;
      var now = Date.now();
      if (now - window.wvt_lastTimeUpdate >= 2000) {
        // 老 WebView 缺少逐帧回调时，仍复用媒体时间连续推进的判定，不能退回为页面加载完成即放行。作者：long
        notifyVideoFrameReady(video);
        window.wvt_lastTimeUpdate = now;
      }
    });
    video.addEventListener('canplay', function () { reportVideoSize(video); });
    video.wvt_setup = true;
    startPlaybackHealthWatch(video);
    requestPresentedFrame(video);
    if (!video.paused) {
      window.main.schemeEnterFullscreen();
      reportVideoSize(video);
    }
  }

  function findVideo() {
    var direct = document.querySelector('video');
    if (direct) return direct;
    var nodes = document.querySelectorAll('*');
    for (var i = 0; i < nodes.length; i += 1) {
      if (nodes[i].shadowRoot) {
        var shadowVideo = nodes[i].shadowRoot.querySelector('video');
        if (shadowVideo) return shadowVideo;
      }
    }
    return null;
  }

  function loop() {
    setTimeout(loop, document.fullscreenElement ? 5000 : 500);
    if (window.wvt_video && !window.wvt_video.isConnected) {
      window.main.disablePlayCheck();
      if (document.fullscreenElement) document.exitFullscreen();
      window.wvt_video = null;
    }
    if (!window.wvt_video) {
      window.wvt_video = findVideo();
      if (window.wvt_video) setupVideo(window.wvt_video);
      return;
    }
    if (window.wvt_video.paused && !window.wvt_video.error) {
      window.wvt_video.play().catch(function () {});
    }
  }

  // 原版由 Native 注入 KEYCODE_F，再通过该键盘事件调用 video.requestFullscreen()。作者：long
  document.onkeydown = function (event) {
    if (event.key === 'f') makeVideoFullscreen();
  };
  setTimeout(loop, 1000);
})();
