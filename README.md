# TV 直播

独立的 Android TV 电视直播应用，沿用追剧项目的官方网页直播方案，包名为 `com.example.tv`。支持 Android 8.0（API 26）及以上系统，采用横屏遥控器交互。

正式版下载：[GitHub Releases](https://github.com/lonnnnnng/tv/releases/latest)。

## 已实现

- 横屏沉浸式直播播放，使用 WebView 承载 CCTV/央视频等官方直播页面。
- 频道配置位于 `app/src/main/assets/sources/base/sourceLive.plain.json`，央视频为默认来源，其他官方页面为备用来源。
- 播放时上下键切换频道；OK 键打开频道浮窗，浮窗内上下键选择、OK 键确认，无操作 5 秒自动收起。
- 右键打开播放信息，返回键关闭浮窗。
- 频道抽屉支持频道焦点、频道数量、收藏/取消收藏。
- 播放信息面板支持切换同频道备用来源。
- 网页加载失败提示和 OK 重试。
- 底部加载浮层显示频道、网页地址和进度，实际出画前进度不超过 99%，出画后再保留 3 秒。
- 视频停滞或媒体错误时尝试重新加载官方页面，重新获取直播会话。
- Android TV Leanback 启动入口和无触摸屏声明。

## 构建

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 正式版构建

使用 JDK 17、Android SDK 36 和项目内的 Gradle Wrapper。正式版开启 R8 代码优化、混淆、资源压缩，并使用独立发布证书，禁用应用调试。

```zsh
bash tools/build_release.sh
```

签名 APK：`app/build/outputs/apk/release/app-release.apk`。构建所用签名环境、产物校验和混淆映射归档见 [发布说明](docs/releasing.md)。

调试版与正式版证书不同，不能相互覆盖安装。已有调试版的设备需要先备份数据，再手动卸载调试版后安装正式版。

## 说明

直播地址和页面行为以 `sourceLive.plain.json` 及官方页面当前可用性为准。应用不抓取或重分发视频流，网页播放失败时可用频道的备用页面来源重试。
