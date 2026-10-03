# 项目总览（AI 可读版）

> **这份文档是写给 AI 助手看的。**
> 如果你想让 ChatGPT / Claude / Gemini 帮你改这个项目，把**这一整份**丢给它即可——
> 它自成一体，不依赖仓库里其它文件，也不需要对方知道 DSH 是什么。
>
> 最后更新：2026-10

---

## 0. 一句话概括

**DSH 安卓客户端**是一个约 1.15 MB 的原生 Android App（Java），
用系统 WebView 承载「DSH（DeepSeek Harness）编码智能体」自带的 Web 界面，
并在原生层补齐该网页在手机上缺失/不好用的四件事：
**后台任务通知、进程保活、原生图片选择、窄屏排版修正**。

它**不实现聊天逻辑**，聊天、流式输出、Markdown 渲染、会话管理全部由 DSH 自己的网页负责。
App 是外壳 + 补丁。

---

## 1. 背景：DSH 是什么

**DSH = DeepSeek Harness**，一个跑在**用户自己的电脑上**的编码智能体（类似 Claude Code / Codex CLI）。
它自带一个 Web 界面（默认监听 `127.0.0.1` 的某个端口），用户在浏览器里和它对话，
它能读写本机文件、执行命令。

和「云端聊天网站」的关键区别：

- **服务端在用户自己电脑上**，不在公网；
- **有状态**：会话、工作区、文件系统访问都在本机；
- **自带认证**：浏览器首次访问需要带一次性 token 的 URL，之后靠签名 Cookie；
- **有来源校验**：对 `/api` 请求做 Host / Origin 信任围栏，防止 DNS rebinding 与跨站请求。

所以「手机访问电脑上的 DSH」不是装个浏览器就行，要处理**网络通道 + 认证 + 围栏**三件事。

---

## 2. 要解决的四堵墙

在手机上直接用浏览器打开 DSH 的网址，会撞到：

| # | 问题 | 具体表现 |
|---|---|---|
| 1 | **拿不到通知** | 智能体跑完任务时人在别的 App，完全不知道，只能反复回来看 |
| 2 | **后台被杀** | 锁屏/切后台后 WebView 被系统冻结，WebSocket 断掉，回来要重连 |
| 3 | **传不了图** | DSH 网页端**没有 `<input type="file">`**，加图只支持**拖拽**和**粘贴**——手机上这两个交互都不存在 |
| 4 | **排版挤爆** | DSH 用 1024px 作侧边栏折叠阈值；设置面板是桌面式两列布局，在 448px 宽的手机上被压成一条缝（实测主内容只剩 **168px**），中文标签「一个字一行」 |

---

## 3. 技术栈与硬约束

| 项 | 值 | 说明 |
|---|---|---|
| 语言 | Java 17 | 不用 Kotlin，减少依赖 |
| 最低版本 | API 26（Android 8.0） | |
| 目标版本 | API 35（Android 15） | |
| 构建 | Android Gradle Plugin 8.7.3 + Gradle 8.11.1 | |
| 唯一依赖 | `androidx.webkit:webkit:1.12.1` | 只为用 `WebViewCompat.addDocumentStartJavaScript` |
| 包名 | `com.dsh.chat` | |
| 版本 | versionCode 3 / versionName "1.2" | |
| APK 体积 | ≈ 1.15 MB | |
| 原生部分 | 4 个 Java 文件，约 1000 行 | |

**为什么极简**：APK 越小、依赖越少，越不容易因为 DSH 更新而失效。
因为界面是 DSH 自己渲染的，**DSH 更新后 App 自动跟着更新，不需要重装**。

---

## 4. 系统架构

```
┌───────────────────────── 手机（Android） ─────────────────────────┐
│                                                                    │
│   DSH 安卓客户端  com.dsh.chat                                      │
│   ┌──────────────────────────────────────────────────────────┐    │
│   │ MainActivity                                             │    │
│   │  · WebView（承载 DSH 的网页）                             │    │
│   │  · 进度条 / 错误横幅                                      │    │
│   │  · 返回键菜单（重载 / 设置 / 宽屏 / 热更新 / 退出）        │    │
│   │  · 文件选择器回调、下载管理器、Cookie 管理                 │    │
│   │  · 缺省地址与扩展来源配置（SharedPreferences）             │    │
│   └───────┬───────────────────────────────┬──────────────────┘    │
│           │ 注入 JS                        │ JS 桥回调              │
│           ▼                                │                       │
│   ┌──────────────────┐          ┌──────────┴──────────────┐        │
│   │ JsHook.SCRIPT    │          │ @JavascriptInterface    │        │
│   │ (document-start) │          │  DSHNative.onTaskState  │        │
│   │  · 宽屏视口覆盖   │          │  DSHNative.isWideMode   │        │
│   │  · WebSocket 钩子 │          └──────────┬──────────────┘        │
│   └──────────────────┘                     │                       │
│                                            ▼                       │
│   ┌────────────────────┐        ┌──────────────────────┐           │
│   │ KeepAliveService   │◀──────▶│ 任务运行中？起停前台服务│           │
│   │ (前台服务)          │        └──────────────────────┘           │
│   └────────────────────┘                     │                    │
│   ┌────────────────────┐                     ▼                    │
│   │ Notifications      │◀──── 任务完成 ── 发通知（HIGH，可震动）    │
│   └────────────────────┘                                          │
└──────────────────────────────────┬─────────────────────────────────┘
                                   │ HTTPS / WS
                    反向代理 · 内网穿透 · VPN（三选一）
                                   │
┌──────────────────────────────────┴─────────────────────────────────┐
│  电脑：DSH Web 服务                                                  │
│    · 信任围栏：校验 Host ∈ {loopback, LAN 字面量, trustedHosts}       │
│                且 Origin == Host，拒绝 sec-fetch-site: cross-site    │
│    · 认证：一次性 token（URL 查询参数）→ 换 HMAC 签名 Cookie（30 天）  │
│    · 静态托管 dsh-ext.css / dsh-ext.js（供客户端热更新）              │
│    · 自己的前端：聊天界面、会话管理、Markdown、WebSocket 事件流        │
└────────────────────────────────────────────────────────────────────┘
```

---

## 5. 代码地图

```
app/src/main/java/com/dsh/chat/
├── MainActivity.java      约 700 行，主体
├── JsHook.java            约 75 行，文档启动注入的 JS（以 Java 字符串常量保存）
├── Notifications.java     约 90 行，通知渠道与构造
└── KeepAliveService.java  约 65 行，前台服务

web-ext/
├── dsh-ext.css            界面修正样式（热更新，独立于 APK）
├── dsh-ext.js             界面修正脚本（热更新，独立于 APK）
│                          含：🖼 图片按钮、🖥 computer use 快捷按钮、
│                              自动关欢迎弹窗、侧边栏状态标记
└── download-page.html     APK 下载页模板
```

### MainActivity.java 职责

| 区块 | 做什么 |
|---|---|
| `onCreate` | 建 WebView + 进度条 + 错误横幅；建通知渠道；配置 WebView；拉取远程扩展；载入地址或弹设置框 |
| `configureWebView` | JS/DOM/缩放/文件访问等设置；注册 JS 桥；装 `WebViewClient`、`WebChromeClient`、`DownloadListener` |
| `installTaskHook` | 用 `WebViewCompat.addDocumentStartJavaScript(webView, JsHook.SCRIPT, {"*"})` 在**页面脚本之前**注入；不支持时回退到 `onPageStarted` 注入 |
| `onConfigurationChanged` / `applyOrientationLayout` | 横竖屏：不重建 Activity（Manifest 里声明了 `configChanges`），横屏隐藏状态栏、竖屏浅色状态栏 |
| `Bridge`（内部类） | `@JavascriptInterface`：`onTaskState(sid, state)` 由网页回调；`isWideMode()` 供注入脚本查询 |
| `refreshRemoteExt` / `httpGetString` / `injectExt` | 后台拉取 `dsh-ext.css/js`，内容变化时**立即注入当前页面**（避免要重启两次） |
| `handleTaskState` / `startKeepAlive` / `stopKeepAlive` | 维护 `runningSessions` 集合，据此起停前台服务、发通知 |
| `showUrlDialog` / `showAuthPrompt` / `showNotifyDialog` | 三个 AlertDialog：填地址、登录失效、通知开关 |
| `onBackPressed` | 优先页面内后退；退无可退时弹功能菜单 |

---

## 6. 三条关键链路（重点，改代码前必读）

### 6.1 任务完成通知

**不依赖 DSH 的任何私有 API，只旁听它自己的事件流。**

DSH 的网页通过一条 WebSocket 接收会话事件。其中有一种事件形如：

```json
{
  "type": "item",
  "value": {
    "event": "api-session/status",
    "args": ["<sessionId>", true]
  }
}
```

`args[1]` 为 `true` 表示该会话**本轮开始运行**，`false` 表示**本轮结束**。

`JsHook.SCRIPT` 在页面脚本执行之前运行，把 `window.WebSocket` 替换成包装类：

```js
var Native = window.WebSocket;
var Hooked = function (url, protocols) {
  var ws = (protocols === undefined) ? new Native(url) : new Native(url, protocols);
  ws.addEventListener('message', function (ev) {
    if (typeof ev.data === 'string') handle(ev.data);   // 解析并识别 api-session/status
  });
  return ws;
};
Hooked.prototype = Native.prototype;
// 复制 CONNECTING/OPEN/CLOSING/CLOSED 常量
window.WebSocket = Hooked;
```

识别到 `true → false` 的跳变后，调用 `window.DSHNative.onTaskState(sid, 'done')`
（`@JavascriptInterface` 桥）回到原生层。

原生层 `handleTaskState` 的逻辑：

```
收到 "start" → runningSessions.add(sid)；启动前台服务（此时 App 在前台，
              不受 Android 12+「后台启动前台服务」限制）
收到 "done"  → runningSessions.remove(sid)；若集合空则停服务
              若「通知已开启」且「App 不在前台」→ 发通知
              2.5 秒内多个会话同时结束只提醒一次（防轰炸）
```

**为什么要在 document-start 注入**：WebSocket 可能在页面加载很早的时候就建好，
如果等到 `onPageFinished` 才注入，会漏掉连接，钩子就废了。

**为什么要在任务开始时才起前台服务**：Android 12+ 禁止 App 在后台启动前台服务。
而任务通常是用户在前台发起的，那时启动是合法的。

### 6.2 手机上传图片

DSH 网页端**没有 `<input type="file">`**，它在 `document` 上监听 `drop` 事件
（内部 `fileTransfer()` 要求 `dataTransfer.types` 包含 `"Files"`）。

所以补丁的做法是**合成一个 drop 事件**，等价于桌面拖拽：

```
点击悬浮 🖼 按钮
  → 触发隐藏的 <input type=file accept="image/png,image/jpeg,image/webp,image/gif" multiple>
  → 安卓原生文件选择器（由 MainActivity.onShowFileChooser 承接）
  → 把选中的文件读成 File 对象
  → new DataTransfer() + dt.items.add(file)
  → document.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true }))
  → DSH 自己的图片投喂管线
```

按钮可**长按拖动**，位置存 `localStorage`（键 `dsh-attach-pos`），默认右下角。

**DSH 自身限制**：格式限 `png/jpeg/webp/gif`，单张 ≤ 5MB，每条消息最多 20 张。

### 6.3 界面修正热更新

原生代码改动要重新编译安装；**网页层修正不需要**。

App 启动时后台拉取两个文件（`extBase()` 决定来源）：

```
<extBase>/dsh-ext.css
<extBase>/dsh-ext.js
```

- 默认 `extBase` = `https://<当前服务器主机>:8443/`（可用 `ext_base` 覆盖）
- 拉到的内容存 `SharedPreferences`（`ext_css` / `ext_js`）
- 页面 `onPageFinished` 时注入：CSS 塞进 `<style id="dsh-ext-css">`，JS 直接 `evaluateJavascript`
- **内容有变化时立即注入当前页面**（早期版本要重启两次才生效，已修）
- App 内 `返回键 → 刷新界面修正（热更新）` 可手动触发

---

## 7. 窄屏排版修正（最绕的一块）

### 问题

DSH 用 **1024px** 作为侧边栏自动折叠阈值。手机上侧边栏默认收成 56px 图标条；
手动展开后，DSH 会在网格里给它 280px，**主内容被压到 168px**（在 448px 屏幕上）。

### 做法

把侧边栏从网格流里摘出来，改成 `position: fixed` 浮层（抽屉）。

### 两个必须同时处理的点（踩过两次）

**① 列数必须一起改。**
侧边栏一旦 `fixed` 就脱离网格流，剩下的子元素会**依次落到第 1、2 列**。
如果只把侧边栏 `fixed` 而不改列定义，主内容会掉进为空的那一列，宽度直接变 0 或被挤扁。

**② 状态怎么拿到——不同 DSH 版本不一样。**

| DSH 版本 | 折叠状态在哪 |
|---|---|
| 较早 | `<frame>` 上的 `data-sidebar-collapsed` / `data-details-collapsed` 属性 |
| 较新 | 属性被移除，只体现在 **frame 行内 style 的 grid-template-columns** 上 |

新版本的行内值形如：

```
展开：grid-template-columns: 280px minmax(0px, 1fr) minmax(0px, 0px)
收起：grid-template-columns:  56px minmax(0px, 1fr) minmax(0px, 0px)
                ↑ 用 100px 作阈值        ↑ 0px 表示详情栏未打开
```

**纯 CSS 读不到行内值**，所以 `dsh-ext.js` 里用定时器观察该属性，把结果写成
`<html>` 上的两个 class：

```html
<html class="dsh-sb-open dsh-dt-open">
```

CSS 再据此布局：

```css
@media (max-width: 1023px) {
  /* 侧栏展开 + 详情收起：主内容独占整宽 */
  html.dsh-sb-open [class*="_frame"] { grid-template-columns: minmax(0, 1fr) !important; }
  /* 侧栏展开 + 详情也开：各占一半 */
  html.dsh-sb-open.dsh-dt-open [class*="_frame"] { grid-template-columns: minmax(0,1fr) minmax(0,1fr) !important; }
  /* 侧栏本体变浮层 */
  html.dsh-sb-open [class*="_frame"] > [class*="_sidebarCol"] {
    position: fixed !important; left: 0; top: 0; bottom: 0;
    width: min(82vw, 320px) !important; z-index: 60;
    box-shadow: 6px 0 28px rgba(0,0,0,.22);
  }
}
```

**实测效果**（448px 宽屏幕）：

| 状态 | 侧边栏 | 主内容 |
|---|---|---|
| 收起 | 56px 常规栏 | 392px（原生表现） |
| 展开（未修正） | 280px 占位 | **168px 被挤扁** ❌ |
| 展开（修正后） | 320px 浮层 | **448px 满宽** ✅ |

**关于类名**：DSH 的 CSS Modules 会生成带哈希前缀的类名（如 `pI_x6G_frame`、
`BynINW_frame`），哈希每次构建都会变。所以选择器一律用 `[class*="_frame"]`
这种**子串匹配**，而不是写死完整类名。

---

## 8. 与 DSH 服务端交互时要知道的事

### 8.1 信任围栏（Host / Origin）

DSH 对 `/api/*` 请求做来源校验：

- `Host` 必须是 **loopback**、**局域网 IP 字面量**，或**显式声明的 `trustedHosts`**；
- 若带 `Origin`，必须与 `Host` 相等；
- `sec-fetch-site: cross-site` 一律拒绝。

**失败返回 403**（不是 401）。**通过围栏但未认证才返回 401。**

> 排查口诀：**403 = 围栏挡住了**（去配 `trustedHosts`）；
> **401 = 围栏放行了但没登录**（去搞认证）。

反向代理必须**保留原始 Host 头**，否则围栏会拒绝。

### 8.2 认证

1. 服务启动时生成一次性 **launch token**，打印在启动日志里；
2. 浏览器访问 `/?token=<token>` → 服务端下发 **HttpOnly Cookie** 并 303 跳到干净 URL；
3. Cookie 名 `dsh-auth-<base64url(sha256(authority))>`，值 `v1.<body>.<HMAC-SHA256 签名>`，
   payload 含 `{version, authority, issuedAt, expiresAt}`，默认 **30 天**；
4. **签名密钥是全局共享的**（存在 DSH 家目录的凭据文件里），
   **Cookie 绑定的是 authority（域名:端口），不是进程**。
   → 因此同一域名下**换实例、换端口，原 Cookie 依然有效**。
5. **launch token 是每个进程随机生成的，且只在内存里、不落盘**。
   → 所以「重装 App 清掉了 Cookie」时，如果连的是**桌面实例**，
   就**没有任何办法**从界面上重新登录（桌面窗口走 `dsh-app://` 私有协议，压根不使用这个 token）。
   只有 `dsh web` 实例能把 token 从启动日志里捞回来。
   完整的机制说明、失效场景、三种恢复手法，以及 ADB 无线调试（含二维码配对）的排查流程，
   见 [05-auth-and-recovery.md](05-auth-and-recovery.md)。

### 8.3 前端插件加载

较新版本会把几十个前端插件**合并成一个极长的合并 URL**：

```
/plugins/??插件A/client.js,插件B/client.js,...(可能 50+ 个)&rev=<hash>
```

这个响应可能有 **10 MB 量级**。经高延迟中继链路（如 200ms 往返）首屏可能耗时 1~2 分钟。
客户端侧无法消除，只能依赖 WebView 缓存。

---

## 9. 常见改动指引（"想改 X，去哪改"）

| 想做什么 | 改哪里 |
|---|---|
| 换默认服务器地址 | `MainActivity.DEFAULT_URL` |
| 换热更新文件来源 | `MainActivity.DEFAULT_EXT_BASE`，或 `ext_base` 偏好 |
| 改通知文案/渠道 | `Notifications.java` |
| 改任务状态识别规则（事件名变了） | `JsHook.SCRIPT` 里的 `handle()` |
| 改宽屏视口宽度 | `JsHook.WIDE_VIEWPORT_WIDTH`（默认 760） |
| 改返回键菜单项 | `MainActivity.onBackPressed` |
| 改图片按钮的样式/位置/可选格式 | `web-ext/dsh-ext.js`（不用重装 APK） |
| 改 computer use 快捷按钮的菜单项 | `web-ext/dsh-ext.js` 里的 `ACTIONS` 数组（不用重装 APK） |
| 改窄屏抽屉布局 | `web-ext/dsh-ext.css` + `dsh-ext.js` 里的 `sync()`（不用重装 APK） |
| 改图片地址改写规则 / 长按保存菜单 | `web-ext/dsh-ext.js` 里的 `__dshImg` 那一段（不用重装 APK） |
| 加一个新的握手/权限 | `AndroidManifest.xml` + `MainActivity` |

**改完网页层文件后**，上传到 `extBase`，然后在 App 里按
`返回键 → 刷新界面修正（热更新）`；**改完 Java 层必须重新构建安装**。

---

## 10. 已知限制与设计取舍

| 限制 | 原因 | 能否解决 |
|---|---|---|
| 首次加载慢（1~2 分钟） | 服务端把几十个前端插件合并成一个 ~10MB 响应，高延迟链路下很慢 | WebView 缓存后变快；客户端侧无解 |
| 设置不持久 | DSH 对非回环地址的浏览器用「内存态」设置作用域 | DSH 设计，客户端无解 |
| 原生改动要重装 | 通知/服务/权限在 Java 层 | 只有网页层能热更新 |
| 宽屏模式是缩放 | 强制布局视口后整体等比缩小，字略小 | 可配合双指缩放 |
| 依赖事件名 | 任务通知监听 `api-session/status` | DSH 改名则同步更新 `JsHook` |
| 无法自动登录 | 需要用户粘贴带 token 的 URL | 安全取舍：不硬编码凭据 |

**刻意的设计取舍**：

- **不实现自己的聊天 UI**，只做外壳。这样 DSH 更新后 App 自动受益，维护成本极低。
- **不逆向 DSH 的私有 API**，只旁听它自己前端也在用的事件流。
- **网页层修正走热更新**，把「需要重装」的范围压到最小。
- **不内置任何凭据**，首次使用由用户粘贴地址。

---

## 11. 给 AI 助手的工作提示

如果你要基于这份文档帮用户改代码，请注意：

1. **先分清改的是「原生层」还是「网页层」**。
   网页层（`web-ext/`）可以热更新，改完无需重装 APK，迭代成本极低；
   原生层（`app/src/main/java/`）必须重新构建安装。

2. **不要写死 DSH 的 CSS 类名**。它们带哈希前缀且每次构建都变，
   一律用 `[class*="_xxx"]` 子串匹配。

3. **不要假设 DSH 的属性存在**。同一份逻辑要兼容「有 data 属性」和「只有行内 style」两种版本。

4. **改 CSS 布局时注意 `position: fixed` 的副作用**：元素脱离网格流后，
   后面的兄弟元素会前移占位，列定义必须一起改。

5. **注入脚本必须幂等**。`document-start` 脚本可能被注入多次，
   所有初始化都要有 `if (window.__xxxInstalled) return;` 之类的 guard。

6. **不要引入新的第三方依赖**。整个 App 只有一个 `androidx.webkit`，
   保持轻量是这个项目的核心价值。

7. **通知功能依赖事件名 `api-session/status`**。如果用户在 DSH 更新后发现通知失效，
   第一件事是让他确认这个事件名是否还在。

8. **回答用户时用简体中文**，少用术语和路径，需要他操作时直接说「点哪里、填什么」。

---

## 12. 术语对照

| 术语 | 含义 |
|---|---|
| **DSH** | DeepSeek Harness，跑在用户电脑上的编码智能体 |
| **Funnel / 内网穿透** | 把本机端口的服务暴露到公网的手段（如 Tailscale Funnel、Cloudflare Tunnel、frp） |
| **信任围栏** | DSH 对 `/api` 的 Host/Origin 来源校验，失败返回 403 |
| **launch token** | DSH 启动时生成的一次性登录令牌，随进程重启变化 |
| **document-start 注入** | 在网页自身脚本执行**之前**注入 JS，用于抢在 WebSocket 创建前挂钩 |
| **热更新** | 只更新服务器上的 CSS/JS，App 拉取后注入，无需重装 APK |
| **抽屉浮层** | 窄屏下把侧边栏改成浮在主内容之上的固定定位面板 |
