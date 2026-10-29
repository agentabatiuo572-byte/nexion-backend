'use strict';
const crypto = require('node:crypto');

const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const sign = (secret, timestamp, nonce, body) => crypto.createHmac('sha256', secret)
  .update(`${timestamp}\n${nonce}\n${hash(body)}`).digest('hex');

function verify(event, secret, now = Date.now()) {
  if (!secret || secret.length < 32) throw new Error('BRIDGE_NOT_CONFIGURED');
  if (event.httpMethod !== 'POST' || typeof event.body !== 'string' || event.isBase64Encoded)
    throw new Error('INVALID_REQUEST');
  if (Buffer.byteLength(event.body, 'utf8') > 4096) throw new Error('BODY_TOO_LARGE');
  const headers = Object.fromEntries(Object.entries(event.headers || {}).map(([k, v]) => [k.toLowerCase(), v]));
  const timestamp = headers['x-push-timestamp'];
  const nonce = headers['x-push-nonce'];
  const signature = headers['x-push-signature'];
  if (!/^\d{10}$/.test(timestamp || '') || Math.abs(now / 1000 - Number(timestamp)) > 120
      || !/^[a-f0-9-]{36}$/.test(nonce || '') || !/^[a-f0-9]{64}$/.test(signature || '')) throw new Error('INVALID_SIGNATURE');
  if (!crypto.timingSafeEqual(Buffer.from(signature, 'hex'), Buffer.from(sign(secret, timestamp, nonce, event.body), 'hex')))
    throw new Error('INVALID_SIGNATURE');
  const input = JSON.parse(event.body);
  if (!input || typeof input !== 'object' || Array.isArray(input)
      || !/^[A-Za-z0-9]{10,32}$/.test(input.requestId || '') || !/^[1-9][0-9]{0,18}$/.test(input.notificationId || '')
      || !/^[A-Za-z0-9_-]{8,128}$/.test(input.clientId || '') || typeof input.language !== 'string' || input.language.length > 32)
    throw new Error('INVALID_BODY');
  if (Object.keys(input).some(k => !['requestId', 'notificationId', 'clientId', 'language'].includes(k))) throw new Error('INVALID_BODY');
  return { input, nonce: hash(nonce), requestHash: hash(event.body) };
}

function notice(language) {
  const lang = language.toLowerCase();
  if (lang.startsWith('zh')) return { title: 'UVEL 通知', content: '您有一条新消息，请打开 UVEL 查看。' };
  if (lang.startsWith('vi')) return { title: 'Thông báo UVEL', content: 'Bạn có tin nhắn mới. Mở UVEL để xem.' };
  return { title: 'UVEL notification', content: 'You have a new message. Open UVEL to view it.' };
}

function acceptedReceipt(result, cid) {
  if (!result || ![0, '0'].includes(result.errCode) || !result.data || typeof result.data !== 'object') return null;
  for (const [taskId, statuses] of Object.entries(result.data)) {
    if (statuses && typeof statuses === 'object' && ['successed_online', 'successed_offline'].includes(statuses[cid]))
      return { status: 'ACCEPTED', taskId };
    if (statuses && typeof statuses === 'object' && statuses[cid] === 'successed_ignore') return { status: 'INVALID_CID' };
  }
  return null;
}

// Store implements persistent unique replay receipts + delivery receipts; test adapter makes all external effects controllable.
function createHandler({ store, getPushManager, secret, appid, enabled = false, now = Date.now }) {
  return async event => {
    const response = (statusCode, body) => ({ statusCode, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    let checked;
    try { checked = verify(event, secret, now()); }
    catch (error) { return response(error.message === 'BRIDGE_NOT_CONFIGURED' ? 503 : 400, { error: 'BRIDGE_REQUEST_REJECTED' }); }
    const { input, nonce, requestHash } = checked;
    if (!enabled || !appid) return response(503, { error: 'BRIDGE_NOT_ENABLED' });
    try {
      if (!await store.claimNonce(nonce, now() + 300000)) return response(409, { error: 'REPLAY_REJECTED' });
      const receipt = await store.claimDelivery(input.requestId, requestHash);
      if (receipt.requestHash !== requestHash) return response(409, { error: 'REQUEST_ID_CONFLICT' });
      if (!receipt.claimed) return response(200, { requestId: input.requestId, status: receipt.status === 'SENDING' ? 'UNKNOWN' : receipt.status,
        ...(receipt.taskId ? { taskId: receipt.taskId } : {}) });
      const manager = getPushManager({ appid });
      let result;
      let sending = false;
      try {
        const detail = await manager.getClientDetailByCid(input.clientId);
        if (!detail || ![0, '0'].includes(detail.errCode)) result = { status: 'RETRY' };
        else if (Array.isArray(detail.data?.invalidCids) && detail.data.invalidCids.includes(input.clientId)) result = { status: 'INVALID_CID' };
        else if (!detail.data?.validCids?.[input.clientId]) result = { status: 'RETRY' };
        else {
          // Persisting SENDING before the call makes an interrupted/ambiguous send fail closed rather than send twice.
          sending = true;
          const provider = await manager.sendMessage({ push_clientid: input.clientId, request_id: input.requestId,
            ...notice(input.language), payload: { notificationId: input.notificationId }, force_notification: true,
            settings: { ttl: 3600000 } });
          result = acceptedReceipt(provider, input.clientId)
            || { status: provider && ![0, '0'].includes(provider.errCode) ? 'RETRY' : 'UNKNOWN' };
        }
      } catch (error) {
        // A thrown send may already have reached the provider. No automatic retry of that ambiguous operation.
        result = { status: sending ? 'UNKNOWN' : 'RETRY' };
      }
      await store.finish(input.requestId, result);
      return response(200, { requestId: input.requestId, ...result });
    } catch (error) {
      return response(503, { error: 'BRIDGE_STORAGE_UNAVAILABLE' });
    }
  };
}

function databaseStore(db) {
  const nonces = db.collection('native_push_nonce');
  const receipts = db.collection('native_push_receipt');
  return {
    async claimNonce(id, expiresAt) {
      try { await nonces.add({ _id: id, expiresAt: new Date(expiresAt) }); return true; }
      catch (error) {
        const found = await nonces.doc(id).get();
        if (found.data?.length) return false;
        throw error;
      }
    },
    async claimDelivery(id, requestHash) {
      try { await receipts.add({ _id: id, requestHash, status: 'SENDING', updatedAt: new Date() }); return { claimed: true, requestHash }; }
      catch (error) {
        const existing = (await receipts.doc(id).get()).data?.[0];
        if (!existing) throw error;
        if (existing.requestHash === requestHash && existing.status === 'RETRY') {
          const changed = await receipts.where({ _id: id, requestHash, status: 'RETRY' }).update({ status: 'SENDING', updatedAt: new Date() });
          if (changed.updated === 1) return { claimed: true, requestHash };
          return { ...existing, status: 'SENDING' };
        }
        return existing;
      }
    },
    async finish(id, result) { await receipts.doc(id).update({ ...result, updatedAt: new Date() }); }
  };
}

exports.main = async event => createHandler({ store: databaseStore(uniCloud.database()),
  getPushManager: options => uniCloud.getPushManager(options), secret: process.env.NATIVE_PUSH_BRIDGE_SECRET,
  appid: process.env.NATIVE_PUSH_APP_ID, enabled: process.env.NATIVE_PUSH_ENABLED === 'true' })(event);
exports.createHandler = createHandler;
exports.verify = verify;
exports.sign = sign;
exports.notice = notice;
exports.acceptedReceipt = acceptedReceipt;
exports.databaseStore = databaseStore;
