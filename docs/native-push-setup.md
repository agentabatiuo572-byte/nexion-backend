# Direct Android FCM / iOS APNs setup

Retain the international DCloud UVELProduction application (`__UNI__G77FFD9D`). International DCloud has no UniPush channel for this integration. Android uses the native Firebase SDK and FCM HTTP v1; iOS uses system APNs registration and HTTP/2 token authentication. The old uniCloud bridge and deployment assets are retired. Sending and scheduling default to disabled. No cloud project, credentials, package signing, deployment, or real-device acceptance is implied by these code changes.

## Protected server configuration

2026-10-30 identity-policy update: the user requests the same `ai.uvel.app` Android package / iOS Bundle ID and shared push private keys across TEST and production. Reuse one Firebase project/client and FCM sender credential. For a single newly created APNs key, both environments must use the same `production` APNs environment with a matching distribution-signed iOS test package (for example TestFlight); development-signed sandbox packages require a separate sandbox key. The user confirmed this distribution choice and credential creation. The common Android client, FCM sender/private key and topic-specific Production APNs key are created and protected locally; Google OAuth exchange and local key signatures were verified. Signing materials were located and the TestFlight profile was regenerated with the production push entitlement. Native package acceptance and server deployment remain separate. New APNs topic-specific keys do not span both environments; see Apple's token-connection documentation linked below.

Keep business APIs, databases, sessions, queues and scheduling separate. Inject key file paths and provider settings from each deployment's protected configuration; no manual source replacement is required on promotion. Same identity/credentials do not give cross-backend token exclusivity: existing ownership/deduplication is local to each backend database. Revoke the old environment binding online and log out before moving a tester installation to another backend; do not send TEST campaigns to an unverified installation. Shared credentials do not authorize production activation or release.

Use protected server runtime configuration and absolute paths to protected files. Never place a service-account JSON, APNs p8 private key, bearer/JWT, or binding proof in source, client packages, reports, logs, or environment examples. Only file paths are configured; no inline private-key property exists. The public Firebase Android configuration belongs in the approved native packaging configuration, never the server service-account key.

| Backend property | Required value / default |
| --- | --- |
| `native-push.enabled` | `false` until authorized TEST activation |
| `native-push.scheduler-enabled` | `false` until migration/configuration verification |
| `native-push.dispatch-delay-ms` | `15000` |
| `native-push.fcm.project-id` | Real Firebase project ID, equal to service-account JSON `project_id` |
| `native-push.fcm.service-account-file` | Absolute protected service-account JSON path; `type=service_account`, RSA PKCS8 key and client email |
| `native-push.apns.private-key-file` | Absolute protected Apple AuthKey `.p8` PKCS8 P-256 path |
| `native-push.apns.key-id` | Apple 10-character Key ID |
| `native-push.apns.team-id` | Apple 10-character Team ID |
| `native-push.apns.topic` | Actual signed application Bundle ID |
| `native-push.apns.environment` | Explicit `sandbox` or `production`, matching the package's `aps-environment` entitlement; no default |

Firebase Cloud Messaging API must be enabled, with narrowly authorized service-account permission to send in the selected project. Java exchanges an RS256 signed OAuth assertion at Google's fixed OAuth endpoint using only `https://www.googleapis.com/auth/firebase.messaging`; cached bearer tokens expire before Google's expiry. The legacy FCM server-key API is unused. Project and provider success receipt names are checked. The supplied key's `token_uri` cannot redirect credential exchange.

APNs uses Apple's fixed sandbox/production endpoints, HTTP/2, ES256 auth with JOSE P1363 64-byte signature, Key ID and Team ID. The provider JWT is reused for up to 50 minutes. Each request carries alert push type, exact topic, priority 10, and one-hour expiration. Both providers use a three-second connection timeout, ten-second request timeout, no redirects, and 16-KiB response limit. Authentication preflight plus send can take approximately 20 seconds inside the bounded installation lock.

The health component reports each provider's local configuration state and scheduler state. `CONFIGURED` confirms parsed local settings/key shape, not external authorization or successful device delivery. Optional-channel health stays UNKNOWN until both configured and scheduled, so an intentionally unopened channel does not stop the application.

## Migration and client contract

Apply the existing business-notification and transport migrations, then `scripts/migrations/20261030_native_push_direct_providers.sql`. The startup migration script registers this order. Stop workers while applying DDL. Rerunning the new additive migration preserves native bindings, proofs and epochs. It widens `client_id` to 4096 ASCII characters, adds `provider`, and indexes a SHA-256 digest of `provider + NUL + token` through a unique key, avoiding overlong token indexes. Provider receipt storage grows to 512 characters. Old published migrations are unchanged.

Existing rows default to `UNIPUSH`; their active binding is disabled once, epoch advanced, and pending deliveries cancelled. They cannot be discovered or sent by the new worker. Only an explicit authenticated registration with a genuine native token and existing private binding proof can activate the new provider. Never relabel an old CID as FCM/APNs.

Routes and response envelopes remain unchanged:

- `PUT /api/me/push-installations/{installationId}` with `{clientId,provider:'FCM'|'APNS',platform:'android'|'ios',permission:'granted'|'denied'|'unknown',appVersion}`. `clientId` now represents the actual native token. Android requires FCM with its native colon-containing long token, 100–4096 ASCII characters; iOS requires APNS and a nonempty even-length hexadecimal encoding of the native token, up to 4096 characters, canonicalized to lowercase (Apple does not guarantee a fixed token byte length). Provider/platform mismatch, old CID, malformed or oversized tokens are rejected. These syntax checks are not provider attestation.
- The PUT and same-path DELETE require the current authorized APP session for exactly this installation, plus `X-Push-Binding-Token`. Generate and persist 32 random bytes before first registration; send the 43–128-character URL-safe proof and retain it across account switches/logout. Only its SHA-256 hash is stored and checked in constant time. Same token cannot be taken over by a different installation, even when inactive. Lost proof needs an operator-reviewed reset or new native installation/token, with no insecure takeover fallback.
- `GET /api/me/push-notifications/{notificationId}` returns `ApiResult<{notificationId:string,route}>` for the current owning user, including target device/conversation ownership checks. Exact business route allowlist and the five notification kinds are unchanged.
- `POST /api/me/push-notifications/{notificationId}/opened` with `{installationId}` records the separate open fact once for the current user's attempted current-epoch delivery, including UNKNOWN outcomes. It never fabricates provider acceptance. Auth/session ownership, epoch, proof and target checks retain their original meaning.

Provider/token/user/session-chain/permission/platform changes and reactivation advance the epoch and cancel pending work. Idempotent refresh and app-version-only updates retain epoch/time. Final send still locks account and installation; checks current APP session chain/context, account/audience/revocation/idle TTL, proof-established binding epoch, notification age and preference; and holds locks through the provider call. An already accepted generic notification can arrive after logout/account switch; click authorization protects the private target again.

## Payload and result semantics

Both transports contain only generic Chinese/English/Vietnamese lockscreen text plus string `notificationId` and `deliveryId`; no amount, credentials, device/security detail, support body or trusted route. FCM sends notification plus data, `uvel_notifications` Android channel, one-hour TTL, and default launcher handling. Foreground Android rendering belongs to the native plugin; background system rendering must not be duplicated. APNs alert and top-level IDs feed the native notification delegates. H5 never registers native tokens. Android FCM requires working Google Play services; absence must remain explicit unavailable, with inbox fallback.

Business discovery remains limited to five existing kinds, fixed migration cutover, 24-hour retention, same-user business facts, system preference/critical priority, and post-binding notifications. The existing narrow critical-account-security re-login recovery exception remains. Push does not mutate business/accounting facts or `nx_notification.push_status`.

`ACCEPTED` means the provider returned a validated receipt (real FCM message resource name or APNs `apns-id`); it does not mean notification display/device delivery. `INVALID_TOKEN` deactivates only explicit FCM UNREGISTERED or APNs Unregistered, plus locally malformed tokens. Generic FCM INVALID_ARGUMENT and APNs BadDeviceToken (including sandbox/production mismatch), BadTopic/DeviceTokenNotForTopic/auth failures remain `BLOCKED`, preserving tokens until configuration is fixed. APNs ExpiredProviderToken also clears the cached authentication JWT, so the next blocked recovery generates fresh credentials. Explicit 429/500/503 rejections retry with bounded backoff, minimum one minute, at most five attempts; Retry-After seconds/date are honored up to 24 hours. Auth/configuration errors are BLOCKED and rechecked every five minutes without consuming send allowance.

Transport timeout/network failure after beginning the native send becomes terminal `UNKNOWN`, because acceptance may already have happened. Expired SENDING worker leases also become UNKNOWN rather than resend. Direct FCM/APNs provide no delivery-ID deduplication guarantee; do not reset UNKNOWN without provider/device evidence, and never claim exactly-once native delivery. Explicit preflight OAuth transient failure can retry because native send has not started. Lease tokens still fence stale worker completion; notification opens remain independent from provider response loss.

## Verification and rollback

Targeted Java checks: `mvn -Dtest=ffdd.opsconsole.content.push.*Test test`. Controlled HTTP tests verify cryptographic JWT signatures, scope/claims, native payloads, endpoints, configuration and result classifications. `NativePushMySqlTest` uses only disposable `native_push_it_<uuid>` schemas at `NEXION_NATIVE_PUSH_MYSQL_URL`, `_USER`, `_PASSWORD`; it covers rerunnable migration, 4096-character token/digest uniqueness, legacy isolation, epoch and lease fencing, preferences and current-session ownership. It never uses real accounts or sends a push.

Stop sending by disabling both backend flags. Preserve inbox/installation/delivery facts and UNKNOWN outcomes. The additive schema can remain during code rollback; rolling back to UniPush requires separately validated old application/provider configuration and must never feed native tokens to an old bridge. Do not delete receipts to simulate rollback.

Still required externally: approved Firebase project and Android public configuration; Apple identifiers/key/topic and entitlements; correctly signed native packages with the real plugin/SDK; TEST configuration and target verification; separately authorized Android/iOS foreground/background/terminated-app notification, permission denial/recovery, account-switch and click acceptance. A Maven pass is not these results.

Official references checked 2026-10-30: [FCM HTTP v1 authorization](https://firebase.google.com/docs/cloud-messaging/auth-server), [FCM send method](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send), [FCM errors](https://firebase.google.com/docs/cloud-messaging/error-codes), [APNs token connection](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns), [APNs error responses](https://developer.apple.com/documentation/usernotifications/handling-error-responses-from-apns).
