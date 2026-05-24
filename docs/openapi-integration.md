# Nexion OpenAPI Integration Guide

The OpenAPI service is exposed through Gateway at `/api/openapi/**`.

## Signed Request Headers

Partner APIs under `/api/openapi/v1/**` use HMAC-SHA256 request signing.

Required headers:

- `X-Nexion-App-Key`: app key returned by `POST /api/openapi/apps`
- `X-Nexion-Timestamp`: Unix epoch seconds
- `X-Nexion-Nonce`: unique nonce for this app key
- `X-Nexion-Signature`: lowercase hex HMAC-SHA256 signature

The signature window defaults to 300 seconds. Nonces are stored in `nx_openapi_nonce` for the configured TTL, so replaying the same nonce is rejected.

String to sign:

```text
appKey + "\n" + timestamp + "\n" + nonce + "\n" + sha256(canonicalJsonBody)
```

`canonicalJsonBody` must be the exact JSON payload sent on the wire. The backend canonicalizer sorts object properties alphabetically, so client examples should also emit stable JSON.

## JavaScript Signing Example

```javascript
import crypto from "node:crypto";

function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function sha256(value) {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function hmacSha256(secret, value) {
  return crypto.createHmac("sha256", secret).update(value, "utf8").digest("hex");
}

const appKey = process.env.NEXION_APP_KEY;
const appSecret = process.env.NEXION_APP_SECRET;
const timestamp = Math.floor(Date.now() / 1000).toString();
const nonce = crypto.randomUUID();

const body = {
  clientName: "partner-sdk",
  rewardNex: 1,
  rewardUsdt: 0.1,
  taskType: "AI_INFERENCE",
  userDeviceId: 1
};

const payload = stableStringify(body);
const stringToSign = `${appKey}\n${timestamp}\n${nonce}\n${sha256(payload)}`;
const signature = hmacSha256(appSecret, stringToSign);

console.log({ timestamp, nonce, signature, payload });
```

## curl Example

Generate a timestamp, nonce, and signature with the JavaScript helper above, then send the same `payload` string:

```bash
curl -X POST "http://127.0.0.1:8090/api/openapi/v1/compute/receipts" \
  -H "Content-Type: application/json" \
  -H "X-Nexion-App-Key: nxak_xxx" \
  -H "X-Nexion-Timestamp: 1710000000" \
  -H "X-Nexion-Nonce: 018f1f5d-6e2d-7d70-b7b7-f5f38d82c2e1" \
  -H "X-Nexion-Signature: lowercase_hex_signature" \
  --data '{"clientName":"partner-sdk","rewardNex":1,"rewardUsdt":0.1,"taskType":"AI_INFERENCE","userDeviceId":1}'
```

## Ops/Admin APIs

Ops endpoints require an authenticated admin token with `PERM_OPENAPI_ADMIN`.

- `GET /api/openapi/ops/apps?status=&appKey=&ownerUserId=&limit=20`
- `POST /api/openapi/ops/apps/{appId}/enable`
- `POST /api/openapi/ops/apps/{appId}/disable`
- `PATCH /api/openapi/ops/apps/{appId}/quotas`
- `GET /api/openapi/ops/call-audits?appId=&appKey=&apiPath=&responseCode=&limit=20`
- `POST /api/openapi/webhooks/deliveries/publish?limit=20`
- `GET /api/openapi/webhooks/deliveries?status=&appId=&eventType=&limit=20`
- `GET /api/openapi/webhooks/deliveries/pending|success|failed|dead|summary`

Quota update body:

```json
{
  "qpsLimit": 50,
  "dailyLimit": 200000,
  "remark": "temporary launch quota"
}
```

App list responses never return `appSecret`.

## Webhook Verification

Webhook deliveries are signed with the webhook subscription secret returned when the subscription is created.

Delivery headers:

- `X-Nexion-Webhook-Id`
- `X-Nexion-Event-Type`
- `X-Nexion-Timestamp`
- `X-Nexion-Signature`

Webhook string to sign:

```text
webhookId + "\n" + eventType + "\n" + timestamp + "\n" + sha256(rawRequestBody)
```

Node.js/Express-style verification:

```javascript
import crypto from "node:crypto";

function sha256(value) {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function hmacSha256(secret, value) {
  return crypto.createHmac("sha256", secret).update(value, "utf8").digest("hex");
}

function timingSafeEqualHex(left, right) {
  const a = Buffer.from(left, "hex");
  const b = Buffer.from(right, "hex");
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export function verifyNexionWebhook(headers, rawBody, webhookSecret) {
  const webhookId = headers["x-nexion-webhook-id"];
  const eventType = headers["x-nexion-event-type"];
  const timestamp = headers["x-nexion-timestamp"];
  const signature = headers["x-nexion-signature"];

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(timestamp)) > 300) {
    return false;
  }

  const stringToSign = `${webhookId}\n${eventType}\n${timestamp}\n${sha256(rawBody)}`;
  const expected = hmacSha256(webhookSecret, stringToSign);
  return timingSafeEqualHex(expected, signature);
}
```

Use the raw request body for verification. Parsing and re-stringifying JSON can change field order or formatting and will invalidate the signature.
