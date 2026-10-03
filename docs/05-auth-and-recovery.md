# 登录态、失效恢复与无线调试

这篇讲三件事，都是**真机上踩出来的**：

1. 手机 App 的登录态到底是什么、什么情况下会掉；
2. **掉了之后为什么可能救不回来**，以及三种恢复办法；
3. 怎么用 ADB 无线调试连上手机去排查（含二维码配对的完整流程）。

> 本文所有域名、令牌、路径均为占位符，请替换成你自己的。

---

## 1. 登录态到底是什么

DSH 的浏览器认证有**两层**，很容易混在一起：

| | 启动令牌（launch token） | 会话 Cookie |
|---|---|---|
| 形态 | URL 上的 `?token=<43 字符 base64url>` | `dsh-auth-<hash>=v1.<payload>.<HMAC>` |
| 生成方式 | **每个进程启动时随机生成**（`randomBytes`） | 服务端用共享密钥签名 |
| 存放位置 | **只在进程内存里，不落盘** | 浏览器 / WebView 的 Cookie 存储 |
| 有效期 | 进程活多久算多久 | 默认 **30 天** |
| 作用面 | **只在 `GET /` 上**换一次 Cookie | 之后每个请求都靠它 |

三句关键事实（来自 DSH 自己的架构文档）：

> 每个进程生成一个随机启动令牌。`frontend-static` 只在 `GET /` 接受该令牌，
> 写入绑定 authority 的签名 cookie，再重定向到干净的 `./`。
>
> cookie 签名密钥是 `ctx.credentials` 中 `client-connection/browser-session`
> 的 grant 记录，本地提供方把它持久化到 `$DSH_HOME/.credentials.yaml`。
>
> cookie 在**确定性名称与签名 payload 中同时绑定规范化 hostname 和 port**。

由此推出两个非常重要的结论：

- **Cookie 认的是「域名 + 端口」，不是进程。** 所以 DSH 换进程、换监听端口重启，
  只要访问域名不变，手机上的 Cookie 照样有效 —— 这正是它被设计出来的意图。
- **反过来，重装 App / 清除 App 数据 = Cookie 没了。** 这时你手里只剩启动令牌，
  而启动令牌**只对生成它的那个进程有效**。

---

## 2. 什么情况下会掉登录态

| 操作 | 会掉吗 | 原因 |
|---|---|---|
| 重启电脑 / 重启 DSH | ❌ 不会 | Cookie 绑定域名，与进程无关 |
| 只改电脑内部监听端口，公网访问地址不变 | ❌ 不会 | 浏览器看到的 authority 没变 |
| 修改公网访问地址的域名或端口 | ✅ 会 | authority 变了，原 Cookie 名和签名均不匹配 |
| **换访问域名，或改端口** | ✅ 会 | authority 变了，签名 payload 对不上 |
| **重装 App / 清除 App 数据** | ✅ 会 | WebView 的 Cookie 存储被清空 |
| Cookie 满 30 天 | ✅ 会 | 需要重新换一次 |

---

## 3. 掉了之后为什么可能救不回来

因为**启动令牌可能拿不到**。

- 如果你连的是 **`dsh web` 起的实例**：好消息。它的启动令牌会**打印到日志里**，
  随时能从日志文件翻出来，重新粘一次链接就能登录。
- 如果你连的是**桌面 App（Electron）的实例**：坏消息。它的启动令牌
  **只存在于进程内存里**。它的窗口走的是 Electron 私有协议（`dsh-app://`），
  根本不经过 HTTP 认证，所以磁盘、Chromium 存储、日志里**全都没有**。

一句话：

> **桌面实例 + 手机上 Cookie 掉了 = 没有任何办法从界面上重新登录。**

除非用下面第 4 节的第 2 种办法。

---

## 4. 三种恢复办法

### 办法 A（推荐）：改用 `dsh web` 实例，令牌随时可取

```bash
dsh web *> <日志文件>
```

日志里会有这样一行，其中的 token 就是启动令牌：

```
dsh web: http://127.0.0.1:19388/?token=<43 字符 base64url>
```

把 `http://127.0.0.1:19388` 换成你对外暴露的地址，整条链接粘进 App 的「设置」即可。

> 也可以做成计划任务开机自启（本仓库的部署就是这么用的）。重启后令牌会变，
> 但日志里有新的，随时能取。

### 办法 B（高级）：自己签一张 Cookie 塞进手机

桌面实例的令牌拿不到，但**签名密钥是持久化的**，所以 Cookie 可以自己造。

> 仅可对你本人拥有或获明确授权的设备和 DSH 实例操作。下面涉及的凭据文件、
> Cookie、配置快照都等同于登录凭证，禁止发给他人或提交到仓库。

```text
1. 从 $DSH_HOME/.credentials.yaml 取出 client-connection/browser-session 的 secret
   （base64url，解码后应为 32 字节）
2. 为你的 authority 计算 Cookie 名
   name = "dsh-auth-" + base64url( sha256(authority) )
   例如 authority = "your-host.your-tailnet.ts.net"
3. payload = base64url( JSON({ version:1, authority:<authority>,
                               issuedAt:<ms>, expiresAt:<ms> }) )
   value = "v1." + payload + "." + base64url( HMAC-SHA256(secret, payload) )
4. 把 name=value 写进手机 WebView
```

第 4 步最反直觉：

> **Cookie 不区分端口，只认 host。**
> 所以只要在一个「host 就是这个域名」的页面上执行 `document.cookie = ...`，
> 这个 Cookie 就会被带到 `https://<该域名>/` 的请求上 —— 不管端口是 443 还是 8443。

于是可以借 App 自己的热更新注入通道（见 [02-integration.md](02-integration.md)）：
把一段引导脚本拼到 App 缓存的 `ext_js` **前面**，脚本判断当前是未认证页面时
写 Cookie 并重载一次即可。这个改造点在 App 私有目录里，需要 ADB：

```bash
adb shell am force-stop com.dsh.chat

# 取出配置（注意：-RedirectStandardOutput 之类的字节级重定向，别用文本管道）
adb exec-out run-as com.dsh.chat cat \
  /data/data/com.dsh.chat/shared_prefs/dsh_prefs.xml > prefs.xml

# 在 <string name="ext_js"> 的值前面拼接引导脚本
#   XML 转义：换行 -> &#10;  & -> &amp;  < -> &lt;  > -> &gt;
# 同时把 ext_base 指到一个连不上的地址，防止 App 启动时把缓存覆盖掉

adb push prefs.xml /data/local/tmp/p.xml
adb shell chmod 644 /data/local/tmp/p.xml
adb shell "run-as com.dsh.chat cp /data/local/tmp/p.xml \
  /data/data/com.dsh.chat/shared_prefs/dsh_prefs.xml"
adb shell am start -n com.dsh.chat/.MainActivity

# 确认引导脚本跑了（页面里的 console.log 会出现在 logcat）
adb logcat -d | grep dsh-boot
#   [dsh-boot] href=https://<域名>/ | unauth=false | has=true | ckLen=250
#   看到 unauth=false 且 href 已经是干净的根地址，就是登录成功了
```

成功后记得把 `ext_base` 和 `ext_js` 还原成原样，并删除电脑及手机临时目录中的
`prefs.xml` / `p.xml`。

> ⚠️ 这套办法等于**手工给自己发一张 30 天的通行证**。做完要把临时文件
> （含 Cookie 的配置快照、脚本、导出的 Cookie）**删干净**，也不要放到任何公开目录里。

### 办法 C：重新配对 + 重新配置

实在不行就重装 App，再按 [04-deployment.md](04-deployment.md) 走一遍首次配置。

---

## 5. 用 ADB 无线调试连手机排查

安卓 11+ 支持**无线调试**，不用插数据线。两种配对方式。

### 5.1 配对码方式

1. 手机：`设置 → 系统 → 开发者选项 → 无线调试`，先打开总开关；
2. 点「使用配对码配对设备」，手机显示一个 **IP:端口** 和 **6 位配对码**；
3. 电脑：

```bash
adb pair <手机IP:配对端口> <6位配对码>
adb connect <手机IP:连接端口>   # 「无线调试」页面上显示的那个端口，和配对端口不是一个
```

### 5.2 二维码方式（不用手敲数字）

手机扫的二维码里装的是一串**仿 Wi-Fi 配置格式**的文本：

```
WIFI:T:ADB;S:<服务名>;P:<密码>;;
```

流程是：电脑显示二维码 → 手机扫 → **手机主动广播配对服务** → 电脑用 mDNS 找到它 → `adb pair`。

```bash
# 1. 电脑生成并显示二维码（服务名/密码随便生成，例如 ADB_WIFI_XXXXXX / 6 位十六进制）
# 2. 手机：设置 -> 系统 -> 开发者选项 -> 无线调试 -> 使用二维码配对设备，扫它
# 3. 电脑用 mDNS 找到手机广播出来的配对服务
adb mdns services                  # 找 _adb-tls-pairing._tcp，记下 ip:port
adb pair <ip:port> <密码>           # 密码就是二维码里 P: 后面那串
adb mdns services                  # 再找 _adb-tls-connect._tcp
adb connect <ip:port>
```

> **前提：手机和电脑必须在同一个局域网。**
> 二维码只省掉「念配对码」这一步，它**解决不了「不在同一个网」** ——
> 手机扫完码是在它自己那个网络上广播的，电脑在别的网上听不到。

配对成功后，只要手机还在这个网里，`adb mdns services` 就能一直看到它，
**IP 变了也能自己找回来**。所以连上之后，建议保留 mDNS 那条连接，
把 `IP:端口` 那条断开，避免同一个设备出现两条记录、`adb shell` 报 "more than one device"。

### 5.3 常用排查命令

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png .  # 截图
adb shell dumpsys window | grep mCurrentFocus                     # 当前前台是哪个界面
adb shell pidof com.dsh.chat                                      # 进程还活着吗
adb logcat -d | grep CONSOLE                                      # 看页面里的 console.log
```

> 页面里 `console.log` 出来的东西，在 logcat 里长这样：
> `chromium: [INFO:CONSOLE:12] "你的日志"`。
> 这是**没有 DevTools 时，看手机页面内部状态最实用的手段**。

还可以直接读 App 的配置（debug 包才允许 `run-as`）：

```bash
adb shell "run-as com.dsh.chat cat /data/data/com.dsh.chat/shared_prefs/dsh_prefs.xml"
adb exec-out run-as com.dsh.chat cat \
  /data/data/com.dsh.chat/app_webview/Default/Cookies > cookies.db
# cookies.db 是 SQLite：SELECT host_key, name, value, path FROM cookies;
```

---

## 6. 排查速查表

| 手机上的现象 | 大概率原因 | 怎么确认 |
|---|---|---|
| `net::ERR_TIMED_OUT` | 电脑上的服务没起来 / 隧道没通 | 在电脑上 `curl` 一下同一个地址 |
| `net::ERR_NAME_NOT_RESOLVED` | 域名解析不了 | 在手机上 `ping` 那个域名 |
| 页面显示 `dsh web authentication required` | 认证没过（**不是**网络问题） | 不带 Cookie 请求 `/api/gateway`，应为 **401** |
| **403** | 被**信任围栏**拦了，域名没进 `trustedHosts` | 对照 `cordis.patch.yml` 的 `trustedHosts` |
| **401** | 围栏过了，但没认证 | 见本文第 4 节 |
| 图片全是破图 | DSH 渲染图片用的是桌面私有协议 `dsh-app://` | 见 README「为什么对话里的图片在手机上是破图」 |

**401 和 403 的区别非常重要，别搞反：**

- **403** = 连门都没进（Host / Origin 不被信任）；
- **401** = 进门了但不认识你（缺有效 Cookie）。

排查时先看这两个码，能省掉一大半弯路。

---

## 7. 一句话总结

> 手机端的登录态是一张**绑定域名的 30 天 Cookie**。
> 它扛得住服务重启、扛得住换端口，但**扛不住重装 App**。
> 而重装之后能不能救回来，取决于你连的是哪个实例：
> **`dsh web` 能救**（令牌在日志里），**桌面 App 救不了**（令牌只在内存里）。
> 所以对外提供手机访问时，**优先用 `dsh web` 实例**。
