# DSH 集成原理

这篇讲清楚 App 和 DSH 之间的四条接口，以及每一条为什么这么设计。
读完应该能独立判断：DSH 升级后哪部分会坏、怎么修。

---

## 1. 为什么要「集成」而不是「调用 API」

DSH 没有给第三方客户端预留 API。它自带 Web 界面，界面通过一条 WebSocket 和本机服务通信。

我们有两个选择：

| 方案 | 优点 | 缺点 |
|---|---|---|
| A. 逆向它的 HTTP / WS 协议，自己实现客户端 | 控制力强 | DSH 一升级就可能全废，维护成本极高 |
| B. **用 WebView 承载它自己的界面，只在外围做加法** | DSH 升级自动受益；不用逆向 | 能干预的只有「网页层面」和「原生外壳层面」 |

**本项目选 B。** 好处是 APK 只有 1.15 MB 且几乎不用维护；
代价是有些问题（比如设置不持久、首屏大包）客户端侧解决不了。

---

## 2. 链路一：任务状态钩子

### 目标

在完全不碰 DSH 内部实现的前提下，知道「智能体这一轮跑完了」。

### 思路

DSH 界面自己在收 WebSocket 事件。我们**不去猜它的协议**，
而是**旁听它收到的消息**——把 `window.WebSocket` 包一层。

### 实现要点

`JsHook.SCRIPT` 的内容（Java 字符串常量），核心是：

```js
var Native = window.WebSocket;
var running = {};

function push(state, sid) {
  try { window.DSHNative.onTaskState(String(sid), state); } catch (e) {}
}

function handle(text) {
  if (typeof text !== 'string' || text.indexOf('api-session/status') === -1) return;
  var msg; try { msg = JSON.parse(text); } catch (e) { return; }
  if (!msg || msg.type !== 'item') return;
  var v = msg.value;
  if (!v || v.event !== 'api-session/status') return;
  var a = v.args || [];
  if (a.length < 2 || a[0] === undefined || a[0] === null) return;
  var sid = String(a[0]);
  if (a[1] === true) { if (!running[sid]) { running[sid] = true; push('start', sid); } }
  else { if (running[sid]) { delete running[sid]; push('done', sid); } }
}

var Hooked = function (url, protocols) {
  var ws = (protocols === undefined) ? new Native(url) : new Native(url, protocols);
  try { ws.addEventListener('message', function (ev) { if (typeof ev.data === 'string') handle(ev.data); }); } catch (e) {}
  return ws;
};
Hooked.prototype = Native.prototype;
Hooked.CONNECTING = Native.CONNECTING;  Hooked.OPEN = Native.OPEN;
Hooked.CLOSING    = Native.CLOSING;     Hooked.CLOSED = Native.CLOSED;
window.WebSocket = Hooked;
```

### 四个关键决策

**① 必须用 `addDocumentStartJavaScript`**

```java
WebViewCompat.addDocumentStartJavaScript(webView, JsHook.SCRIPT, Collections.singleton("*"));
```

这个 API 保证脚本在**页面自身脚本之前**执行。如果等到 `onPageFinished` 再注入，
WebSocket 早建好了，钩子会漏掉连接。

不支持该 API 的旧 WebView 会回退到 `onPageStarted` 注入（脚本自带防重入）。

**② 状态机而不是计数器**

用 `running{}` 记录每个会话是否在跑，只在 `false → true` / `true → false`
的**跳变**时通知。因为 DSH 可能重复发同一个状态，直接透传会导致重复通知。

**③ 用 `indexOf` 先做廉价过滤**

每条 WS 消息都过一遍 `JSON.parse` 会拖慢页面。
先用 `text.indexOf('api-session/status') === -1` 快速排除 99% 的消息。

**④ `Hooked.prototype = Native.prototype`**

不这样赋值，`ws instanceof WebSocket` 会失败，某些库会据此判断环境。

### 原生侧的处理

```java
public void onTaskState(String sessionId, String state) {   // @JavascriptInterface
    runOnUiThread(() -> handleTaskState(sessionId, state));
}
```

`handleTaskState`：

```
"start" → runningSessions.add(sid)
          startKeepAlive()        // 此刻 App 在前台，起前台服务是合法的
"done"  → runningSessions.remove(sid)
          if (runningSessions.isEmpty()) stopKeepAlive()
          if (!通知已开启 || App 在前台) return
          if (距上次通知 < 2.5s) return      // 多会话同时结束只提醒一次
          Notifications.showTaskDone(this)
```

### 前台服务为什么必要

Android 会在 App 进入后台后冻结进程，WebView 的 WebSocket 随之断开，
任务完成事件就收不到了。`KeepAliveService` 通过 `startForeground` 让进程保持活跃。

`AndroidManifest.xml` 里声明了 `foregroundServiceType="specialUse"`
（API 34+ 强制要求），并附上用途说明：

```xml
<service android:name=".KeepAliveService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="Keep the DSH chat connection alive while the agent is running,
                           so the user is notified when the task finishes." />
</service>
```

**必须在任务开始时启动**：Android 12+ 禁止后台启动前台服务。
任务通常是用户在前台发起的，那时启动合法。所以 `onPause` 里**故意不启动**，
只由 `onTaskState("start")` 触发。

---

## 3. 链路二：图片上传

### 问题

DSH 网页端**没有 `<input type="file">`**。它的加图入口只有两个：

- **拖拽**：`document` 上的 `dragover` / `drop`
- **粘贴**：`paste` 事件

手机上这两个都不存在。

### 对策：合成一个 drop 事件

```js
var dt = new DataTransfer();
for (var i = 0; i < files.length; i++) dt.items.add(files[i]);

var ev = new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt });
document.dispatchEvent(ev);
```

DSH 的处理器会检查 `event.dataTransfer.types.includes('Files')`，
`DataTransfer` 里加了 File 就满足。

### 文件从哪来

补一个隐藏的 `<input type="file" multiple accept="image/*">`，
点击悬浮按钮时触发它。安卓上这会走 `WebChromeClient.onShowFileChooser`，
由原生层给出系统选择器：

```java
@Override
public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                 FileChooserParams params) {
    pendingFileCallback = callback;
    startActivityForResult(params.createIntent(), REQ_FILE);
    return true;
}
```

选完在 `onActivityResult` 里把 `Uri[]` 交回 `pendingFileCallback`（支持多选，
用 `data.getClipData()`）。

### 悬浮按钮

- 圆形，`position: fixed`，`z-index` 极高
- 支持触摸拖动（`touchstart/move/end`），拖过 6px 就视为拖动、不触发点击
- 位置存 `localStorage['dsh-attach-pos']`
- 默认放**右下角**——左上角是 DSH 的侧边栏图标条，放那里会挡住

### 注意

`web-ext/dsh-ext.js` 里的 `mount()` 会被定时器多次调用（页面是 SPA，可能重渲染），
所以必须先判断 `document.getElementById(BTN_ID)` 再创建；
并且 `if (!document.body) return;` 防御 body 还不存在的情况。

---

## 4. 链路三：界面修正热更新

### 为什么要它

每改一行 CSS 都要重新编译 APK、传到手机、安装——迭代太慢。
而这些修正**本来就只作用于网页层**，完全没必要进 APK。

### 链路

```
App 启动
  → 后台线程 GET  <extBase>/dsh-ext.css
                   <extBase>/dsh-ext.js
  → 与 SharedPreferences 里缓存的内容比较
  → 有变化：写入缓存 + 立即注入当前页面
  → 无变化：什么都不做

页面 onPageFinished
  → injectExt()：把缓存的 CSS 塞进 <style id="dsh-ext-css">，JS 直接 evaluateJavascript
```

`extBase` 的解析顺序：

1. `SharedPreferences['ext_base']`（用户配置）
2. `https://<当前服务器 URL 的 host>:8443/`
3. `DEFAULT_EXT_BASE` 常量

### 早期的一个 bug

最初只在 `onPageFinished` 注入，而 `refreshRemoteExt()` 是异步的：

```
第 1 次启动：拉取完成 → 写入缓存 → 页面已经加载完了，没注入
第 2 次启动：用缓存注入 → 才生效
```

结果「改完要重启两次」。修法是：**拉取到有变化时立刻 `runOnUiThread(() -> injectExt(webView))`**，
不等下次加载。

---

## 5. DSH 服务端的信任围栏与认证

这两件事是**部署时必须处理**的，不是 App 的代码，但不知道就会卡住。

### 5.1 信任围栏（Host / Origin）

DSH 对 `/api/*` 的请求做来源校验，规则：

1. `Host` 必须是 **loopback**（`127.0.0.1` 等）、
   **由绑定地址派生的局域网 IP 字面量**、
   或**配置里声明的 `trustedHosts`**；
2. 如果请求带 `Origin`，它必须等于该 `Host`；
3. `sec-fetch-site: cross-site` 一律拒绝。

这是防 DNS rebinding 和跨站请求的，不是身份认证。

**返回码语义（排查时最重要的一条）**：

```
403  →  Host/Origin 校验失败，即「被围栏挡住」
401  →  围栏放行了，但没有有效凭据，即「没登录」
200  →  已认证
```

> 所以用域名访问时如果拿到 **403**，去加 `trustedHosts`；
> 拿到 **401** 说明围栏已经没问题了，去解决认证。

反向代理（Funnel / Cloudflare Tunnel 等）**必须保留原始 Host 头**，
否则围栏看到的是 `127.0.0.1` 之类的内部地址，行为会不符合预期。

### 5.2 认证流程

```
① 服务启动 → 生成一次性 launch token → 打印到日志
        dsh web: http://127.0.0.1:PORT/?token=<43 字符 base64url>

② 浏览器访问 /?token=<token>
        → 服务端校验 token
        → 下发 HttpOnly Cookie，303 重定向到干净的 /

③ 后续请求带 Cookie 即可
```

Cookie 细节：

```
名字   dsh-auth-<base64url(sha256(authority))>
值     v1.<base64url(payload)>.<base64url(HMAC-SHA256(secret, body))>
payload { version, authority, issuedAt, expiresAt }
属性   HttpOnly; SameSite=Strict; Path=/; Max-Age=30 天
```

**两个关键性质**：

| 性质 | 含义 |
|---|---|
| 签名密钥是**全局共享**的 | 存在 DSH 家目录的凭据文件里，所有实例共用 |
| Cookie 绑定的是 **authority（域名:端口）**，不是进程 | 同一域名下换实例、换端口，原 Cookie 依然有效 |

这两条直接决定了：**后端实例换了，手机端不需要重新登录。**
（本项目就从中受益过：把入口从「独立的 web 实例」切到「另一个实例本体」时，
手机上的登录态一行没改就继续能用。）

**验证认证是否生效**：

```bash
# 不带 Cookie，应当返回 401
curl -s -o /dev/null -w "%{http_code}\n" -H "Host: your-host.example.com" \
     http://127.0.0.1:PORT/api/gateway
```

返回 200 就说明认证没开着，**不要把这个地址暴露到公网**。

### 5.3 前端插件打包

较新版本的 DSH 会把几十个前端插件**合并成一条极长的 URL**：

```
/plugins/??插件A/client.js,插件B/client.js,...&rev=<hash>
```

这是用一次请求换掉几十次往返的优化。代价是这个响应可能有 **10 MB 量级**。

在低延迟链路上无感，但在高延迟中继链路（实测 200ms 往返）上，
首屏可能要 **1~2 分钟**。WebView 缓存后再次打开会快很多。

客户端侧无法消除这个问题——这是服务端的行为。

### 5.4 「设置不持久」是什么

DSH 对**非回环地址**的浏览器使用**内存态**的设置作用域。
也就是说：通过域名访问时，主题、字号、面板开关这些设置**刷新就回默认**。

这是 DSH 对远程访问的设计，不是 App 的 bug。

---

## 6. 断线时的排查手法

App 内置了 `WebView.setWebContentsDebuggingEnabled(true)`，
配合 adb 可以直接在电脑上调试手机里的页面：

```bash
# 1. 找到 WebView 的调试 socket（进程号会变）
adb shell cat /proc/net/unix | grep webview_devtools_remote

# 2. 转发到本地
adb forward tcp:9222 localabstract:webview_devtools_remote_<PID>

# 3. 现在可以走 CDP：
curl http://127.0.0.1:9222/json     # 列出页面
```

能做的事：

| 目标 | CDP 方法 |
|---|---|
| 跑一段 JS、读 DOM | `Runtime.evaluate` |
| 看真实报错 | `Runtime.exceptionThrown`（**关键**） |
| 看 HTTP 失败 | `Network.loadingFailed` / `Network.responseReceived` |
| 看 console | `Log.entryAdded` |
| 重载并抓现场 | `Page.reload` + `Page.addScriptToEvaluateOnNewDocument` |

**为什么必须用 CDP 的 `Runtime.exceptionThrown`**：
页面里的 `window.onerror` 对来自 `crossorigin` 脚本的错误只会给
`"Script error."`（跨域混淆，看不到任何细节）。CDP 的异常事件能看到真实信息。

**注意**：手机息屏时 WebView 会被节流，`document.readyState` 会一直停在
`"loading"` 让测量失真。调试前先 `adb shell input keyevent KEYCODE_WAKEUP`。
