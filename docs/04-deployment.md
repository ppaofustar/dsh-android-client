# 部署指南：把电脑上的 DSH 暴露给手机

App 只是外壳，**前提是手机能访问到电脑上的 DSH Web 服务**。这篇讲怎么搭这条通道。

---

## 0. 先明确三件事

搭通道前必须搞清楚：

| 事项 | 为什么重要 |
|---|---|
| **认证开着吗** | DSH 首次访问需要带 token 的 URL，之后靠 30 天 Cookie。认证没开就别暴露到公网 |
| **Host 头会被改成什么** | DSH 有信任围栏，Host 不对 `/api` 直接返回 **403**，页面等于废的 |
| **手机在哪个网络** | 同一个 Wi-Fi 最简单；不在同一网络才需要内网穿透 |

---

## 1. 方案选择

| 方案 | 适用场景 | 难度 | 手机需要装东西吗 |
|---|---|---|---|
| **A. 同一 Wi-Fi 直连** | 只在家里/公司用 | ⭐ | 不需要 |
| **B. VPN（WireGuard / Tailscale）** | 常用，安全性最好 | ⭐⭐ | 需要（且要占 VPN 槽位） |
| **C. 内网穿透**（Cloudflare Tunnel / frp / Tailscale Funnel） | 手机不能装 VPN，或要走公共网络 | ⭐⭐⭐ | 不需要 |

> **注意**：Android 同时只能有一个 VPN 生效。如果手机上已经有别的 VPN
> （比如系统级的流量代理）在用，方案 B 会冲突，得走方案 C。

---

## 2. 方案 A：同一 Wi-Fi 直连（最简单）

这是最快的验证方式，**先用它确认 App 本身没问题**，再去搞穿透。

1. 查电脑的局域网 IP：

   ```powershell
   Get-NetIPAddress -AddressFamily IPv4 |
     Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
     Select-Object IPAddress, InterfaceAlias
   ```

2. 让 DSH 监听局域网地址（如果它默认只绑 `127.0.0.1`）。

   > DSH 出于安全考虑**通常不支持 `--host 0.0.0.0`**。
   > 如果它拒绝，就只能在电脑上放一个反向代理监听 `0.0.0.0`，转发到 `127.0.0.1:<DSH端口>`。
   > 反向代理**必须保留原始 Host 头**。

3. 信任围栏：DSH 一般会自动信任「绑定地址派生的局域网 IP 字面量」，
   所以直接用 IP 访问通常能过围栏。

4. 手机浏览器打开 `http://<电脑IP>:<端口>/` 验证，再填进 App。

---

## 3. 方案 C 实例：用 Tailscale Funnel 暴露到公网

下面用一个具体例子走一遍。**把 `your-device.your-tailnet.ts.net` 换成你自己的域名。**

### 为什么选它

- 手机**不需要装 Tailscale**、不占 VPN 槽位；
- 自动有公网 HTTPS 证书；
- 可以直接把**一个目录**发出去（用来放 APK 和热更新文件）。

### 3.1 前置

1. 装 Tailscale 并登录；
2. 在**管理后台**开启 **HTTPS 证书**；
3. 在**管理后台**开启 **Funnel**（这一步不做的话，下面的命令会**静默挂起**）。

### 3.2 让 DSH 接受这个域名

DSH 对 `/api` 有 Host 围栏，必须显式声明该域名。

**怎么改**取决于是哪个实例：

- 如果是命令行启动的 `dsh web`：加 `--trusted-host your-device.your-tailnet.ts.net`；
- 如果是图形界面的桌面应用：改它 profile 下的补丁配置文件 `cordis.patch.yml`。

补丁内容形如：

```yaml
- id: connection
  name: "@deepseek-ai/dsh-client-connection"
  config:
    trustedHosts:
      - your-device.your-tailnet.ts.net
```

> ⚠️ **两个大坑**：
>
> 1. **这个配置文件里不能写 `!!js` 表达式**（文件头注释可能会说"允许"，别信）。
>    写了会导致 **DSH 启动即崩溃**，日志里是
>    `YAMLException: unknown tag !<tag:yaml.org,2002:js>`。**只能用纯字面量。**
> 2. **改完必须重启那个实例**（桌面应用不支持配置热重载）。
>    改之前先备份原文件。

**改配置前先验证**：如果还有别的可热重载的实例，把同一段配置先放到它上面跑一次，
确认能正常启动，再动目标实例。

### 3.3 验证围栏是否放行

```powershell
# 把 your-host 换成你的域名
curl.exe -s -o NUL -w "%{http_code}`n" -H "Host: your-device.your-tailnet.ts.net" `
    http://127.0.0.1:<DSH端口>/api/gateway
```

| 返回 | 含义 | 下一步 |
|---|---|---|
| **403** | 被围栏挡住 | 检查 `trustedHosts` 是否生效、实例是否重启过 |
| **401** | 围栏已放行，未认证 | 正常，继续 |
| **200** | 既放行又没认证 | ⚠️ 认证没开，**不要暴露到公网** |

### 3.4 建立 Funnel

```powershell
# 主服务 → 443
tailscale funnel --https=443 --bg --yes <DSH端口>

# 顺带把发布目录（APK + 热更新文件）发到 8443
tailscale funnel --https=8443 --bg --yes <静态目录端口>

# 查看状态
tailscale funnel status
```

> `tailscale funnel` 的 Funnel 配置是**持久化**的，重启自动恢复。

### 3.5 手机上认证

```powershell
# 取当前的一次性 token（每次服务重启都会变）
Get-Content "$env:USERPROFILE\.dsh\dsh-web-remote.log"
```

输出里形如 `dsh web: http://127.0.0.1:PORT/?token=xxxx`，
把 `http://127.0.0.1:PORT` 换成你的公网域名，整条粘进 App 的「连接 DSH」对话框。

认证成功后服务端会下发 Cookie（30 天），**之后不用再管 token**。

---

## 4. 认证机制（为什么换实例不用重新登录）

理解这两条能省很多事：

| 性质 | 说明 |
|---|---|
| 签名密钥**全局共享** | 存在 DSH 家目录的凭据文件里，所有实例共用同一个 |
| Cookie 绑定 **authority（域名:端口）**，不是进程 | 同一域名下**换实例、换端口，原 Cookie 依然有效** |

所以：**改完后端实例、改端口，只要域名不变，手机端不需要重新登录。**

（本项目实测过：把公网入口从一个实例切到另一个实例，手机上的登录态一行没改就继续用。）

### 手工签发 Cookie（换域名时用）

如果你知道 DSH 的签名密钥，可以自己签一个 Cookie 来免去交互式登录：

```js
// authority 形如 "your-host.example.com" 或 "your-host.example.com:8444"
const b64u = (b) => Buffer.from(b).toString('base64')
  .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');

const cookieName = 'dsh-auth-' + b64u(createHash('sha256').update(authority).digest());
const body = b64u(Buffer.from(JSON.stringify({
  version: 1, authority, issuedAt: Date.now(), expiresAt: Date.now() + 29 * 864e5,
}), 'utf8'));
const value = `v1.${body}.${b64u(createHmac('sha256', secret).update(body).digest())}`;
```

密钥在 DSH 家目录的 `.credentials.yaml` 里，记录键 `client-connection/browser-session`。

拿到 `name=value` 后用 CDP 的 `Network.setCookie` 写进 WebView 即可。

---

## 5. 常用命令速查

```powershell
# 静态发布目录（放 APK 和热更新文件）
python -m http.server 8099 --directory <发布目录>

# 反向代理 / 穿透
tailscale funnel status
tailscale funnel --https=443 off          # 关掉
tailscale funnel --https=443 --bg --yes <端口>   # 打开

# 验证围栏
curl.exe -s -o NUL -w "%{http_code}`n" -H "Host: your-host" http://127.0.0.1:<端口>/api/gateway

# 手机端调试（需要 adb）
adb shell cat /proc/net/unix | grep webview_devtools_remote
adb forward tcp:9222 localabstract:webview_devtools_remote_<PID>
curl http://127.0.0.1:9222/json
```

---

## 6. 安全清单

暴露到公网之前逐条确认：

- [ ] **认证是开着的**（不带 Cookie 请求 `/api/gateway` 应返回 401，不是 200）
- [ ] 带 `token=` 的链接**没有外传、没有截图分享**（它等同于钥匙，能读写本机文件、执行命令）
- [ ] 用的是 **HTTPS**，不是明文 HTTP
- [ ] 只暴露必要的端口（下载页那个静态目录不含任何隐私数据）
- [ ] 知道怎么**紧急关闭**：`tailscale funnel --https=443 off`
- [ ] 知道怎么**作废所有已登录的客户端**：删掉服务端凭据里的浏览器会话记录并重启服务

---

## 7. 排查清单

按顺序问自己：

1. **手机浏览器直接打开这个地址，能通吗？**
   不能 → 网络/穿透问题，跟 App 无关。
2. **页面能打开，但内容空白 / 一直转圈？**
   看浏览器控制台或 CDP 的 `Network.loadingFailed`。
   如果是「大包下载慢」，等一会儿就好（见 [03-pitfalls.md](03-pitfalls.md) 第 10 条）。
3. **页面能打开，但所有操作都失败？**
   大概率是 `/api` 被围栏挡了 → 用上面那条 `curl` 看返回码。
   403 = 围栏；401 = 认证。
4. **提示需要重新登录？**
   Cookie 过期（30 天）或服务端换了签名密钥。
   重新粘贴带 token 的完整链接。
5. **收到 401/403 但 App 没弹登录框？**
   检查是不是被反代改写成了别的状态码。
