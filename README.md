# DSH 安卓客户端

把电脑上跑的 **DSH（DeepSeek Harness）** 智能体，变成一个装在手机上的原生 App。

它不是一个独立的应用——它是一层**原生外壳**，用系统 WebView 承载 DSH 自己的 Web 界面，
再补上 DSH 网页端在手机上缺失或不好用的部分：**后台任务通知、横竖屏适配、图片上传、窄屏排版**。

> 唯一依赖是 `androidx.webkit`，**APK 约 1.15 MB**。
> DSH 本身更新后，App 自动跟着更新，**不需要重装**。

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [功能](#功能)
- [它是怎么工作的](#它是怎么工作的)
- [快速开始](#快速开始)
- [配置](#配置)
- [界面修正热更新](#界面修正热更新)
- [已知限制](#已知限制)
- [安全须知](#安全须知)
- [目录结构](#目录结构)
- [开发与构建](#开发与构建)
- [常见问题](#常见问题)
- [文档](#文档)
- [许可证](#许可证)

---

## 它解决什么问题

DSH 是一个跑在电脑上的编码智能体，自带 Web 界面。理论上手机浏览器直接打开那个网址就能用，
但在手机上实际会撞到四堵墙：

| 墙 | 具体表现 |
|---|---|
| **拿不到通知** | 智能体跑完任务时人在别的 App 里，完全不知道，只能反复回来看 |
| **后台被杀** | 手机锁屏/切后台后 WebView 被冻结，WebSocket 断掉，回来还得重连 |
| **传不了图** | DSH 网页端**没有 `<input type="file">`**，加图只支持**拖拽**和**粘贴**——手机上这两个交互都不存在 |
| **排版挤爆** | DSH 用 1024px 作侧边栏折叠阈值、设置面板是桌面式两列布局，在 400px 宽的手机上被压成一条缝，中文标签「一个字一行」 |

这个 App 逐条把它们补上了。

## 功能

| 功能 | 说明 |
|---|---|
| **任务完成通知** | 挂钩 DSH 自己的 WebSocket 事件流，智能体跑完就通知你（可震动） |
| **后台保活** | 任务运行期间启用前台服务，防止进程被系统冻结，切到后台也能收到提醒 |
| **横竖屏适配** | 旋转不重载页面（保留会话）；横屏自动隐藏状态栏争取纵向空间；适配刘海屏 |
| **图片上传按钮** | 补一个可拖动的悬浮按钮 → 调起安卓原生文件选择器 → 合成 `drop` 事件喂给 DSH 自己的投喂管线 |
| **computer use 快捷按钮** | 宿主侧 computer-use 插件没有界面；补一个 🖥 悬浮按钮 → 菜单 → 一键让智能体截屏 / 列窗口 / 打字 / 点击 |
| **界面修正热更新** | 样式/脚本放在服务器上，改完**不用重装 APK** |
| **窄屏排版修正** | 侧边栏展开时改成「抽屉浮层」，主内容保持满宽（见 [界面修正热更新](#界面修正热更新)） |
| **多会话 / 流式输出 / Markdown / 代码高亮** | 由 DSH 界面本身提供 |
| **文件下载** | 走系统下载管理器 |
| **双指缩放** | 任何界面都能放大缩小 |
| **地址可配置** | 首次启动填地址即可，支持带认证 token 的 URL 自动登录 |
| **全屏沉浸** | 没有浏览器地址栏，就是一个 App |

### 截图

> 📷 仓库里没有放截图——截图会暴露使用者的会话标题。
> 建议你自己跑起来后截 3 张放到 `docs/images/`：`chat.png`（对话）、`drawer.png`（侧边栏抽屉）、`notify.png`（通知），
> 然后在上面这张表下面插入：
>
> ```markdown
> | 对话 | 侧边栏 | 通知 |
> |---|---|---|
> | ![](docs/images/chat.png) | ![](docs/images/drawer.png) | ![](docs/images/notify.png) |
> ```

## 它是怎么工作的

```
┌───────────────────────────── 手机 ─────────────────────────────┐
│  DSH 安卓客户端 (com.dsh.chat)                                  │
│                                                                │
│   ┌────────────┐   document-start JS   ┌───────────────────┐   │
│   │ JsHook     │──────────────────────▶│                   │   │
│   │ 视口覆盖    │                       │   WebView         │   │
│   │ WebSocket  │   ◀── DSHNative ────  │   (DSH 自己的网页) │   │
│   │ 钩子        │      JS 桥接口        │                   │   │
│   └────────────┘                       └─────────┬─────────┘   │
│                                                  │             │
│  KeepAliveService（前台服务）◀── 任务开始/结束事件 ─┘             │
│  Notifications（通知渠道）                                      │
└──────────────────────────────────┬─────────────────────────────┘
                                   │ HTTPS
                        反向代理 / 内网穿透
                                   │
┌──────────────────────────────────┴─────────────────────────────┐
│  电脑：DSH Web 服务 (127.0.0.1:PORT)                            │
│    · 信任围栏校验 Host / Origin                                 │
│    · HMAC 签名 Cookie 认证                                      │
│    · 静态托管 dsh-ext.css / dsh-ext.js（热更新用）               │
└────────────────────────────────────────────────────────────────┘
```

三条关键链路：

**1. 任务通知（不碰 DSH 内部，只旁听它自己的事件流）**

DSH 的 Web 界面通过一条 WebSocket 接收会话事件，其中 `api-session/status`
携带 `[sessionId, boolean]`，`true` = 本轮开始运行，`false` = 本轮结束。

App 在**页面脚本执行之前**（`WebViewCompat.addDocumentStartJavaScript`）注入一小段脚本，
把 `window.WebSocket` 换成一个包装类，旁听所有收到的消息。
一旦识别到 `true → false` 的跳变，就通过 `@JavascriptInterface` 桥（`DSHNative.onTaskState`）
回调到原生层，原生层据此起停前台服务并发通知。

这样做的好处：**不依赖 DSH 的任何私有 API**，DSH 内部怎么改，只要事件名不变就一直有效；
而且脚本在 WebSocket 创建之前就装好了，不会漏掉连接。

**2. 图片上传（复用 DSH 已有的投喂管线）**

DSH 网页端在 `document` 上监听 `drop` 事件。所以只要**合成一个带 `DataTransfer` 的 `drop` 事件**，
就等价于桌面上的拖拽。App 注入的脚本补一个悬浮按钮：

```
点击 🖼  →  隐藏的 <input type=file>  →  安卓原生选择器  →  读成 File 对象
                                            ↓
                                  构造 DataTransfer，dispatch('drop')
                                            ↓
                              DSH 自己的图片投喂管线（不碰它的内部实现）
```

按钮可长按拖动，位置存在 `localStorage`。

**3. 界面修正热更新（改样式不用重装 App）**

原生代码一旦改动就要重新编译安装，但**网页层的修正不需要**。所以 App 启动时会在后台拉取：

```
<服务器>:8443/dsh-ext.css
<服务器>:8443/dsh-ext.js
```

缓存到 `SharedPreferences`，页面加载完成后注入。内容有变化时**立即注入当前页面**，
不用重启两次。改完在 App 里按 `返回键 → 刷新界面修正（热更新）` 即可生效。

## 快速开始

### 你需要准备的

1. 电脑上已装好并运行 DSH（自带 Web 服务）。
2. 一个让手机能访问到那个 Web 服务的通道。常见三种：
   - **同一个 Wi-Fi**：直接访问 `http://<电脑局域网IP>:<端口>`；
   - **反向代理 / 内网穿透**（Cloudflare Tunnel、frp、Tailscale Funnel……）；
   - **VPN**（WireGuard / Tailscale，手机加入同一张网）。

   > ⚠️ DSH 的 `/api` 有 **Host / Origin 信任围栏**。用域名访问时，必须把该域名加进
   > DSH 的 `trustedHosts`，否则 `/api` 会返回 **403**（而不是 401）。
   > 详见 [docs/04-deployment.md](docs/04-deployment.md)。

### 构建安装

```powershell
# 1. 装工具链（已装过就跳过）
.\scripts\setup-toolchain.ps1

# 2. 构建 APK
.\scripts\build-apk.ps1
# 产物：app\build\outputs\apk\debug\app-debug.apk

# 3. 装到手机（USB 调试已开启时）
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

也可以把 APK 放到一个静态目录里让手机直接下载：

```powershell
.\scripts\publish-apk.ps1     # 生成发布目录（APK + 下载页 + 热更新文件）
python -m http.server 8099 --directory <发布目录>
```

### 首次使用

1. 打开 App，会弹「连接 DSH」对话框。
2. 粘贴 DSH 的访问地址。**如果启用了 token 认证，请粘贴带 token 的完整链接**
   （形如 `https://your-host/?token=xxxx`），认证成功后会自动跳转并写入 Cookie。
3. 之后就能直接用了。

## 配置

全部配置都在 App 内完成，存在 `SharedPreferences`（`dsh_prefs`）：

| 键 | 含义 | 默认 |
|---|---|---|
| `server_url` | DSH 访问地址 | 空（首次启动弹窗填写） |
| `notify_enabled` | 任务完成通知开关 | `true` |
| `wide_mode` | 宽屏模式（强制布局视口 760px） | `false` |
| `ext_base` | 界面修正文件来源 | 空 → 自动取 `https://<当前主机>:8443/` |
| `ext_css` / `ext_js` | 缓存的界面修正内容 | 拉取后缓存 |

想预置默认地址，改 `MainActivity.java` 里的常量：

```java
private static final String DEFAULT_URL = "";       // 例如 "https://your-host/"
private static final String DEFAULT_EXT_BASE = "";  // 例如 "https://your-host:8443/"
```

### App 内操作

**返回键**：优先页面内后退；退无可退时弹出菜单：

```
重新加载
设置 / 任务提醒
宽屏模式开关
刷新界面修正（热更新，无需重装）
退出
```

## 界面修正热更新

`web-ext/` 下两个文件就是全部「补丁」，它们是**独立于 APK 的**——改完上传，手机端刷新即可。

| 文件 | 作用 |
|---|---|
| `dsh-ext.css` | 窄屏侧边栏改抽屉浮层、中文标签不逐字断行 |
| `dsh-ext.js` | 🖼 图片上传悬浮按钮、🖥 computer use 快捷按钮、自动关闭欢迎弹窗、侧边栏状态标记 |

### 一个具体的坑：窄屏侧边栏

DSH 以 1024px 为侧边栏自动折叠阈值。手机上侧边栏默认收成图标条；
手动展开后它会**把主内容挤成一条窄缝**（实测 448px 屏幕上只剩 **168px**）。

**做法**：把侧边栏从网格里摘出来改成 `position: fixed` 浮层。

**这里有两个必须同时处理的地方**，否则主内容会更糟：

1. 侧边栏一旦 `fixed` 就脱离网格流，剩下的元素会**依次落到第 1、2 列**，
   所以网格列数必须同时改掉——否则主内容会掉进为空的那一列，宽度直接变 0 或被压扁。
2. 状态怎么拿：DSH **不同版本暴露折叠状态的方式不一样**。
   较早版本输出 `data-sidebar-collapsed` / `data-details-collapsed` 属性；
   较新版本去掉了这两个属性，只把列宽写在 frame 的**行内 style** 里：

   ```
   展开  grid-template-columns: 280px minmax(0px, 1fr) minmax(0px, 0px)
   收起  grid-template-columns:  56px minmax(0px, 1fr) minmax(0px, 0px)
   ```

   纯 CSS 读不到行内值，所以 `dsh-ext.js` 里用脚本观察该属性，
   把结果写成 `<html>` 上的 `dsh-sb-open` / `dsh-dt-open` 两个 class，
   CSS 再据此布局。这样**两种版本都能兼容**。

改完记得在 App 里点一次「刷新界面修正（热更新）」。

### computer use 快捷按钮

DSH 的 computer-use 类插件是**纯宿主插件**（只 `inject: ['tools']` 注册模型工具），
**没有任何界面**——所以在 DSH 的「插件」页里看不到它，这是正常的，不是没装上。
它唯一的存在形式是**工具调用**。

这个按钮就是给它补一个入口：右下角一个绿色的 🖥 悬浮按钮（可拖动、位置记忆），
点开是一组一键动作：

```
📷  截图看看         → 用 screen_capture 截一张屏
🪟  开了哪些窗口      → 用 window 列出当前窗口
⌨️  帮我打字…         → 展开输入框，填完发「在 xx 里输入这段文字」
🖱️  点一下…           → 展开输入框，填坐标，发「在 (x,y) 点击」
💬  自定义指令…       → 展开输入框，直接把你的话发出去
```

选完动作后，脚本会**把对应指令送进输入框并自动发送**，剩下的由智能体执行。

**它不直接遥控电脑**——它只是把「让智能体去做」压缩成一次点击，
真正干活的是宿主侧插件的工具。

**一个实现上的坑**（已解）：DSH 的输入框是 **Lexical**，它异步重渲染，
所以同步读 `innerText` 永远是空的；而且 `document.execCommand('insertText')` 时灵时不灵。
最终方案是**合成一个 `paste` 事件**（Lexical 一定会处理），延迟后再确认文字是否真的进去，
没进去才退回 `execCommand`。

## 已知限制

| 限制 | 原因 | 能否解决 |
|---|---|---|
| **首次加载慢** | DSH 较新版本会把几十个前端插件合并成**一个约 10 MB 的响应**；经中继链路（≈200ms 往返）首屏可能要 1~2 分钟 | WebView 缓存后再次打开就快了；无法从客户端侧消除 |
| **设置不持久** | DSH 对**非回环地址**的浏览器使用「内存态」设置作用域，主题/字号/面板开关刷新后回默认 | DSH 的设计，App 侧无法改 |
| **改了原生代码要重装** | 通知、前台服务、权限、横竖屏逻辑都在 Java 层 | 只有网页层修正能热更新 |
| **宽屏模式是缩放** | 强制布局视口 760px 让 DSH 按桌面排版渲染再等比缩小，字会略小 | 可配合双指缩放 |
| **依赖 DSH 的事件名** | 任务通知监听 `api-session/status` | 若 DSH 改名，需同步更新 `JsHook.java` |

## 安全须知

- **带 `token=` 的链接等同于钥匙。** 拿到它的人可以读写你电脑上的文件、执行命令。
  不要外传、不要截图分享。
- App 里的登录态是 **HttpOnly Cookie**，用共享密钥签名，默认 **30 天**有效。
  想立即作废所有客户端登录态：删掉 DSH 服务端凭据里的浏览器会话记录并重启服务。
- 如果公开了服务地址，**务必确认认证是开着的**。
  自测方法：不带 Cookie 请求 `/api/gateway`，应当返回 **401**；
  返回 200 说明认证没生效。
- 仓库里**不含任何真实的服务器地址、token 或本机路径**，全部是占位符。

## 目录结构

```
.
├── app/                              Android 应用模块
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dsh/chat/
│       │   ├── MainActivity.java     主体：WebView、文件选择、下载、返回键、横竖屏、热更新拉取
│       │   ├── JsHook.java           文档启动注入脚本（宽屏视口 + WebSocket 任务状态钩子）
│       │   ├── Notifications.java    通知渠道与通知构造
│       │   └── KeepAliveService.java 前台保活服务
│       └── res/                      图标、主题、颜色、文案
├── web-ext/                          界面修正（热更新，独立于 APK）
│   ├── dsh-ext.css                   窄屏抽屉布局、中文断行修正
│   ├── dsh-ext.js                    图片按钮、computer use 按钮、自动关弹窗、侧边栏状态标记
│   └── download-page.html            APK 下载页模板
├── scripts/
│   ├── setup-toolchain.ps1           一次性安装 JDK/SDK/Gradle
│   ├── build-apk.ps1                 构建 APK
│   └── publish-apk.ps1               整理发布目录
├── docs/                             详细文档
├── build.gradle / settings.gradle / gradle.properties
└── CHANGELOG.md
```

## 开发与构建

| 项 | 值 |
|---|---|
| 语言 | Java 17 |
| 最低版本 | Android 8.0（API 26） |
| 目标版本 | Android 15（API 35） |
| 构建插件 | Android Gradle Plugin 8.7.3 |
| Gradle | 8.11.1 |
| 依赖 | `androidx.webkit:webkit:1.12.1` |

```
包名        com.dsh.chat
versionCode 3
versionName 1.2
```

`local.properties` 里的 `sdk.dir` 指向你自己的 Android SDK，**已被 .gitignore 忽略**，
克隆后自行创建或让 Gradle 自动生成。

## 常见问题

**Q：App 显示「网络连接失败」？**
地址填错、服务没开、或手机不在同一网络。先用手机浏览器打开同一个地址确认能通。

**Q：一直显示需要登录 / 反复弹登录框？**
认证 Cookie 过期（默认 30 天），或者服务端重启后 token 变了但 Cookie 失效了。
重新在「设置」里粘贴带 token 的完整链接即可。

**Q：收不到任务完成通知？**
1. 首次启动时是否授予了通知权限；
2. `返回键 → 设置 / 任务提醒` 里开关是否打开（可发测试通知）；
3. 只在**你不在 App 内**时才提醒（正看着就不打扰）；
4. 系统电池优化可能杀掉后台，把 App 加入「不受限制」。

**Q：设置面板还是看不全？**
按 `返回键 → 开启宽屏模式`，让 DSH 按桌面布局渲染再缩放。

**Q：手机加了图但没发出去？**
DSH 自身限制：格式限 `png / jpeg / webp / gif`，**单张 ≤ 5MB**，每条消息最多 20 张。

**Q：改了 `dsh-ext.css` 怎么不生效？**
文件要能被 App 拉到（默认 `https://<服务器主机>:8443/`），
然后在 App 里按 `返回键 → 刷新界面修正（热更新）`。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/01-overview-ai.md](docs/01-overview-ai.md) | **自包含的完整项目说明**，写给 AI 助手看的（想用 ChatGPT/Claude 帮忙改这个项目，把这份整个丢给它） |
| [docs/02-integration.md](docs/02-integration.md) | 深入讲解：WebSocket 事件钩子、信任围栏、Cookie 认证机制、热更新链路 |
| [docs/03-pitfalls.md](docs/03-pitfalls.md) | 开发过程中真实踩过的坑与排查手法 |
| [docs/04-deployment.md](docs/04-deployment.md) | 把电脑上的 DSH 暴露给手机（含信任围栏配置、认证机制、验证方法） |

## 许可证

[MIT](LICENSE)

> 本仓库与 DeepSeek 官方无关，是一个第三方客户端。
