# 小轻游 (TinyPlay) 🎮

专为安卓平板与手机深度定制的纯净网页游戏容器，是 [tiny-web-games](https://github.com/tiny-web-games) 组织开源小游戏的移动端游玩门户。

彻底解决移动端普通浏览器无法真正全屏、上下地址栏/底栏挤占可视面积、系统手势误触、长按意外触发识图、横竖屏频繁错乱以及弱网/离线无法游玩的痛点。

---

## ✨ 核心特性

- 🚀 **纯净轻量外壳**：APK 默认不捆绑庞大游戏静态包，安装包极致精简。
- 🌐 **GitHub 游戏大厅动态直连**：启动自动动态拉取 [tiny-web-games](https://github.com/tiny-web-games) 组织下的全部开源网页游戏，持续同步最新作品。
- ⚡ **双模畅玩（在线直玩 / 按需离线秒开）**：
  - 云端就绪：点击即可直接全屏在线畅玩。
  - 一键离线：支持按需一键下载解压至本地沙盒，断网/离线环境秒开游玩。
- 🖥️ **沉浸式全屏与硬件加速**：
  - 原生支持 Android Sticky Immersive Mode 全屏与刘海屏（Display Cutout）沉浸穿透。
  - 屏幕常亮保护，防止游戏过程中黑屏休眠。
  - Chromium 硬件加速，流畅运行各类 Canvas / WebGL / Phaser / PixiJS 游戏。
- 🔄 **横竖屏自适应与记忆**：
  - 悬浮球一键切换屏幕方向，并持久化记住每个游戏独立的横竖屏偏好。
- 🔍 **通用 Web 游戏地址栏**：
  - 顶部类现代浏览器胶囊输入栏，可输入任意在线 Web 游戏网址游玩，支持在游戏内一键离线保存收录至大厅。

---

## 🛠️ 技术栈

- **平台**：Android 原生 (Kotlin)
- **最低支持**：Android 7.0 (API Level 24)
- **核心组件**：
  - `AndroidX WebKit` & `WebViewAssetLoader`：解决离线静态资源加载与 CORS 跨域问题。
  - `Kotlin Coroutines`：异步拉取 GitHub API 与后台流式下载。
  - `GitHub Actions CI/CD`：全自动构建与 GitHub Release 发布。

---

## 📦 构建与发布

### 本地编译

```bash
# 编译 Release APK
./gradlew assembleRelease
```
产物位置：`app/build/outputs/apk/release/app-release.apk`

### 自动发布 Release
向仓库推送 `v*` 格式标签即可自动触发 GitHub Actions 编译并生成 Release：
```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## 📄 License
MIT License.
