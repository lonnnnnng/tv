# 正式版发布

## 构建与签名

- JDK 17、Android SDK 36、Gradle Wrapper。
- 版本号与版本代码统一在 `app/build.gradle.kts` 中维护。
- 默认签名文件为 `$HOME/.android/tvlive-release.jks`，格式为 PKCS12，别名为 `tvlive`。
- macOS 密码保存在钥匙串服务 `tvlive-release-keystore`，账户为当前登录用户名。
- 密钥和密码必须单独备份，后续版本沿用同一签名，不能重新生成后替换。

其他构建机通过以下环境变量提供签名信息，不能把值写入仓库或构建日志：

| 变量 | 用途 |
| --- | --- |
| `TVLIVE_STORE_FILE` | PKCS12 密钥库绝对路径 |
| `TVLIVE_STORE_PASSWORD` | 密钥库密码 |
| `TVLIVE_KEY_ALIAS` | 私钥别名，默认 `tvlive` |
| `TVLIVE_KEY_PASSWORD` | 私钥密码，默认与密钥库密码相同 |

```zsh
bash tools/build_release.sh
```

脚本运行项目可用的单元测试聚合任务 `:app:test`、正式版 Lint 和签名构建。当前 AGP 配置只生成调试变体的单元测试任务；显示 `NO-SOURCE` 时不代表已有单元测试覆盖。正式版另以实际签名 APK 进行模拟器验收。正式签名缺失会阻止构建，不会退回调试证书或发布未签名 APK。

## R8 配置

正式变体同时开启 `isMinifyEnabled` 和 `isShrinkResources`，采用 `proguard-android-optimize.txt`，保留默认 Full Mode。项目使用 AGP 9.0.1 和随附的 R8，不为了发布升级工具链。

Android 默认优化规则已保留网页调用的 `@JavascriptInterface` 方法，不额外重复保留，也不保留整个业务或 AndroidX 包。正式版移除 Verbose、Debug、Info 日志，保留 Warning 和 Error 日志。

每次发布后，将以下文件复制到本地 `artifacts/releases/<版本>/` 并单独备份，尤其不能丢失与 APK 对应的 `mapping.txt`：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/mapping/release/mapping.txt`
- `app/build/outputs/mapping/release/configuration.txt`
- `app/build/outputs/mapping/release/usage.txt`

映射和完整构建记录仅作内部归档，不随面向用户的 APK 分发。

## 验收与发布

1. 使用 Android SDK Build Tools 的 `apksigner verify --verbose --print-certs` 校验 APK 签名，确认不是 Android Debug 证书。
2. 检查最终 Manifest 的版本、包名、最低 SDK 和 `debuggable=false`。
3. 安装同一份压缩签名 APK，验证实际出画、上下切台、OK 浮窗确认、5 秒无操作关闭以及加载浮层消失。
4. 调试签名与正式签名不同，使用独立模拟器验证，不能为了覆盖安装静默清除原设备数据。
5. 使用中文提交说明推送代码，创建与版本一致的 `v<版本>` 标签。
6. 创建非草稿、非预发布的 GitHub Release，上传 `TVLive-v<版本>.apk` 与 `SHA256SUMS`，设置为最新正式版。
7. 从 Release 下载 APK，对比 SHA-256 并再次核验签名。

官方参考：[R8 优化](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[应用签名](https://developer.android.com/studio/publish/app-signing)。
