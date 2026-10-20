# Google、Apple、Telegram 登录配置

三方授权使用系统浏览器。已有手机号账号在「账号安全」验证当前密码并绑定，之后可用第三方登录；未绑定身份不会创建空手机号账号，也不会按邮箱合并账号。登录继续执行封禁、强制重置密码、二次验证码和现有会话管理规则。

## 启用配置

由部署环境的安全配置提供下列 Spring properties，不写入 Git 或前端环境文件。未完整配置的平台不显示登录按钮。仅正式数据环境（单独的 `dev` / `prod` profile）开放，沙箱数据不共享身份。

```properties
oauth.public-base-url=https://api.example.com
oauth.h5-origin=https://app.example.com
oauth.google.client-id=
oauth.google.client-secret=
oauth.apple.client-id=
oauth.apple.client-secret=
oauth.telegram.client-id=
oauth.telegram.client-secret=
```

域名需替换为实际 HTTPS 网关。`h5-origin` 只填协议、域名和端口，不带路径或末尾斜杠；它控制回调页向哪个网页窗口传递授权结果。仅本地 `dev` 允许其使用 HTTP localhost/127.0.0.1。`public-base-url` 始终需要 HTTPS。

| 平台 | 开发者应用与凭据 | 登记的完整回调地址 |
| --- | --- | --- |
| Google | Google Cloud 配置同意屏幕及 Web OAuth client；测试状态添加测试用户 | `https://api.example.com/auth/users/oauth/provider/callback/GOOGLE` |
| Apple | 开启 Sign in with Apple 的 App ID、关联的 Services ID、Team ID、Key ID 和私钥；client-id 使用 Services ID | `https://api.example.com/auth/users/oauth/provider/callback/APPLE` |
| Telegram | BotFather 创建代表本产品的 Bot，在 Login Widget 设置 Allowed URLs，取得该处 Client ID / Client Secret；使用默认 RS256 | `https://api.example.com/auth/users/oauth/provider/callback/TELEGRAM` |

Apple 的 `client-secret` 是使用开发者私钥签出的 ES256 JWT，含 Team ID（iss）、Services ID（sub）、Apple audience、iat 和 exp；须在过期前通过安全配置轮换。当前接入消费已生成的 JWT，不把私钥交给 App，也不自动生成开发者应用或轮换密钥。Telegram 使用 Login Widget 的 client secret，不使用 Bot API token。

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
