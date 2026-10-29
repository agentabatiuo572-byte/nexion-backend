'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { createHandler, sign, verify, notice } = require('./index');
const secret = 'test-only-secret-'.repeat(3);
const now = 1793280000000;
const input = { requestId: 'n1000000000000000000000000000001', notificationId: '123', clientId: 'cid12345678', language: 'zh' };
function event(body = input) {
  const raw = JSON.stringify(body), nonce = crypto.randomUUID(), timestamp = String(now / 1000);
  return { httpMethod: 'POST', body: raw, headers: { 'X-Push-Timestamp': timestamp, 'X-Push-Nonce': nonce,
    'X-Push-Signature': sign(secret, timestamp, nonce, raw) } };
}
function fixture(overrides = {}) {
  const nonces = new Set(), receipts = new Map();
  let sends = 0;
  const store = {
    async claimNonce(id) { if (nonces.has(id)) return false; nonces.add(id); return true; },
    async claimDelivery(id, requestHash) {
      const old = receipts.get(id);
      if (old && old.status !== 'RETRY') return old;
      const receipt = { status: 'SENDING', requestHash }; receipts.set(id, receipt); return { ...receipt, claimed: true };
    },
    async finish(id, result) { Object.assign(receipts.get(id), result); }
  };
  const manager = {
    async getClientDetailByCid(cid) { return { errCode: 0, data: { validCids: { [cid]: {} }, invalidCids: [] } }; },
    async sendMessage(message) {
      sends++; assert.deepEqual(message.payload, { notificationId: '123' });
      assert.equal(message.push_clientid, input.clientId); assert.equal(message.settings.ttl, 3600000);
      assert.equal(message.force_notification, true); assert.equal(message.open_url, undefined);
      return { errCode: 0, data: { task123: { [input.clientId]: 'successed_offline' } } };
    }, ...overrides
  };
  const handler = createHandler({ store, getPushManager: ({ appid }) => { assert.equal(appid, 'test-app'); return manager; }, secret,
    appid: 'test-app', enabled: true, now: () => now });
  return { handler, receipts, sends: () => sends };
}
const body = response => JSON.parse(response.body);

test('HMAC authenticates exact bytes, rejects timestamps, missing proof, wrong method and oversized bodies', () => {
  assert.equal(verify(event(), secret, now).input.clientId, input.clientId);
  const forged = event(); forged.body = forged.body.replace('123', '124');
  assert.throws(() => verify(forged, secret, now), /INVALID_SIGNATURE/);
  assert.throws(() => verify(event(), secret, now + 121000), /INVALID_SIGNATURE/);
  assert.throws(() => verify({ ...event(), httpMethod: 'GET' }, secret, now), /INVALID_REQUEST/);
  assert.throws(() => verify(event({ ...input, userId: 'victim' }), secret, now), /INVALID_BODY/);
  assert.throws(() => verify({ ...event(), body: 'x'.repeat(4097) }, secret, now), /BODY_TOO_LARGE/);
});
test('provider acceptance is receipt only; replay and fresh HTTP retry never send twice', async () => {
  const f = fixture(), request = event();
  const first = await f.handler(request); assert.equal(first.statusCode, 200); assert.equal(body(first).status, 'ACCEPTED');
  assert.equal(body(first).taskId, 'task123'); assert.equal((await f.handler(request)).statusCode, 409);
  assert.equal(body(await f.handler(event())).status, 'ACCEPTED'); assert.equal(f.sends(), 1);
});
test('persistent SENDING after a crash becomes UNKNOWN without repeating an ambiguous send', async () => {
  const f = fixture(); f.receipts.set(input.requestId, { status: 'SENDING', requestHash: crypto.createHash('sha256').update(JSON.stringify(input)).digest('hex') });
  assert.equal(body(await f.handler(event())).status, 'UNKNOWN'); assert.equal(f.sends(), 0);
});
test('invalid and ignored CID is deactivated rather than accepted', async () => {
  const invalid = fixture({ async getClientDetailByCid() { return { errCode: 0, data: { invalidCids: [input.clientId] } }; } });
  assert.equal(body(await invalid.handler(event())).status, 'INVALID_CID'); assert.equal(invalid.sends(), 0);
  const ignored = fixture({ async sendMessage() { return { errCode: 0, data: { task: { [input.clientId]: 'successed_ignore' } } }; } });
  assert.equal(body(await ignored.handler(event())).status, 'INVALID_CID');
});
test('read-only provider failure retries; send timeout is UNKNOWN; malformed acceptance never succeeds', async () => {
  let count = 0;
  const read = fixture({ async getClientDetailByCid(cid) { if (++count === 1) throw Error('timeout'); return { errCode: 0, data: { validCids: { [cid]: {} } } }; } });
  assert.equal(body(await read.handler(event())).status, 'RETRY'); assert.equal(body(await read.handler(event())).status, 'ACCEPTED');
  const timeout = fixture({ async sendMessage() { throw Error('timeout after upstream might accept'); } });
  assert.equal(body(await timeout.handler(event())).status, 'UNKNOWN');
  const malformed = fixture({ async sendMessage() { return { errCode: 0 }; } });
  assert.equal(body(await malformed.handler(event())).status, 'UNKNOWN');
});
test('all lock-screen languages are generic and never copy caller data', async () => {
  for (const language of ['zh-CN', 'en', 'vi']) {
    const text = notice(language); assert.ok(text.title.length < 20); assert.ok(text.content.length < 50);
    assert.ok(!/password|USDT|amount|123|cid/i.test(text.title + text.content));
  }
  const f = fixture(); assert.equal((await f.handler(event({ ...input, route: 'https://evil' }))).statusCode, 400); assert.equal(f.sends(), 0);
});
test('channel disabled and missing secret do not call SDK', async () => {
  const handler = createHandler({ store: {}, getPushManager() { throw Error('must not call'); }, secret, appid: 'test', enabled: false, now: () => now });
  assert.equal((await handler(event())).statusCode, 503);
  const noSecret = createHandler({ store: {}, getPushManager() { throw Error('must not call'); }, appid: 'test', enabled: true, now: () => now });
  assert.equal((await noSecret(event())).statusCode, 503);
});
