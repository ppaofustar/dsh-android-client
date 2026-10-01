# 踩坑记录

开发过程中真实撞到的问题。每条都是「症状 → 根因 → 解法」，
比结论更有价值的是**排查手法**。

---

## 1. `Script error.` 什么都看不到

**症状**：手机页面卡在「Loading plugins…」，`window.onerror` 只报
`Script error. @ :0:0`，没有任何有效信息。

**根因**：DSH 的入口脚本是 `<script type="module" crossorigin src="...">`。
带 `crossorigin` 时浏览器按 CORS 模式加载，一旦抛错，
出于安全考虑只把错误信息混淆成 `"Script error."`。

**解法 / 排查手法**：不要用 `window.onerror`，改用 **CDP 的事件**：

```js
ws.send(JSON.stringify({ id: ++id, method: 'Runtime.enable' }));
// 监听 Runtime.exceptionThrown —— 这里能看到真实的堆栈
```

同时监听 `Log.entryAdded` 和 `Network.loadingFailed`，能一并看到
CSP 报错和请求失败。

> **可复用结论**：页面报 `Script error.` 时，永远去 CDP 拿 `Runtime.exceptionThrown`。

---

## 2. 手机息屏导致测量全是假的

**症状**：反复测到 `document.readyState === "loading"`、`document.body.innerText` 为空，
看起来像页面挂了。但下一次测又正常。

**根因**：手机息屏后 WebView 被系统节流，页面加载停滞在中间态。

**解法**：调试前先唤醒屏幕。

```bash
adb shell input keyevent KEYCODE_WAKEUP
```

> **可复用结论**：移动端 WebView 调试出现「时好时坏」的结果时，
> 先排除屏幕状态和后台节流，别急着改代码。

---

## 3. `position: fixed` 让主内容宽度变 0

**症状**：把侧边栏改成 `position: fixed` 后，主内容不是变宽，而是**塌成 0 宽**。

**根因**：元素一旦 `fixed` 就**脱离文档流**。原本网格里
`sidebar | center | details` 三列，侧边栏走了之后，
`center` 和 `details` 会**依次前移**填进第 1、2 列——
如果第 1 列本来是给侧边栏的 0px 或某个空列，主内容就掉进去了。

**解法**：**列定义必须和 `fixed` 同时改**，让网格不再为侧边栏留位置。

```css
/* 错：只改定位 */
[class*="_sidebarCol"] { position: fixed; }

/* 对：定位和列数一起改 */
html.dsh-sb-open [class*="_frame"] { grid-template-columns: minmax(0, 1fr) !important; }
html.dsh-sb-open [class*="_frame"] > [class*="_sidebarCol"] { position: fixed !important; }
```

> **可复用结论**：把子元素摘出网格流时，永远要同时调整容器的列定义。

---

## 4. 依赖 data 属性判断状态——换版本就失效

**症状**：第一版抽屉布局工作正常。DSH 升级后**同一份 CSS 完全失效**，
主内容又被挤成 168px。

**根因**：折叠状态原本暴露在 `<frame>` 的
`data-sidebar-collapsed` / `data-details-collapsed` 属性上，
新版本把这两个属性删了，改成只体现在**行内 style**：

```
展开：grid-template-columns: 280px minmax(0px, 1fr) minmax(0px, 0px)
收起：grid-template-columns:  56px minmax(0px, 1fr) minmax(0px, 0px)
```

**纯 CSS 读不到行内值**，所以旧选择器全部不匹配；而其中一条
`:not([data-sidebar-collapsed])` 因为属性**消失**反而**恒为真**，
导致状态判断整体错乱（收起时也把侧边栏设成 fixed）。

**解法**：加一层 JS 桥。脚本轮询观察行内 style，把状态写成 `<html>` 上的 class，
CSS 再据此布局。这样**新旧两种版本都能兼容**。

```js
var m = /grid-template-columns\s*:\s*([^;]+)/.exec(frame.getAttribute('style') || '');
var parts = splitCols(m[1]);                 // 按空格切，跳过括号内的空格
var sidebarOpen = pxOf(parts[0]) > 100;      // 56 = 收起，280 = 展开
var detailsOpen = !(parts[2] === '0px' || /minmax\(\s*0px\s*,\s*0px\s*\)/.test(parts[2]));
root.classList.toggle('dsh-sb-open', sidebarOpen);
root.classList.toggle('dsh-dt-open', detailsOpen);
```

> **可复用结论**：
> ① `:not([attr])` 在属性被移除后会**恒真**，是隐蔽的陷阱；
> ② 依赖第三方 DOM 细节时，JS 桥比纯 CSS 更抗版本变化。

---

## 5. 切分 `minmax(0px, 1fr)` 时不能简单按空格切

**症状**：解析 `280px minmax(0px, 1fr) minmax(0px, 0px)` 得到 5 段而不是 3 段。

**根因**：`minmax(0px, 1fr)` 内部有空格，`split(' ')` 会切开。

**解法**：只在**括号外**的空格切分。

```js
value.split(/\s+(?![^()]*\))/)
```

> **可复用结论**：CSS 函数值不能用朴素空格切分。

---

## 6. 热更新「要重启两次才生效」

**症状**：改完 `dsh-ext.css`，第一次重启 App 没变化，第二次才生效。

**根因**：拉取是异步的，而注入挂在 `onPageFinished` 上：

```
第 1 次启动：拉取完成 → 写缓存 → 但页面早加载完了，没人注入
第 2 次启动：直接用缓存注入 → 生效
```

**解法**：拉到内容**有变化就立即注入当前页面**，不等下次加载。

```java
if (changed) {
    runOnUiThread(() -> { if (webView != null) injectExt(webView); });
}
```

---

## 7. `.ps1` 里的中文在 Windows PowerShell 5.1 下被当 GBK 解码

**症状**：带中文注释的 `.ps1` 脚本报语法错误或乱码。

**根因**：Windows PowerShell 5.1 读取 `.ps1` 时按系统 ANSI 代码页（中文系统是 GBK）解码，
UTF-8 无 BOM 的中文会被解成乱码，可能产生 `|` `&` 之类的字节把语法搞崩。

**解法**（三选一）：

1. 脚本内容**只用 ASCII**（最省事，本项目选的这个）；
2. 存成 **UTF-8 with BOM**；
3. 用 `pwsh`（PowerShell 7）跑，它默认按 UTF-8 读。

> 同样的坑在 `.cmd` 里更严重：GBK 中文的尾字节可能正好是 `|` 或 `&`，
> 直接把命令行拆断。**批处理脚本别写中文。**

---

## 8. 授权失败时 `--bg` 静默挂起

**症状**：`tailscale funnel --bg <port>` 不报错也不返回，就卡住。

**根因**：tailnet 管理后台里 **Funnel 功能没启用**。CLI 不走错误路径，而是等待。

**解法**：先去管理后台开启 Funnel（HTTPS 证书也要单独启用），再执行命令。

> **可复用结论**：CLI 静默挂起时，先怀疑「服务端侧的功能开关没开」，
> 而不是命令参数写错。

---

## 9. 装 Tailscale 报 1603

**症状**：MSI 安装失败，错误码 1603。

**根因**：Windows 服务 `iphlpsvc`（IP Helper）被禁用。Tailscale 依赖它。

**解法**：把 `iphlpsvc` 改成「自动」并启动，再装。

---

## 10. 把「10 MB 大包导致加载慢」误判为「页面卡死」

**症状**：手机打开页面后长时间停在「Loading plugins…」，
看起来像卡死了。

**根因**：服务端把 57 个前端插件合并成一个 **10.46 MB** 的响应。
在 200ms 往返的中继链路上要下 **128 秒**。

**排查过程**（值得复用）：

```
① 先量请求：在页面里 fetch 那个 URL，记录 status / 字节数 / 耗时
   → 200，10460083 bytes，128314 ms     ← 不是失败，是慢
② 再对比另一台服务：同一个位置只有 0 个合并批次
   → 确认是「合并打包」这个新行为造成的
```

**关键教训**：我在中间用 `Page.reload` 重载页面，把正在下载的大请求打断了，
产生了 `net::ERR_CONNECTION_ABORTED`，一度误判成「连接被中断」。

> **可复用结论**：调试「加载不出来」时，**不要在有大量在途请求时重载**；
> 先量清楚是「失败」还是「慢」，两者排查方向完全相反。

---

## 11. 检查一个补丁配置会不会让服务起不来

**症状**：需要给 DSH 的桌面实例加一段配置（`trustedHosts`），
但**桌面实例不做热重载，必须重启才生效**，
而重启会中断当时正在进行的会话——如果配置写错，服务直接起不来。

**做法**：**先在可以热重载的实例上验证同一段配置。**

具体是：

1. 把要加的配置写进另一个**支持热重载**的实例的补丁文件；
2. 重启那个实例，观察是否正常启动；
3. 确认无误后再写进目标实例。

**结果**：正是靠这一步，发现了配置文件里**不能使用某个标签**——
写了会导致启动即崩溃。如果直接改目标实例，用户重启后就再也打不开了。

> **可复用结论**：改动「需要重启才生效、且失败会导致服务不可用」的配置时，
> 找一个**低风险副本先验证**，不要拿唯一的生产实例试。

---

## 12. 不要用「看起来是稳定的字符串」判断长期存在的 DOM

`dsh-ext.js` 里有一段自动关闭 DSH 欢迎弹窗的逻辑，
靠匹配弹窗文案（如「内测声明」）和按钮文案（如「继续」）来点击。

DSH 改版后弹窗文案变成别的词，这段逻辑就静默失效了——不报错，只是不再工作。

**这是一个已知的脆弱点。** 处理原则：

- 文案匹配必须是**宽松的**（多个候选标记 + 按钮文案白名单）；
- 必须在**弹窗容器范围内**找按钮，不要全页面找（否则可能误点其它按钮）；
- 如果无法实际观察到弹窗，**宁可不动**——一段看不见的自动点击逻辑，
  风险大于收益。
