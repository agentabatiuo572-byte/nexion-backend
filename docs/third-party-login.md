# Google、Apple、Telegram 登录配置

三方授权使用系统浏览器。已有手机号账号在「账号安全」验证当前密码并绑定，之后可用第三方登录；未绑定身份不会创建空手机号账号，也不会按邮箱合并账号。登录继续执行封禁、强制重置密码、二次验证码和现有会话管理规则。

## 启用配置

由部署环境的安全配置提供下列 Spring properties，不写入 Git 或前端环境文件。独立总开关 `oauth.enabled`（环境变量 `OAUTH_ENABLED`）默认 `false`，仅显式 `true` 才开放；缺失、空值或其他字符串均关闭。凭据完整不再等于启用。总开关关闭时平台列表为空，新建授权、打开供应商页面、回调、查询流程、登录完成/重试、绑定与旧三方兑换入口均无法完成授权或发放会话。开关不撤销此前已经发出的普通会话，也不改变本地开发 Passkey 的现有隔离规则。

未完整配置的平台不显示登录按钮。总开关开启后仍仅正式数据环境（单独的 `dev` / `prod` profile）开放，沙箱数据不共享真实平台身份。

```properties
oauth.enabled=false
oauth.public-base-url=https://api.example.com
oauth.h5-origin=https://app.example.com
oauth.google.client-id=
oauth.google.client-secret=
oauth.apple.client-id=
oauth.apple.client-secret=
oauth.telegram.client-id=
oauth.telegram.client-secret=
```

TEST 固定保持 `OAUTH_ENABLED=false`，可提前加载 Google Client ID/Secret。配置变更需要按部署流程重启后端生效；未来功能完成后，须经用户明确批准再设为 `true`、重启并验收。不得因已配置凭据或构建成功自动开启。本轮仅验证关闭门禁，不代表 Google/Apple/Telegram 真实授权验收通过。

域名需替换为实际 HTTPS 网关。`h5-origin` 只填协议、域名和端口，不带路径或末尾斜杠；它控制回调页向哪个网页窗口传递授权结果。仅本地 `dev` 允许其使用 HTTP localhost/127.0.0.1。`public-base-url` 始终需要 HTTPS。

| 平台 | 开发者应用与凭据 | 登记的完整回调地址 |
| --- | --- | --- |
| Google | Google Cloud 配置同意屏幕及 Web OAuth client；测试状态添加测试用户 | `https://api.example.com/auth/users/oauth/provider/callback/GOOGLE` |
| Apple | 开启 Sign in with Apple 的 App ID、关联的 Services ID、Team ID、Key ID 和私钥；client-id 使用 Services ID | `https://api.example.com/auth/users/oauth/provider/callback/APPLE` |
| Telegram | BotFather 创建代表本产品的 Bot，在 Login Widget 设置 Allowed URLs，取得该处 Client ID / Client Secret；使用默认 RS256 | `https://api.example.com/auth/users/oauth/provider/callback/TELEGRAM` |

Apple 推荐使用按需自动签发：配置 `oauth.apple.team-id`、`oauth.apple.key-id`、`oauth.apple.private-key-file`（环境变量分别为 `OAUTH_APPLE_TEAM_ID`、`OAUTH_APPLE_KEY_ID`、`OAUTH_APPLE_PRIVATE_KEY_FILE`），以及上面的 Services ID。私钥文件使用服务器上的绝对路径，内容为 Apple 下载的 PKCS#8 PEM `.p8`，只允许后端服务账号和系统管理员读取，不进入 Git、镜像、前端或日志。

每次向 Apple 交换授权码时，后端读取该文件并签发有效期 5 分钟的 ES256 Client Secret，包含 Team ID（iss）、Services ID（sub）、Apple audience、Key ID、iat 和 exp。无需定时任务、半年替换凭据或为续签重启服务。签发时间回退 30 秒容忍轻微时钟差，服务器仍需正常时间同步。总开关关闭时在读取私钥和签发之前拒绝授权。

配置任一签名字段后必须完整提供三项和有效 P-256 私钥；缺失、不可读或损坏时平台不可用，不回退到旧静态 Client Secret。未配置这些字段时仍兼容已有静态 `client-secret`。迁移完成后移除 `OAUTH_APPLE_CLIENT_SECRET`，避免以后误用过期值。更换同一路径的私钥会在下一次请求读取；更换 Key ID / Team ID / Services ID 等环境配置仍需正常部署重启。自动签发不能恢复已撤销密钥或失效开发者会员，出现此情况需在 Apple 控制台处理。

Telegram 使用 Login Widget 的 client secret，不使用 Bot API token。

## 网关和客户端

- 转发 `/auth/users/oauth/provider/**`，保留 Cookie、Set-Cookie、Authorization 及已有登录上下文请求头。Apple 回调接受表单 POST，仅为该路径放行 Apple Origin。
- 回调 cookie 使用 Secure、HttpOnly、SameSite=None、`__Host-` 前缀，不设置 Domain。网关不得改为 HTTP、缓存授权响应或记录请求/响应正文中的 code、flowKey、receipt、密码、验证码及令牌。
- H5 使用弹窗及固定 Origin 的 postMessage。网页不能设置 `Cross-Origin-Opener-Policy: same-origin`；使用 `same-origin-allow-popups` 或不设置。回调页响应自己的 CSP 和 COOP。
- APP manifest 已注册 `uvel://oauth`。需要重新打包安装才能生效；浏览器完成后点击返回 App。授权证明只在当前页面内存保留，杀进程或刷新后重新开始。
- 新表 `nx_oauth_provider_flow` 随服务启动按现有 Mapper 初始化方式创建，部署数据库账号需有建表权限。流程 10 分钟过期；仅保存短期 PKCE verifier 和凭据哈希，不保存第三方 access/ID token。

## 上线验收

先部署后端与同步版本的 App/H5，再配置各开发者平台。逐个平台验收：取消授权、未绑定提示、当前密码错误/限流、绑定成功、已绑定到他人拒绝、登录成功、启用二次验证码及 CAPTCHA、会话刷新/中断重试、账号切换、Android/iOS 回跳。检查禁用/重置密码账号无法绕过既有规则。

本地传输夹具和签名测试证明代码行为，不代表真实平台授权成功；开发者应用尚未创建时，真实账号和真机授权验收仍待完成。

官方参考：[Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect)、[Apple 授权参数](https://developer.apple.com/documentation/signinwithapple/incorporating-sign-in-with-apple-into-other-platforms)、[Apple token API](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens)、[Telegram 登录](https://core.telegram.org/bots/telegram-login)。
