import http from 'node:http';
import { writeFile } from 'node:fs/promises';

const baseUrl = new URL(process.env.TEAM_ACCEPTANCE_BASE_URL ?? 'http://127.0.0.1:18110');
const runId = required('TEAM_ACCEPTANCE_RUN_ID');
const password = required('TEAM_ACCEPTANCE_PASSWORD');
const outputPath = required('TEAM_ACCEPTANCE_OUTPUT');
const fixedOtp = process.env.TEAM_ACCEPTANCE_FIXED_OTP ?? '123456';

const sponsors = new Map([
  ['R', null], ['A', 'R'], ['A1', 'A'], ['A11', 'A1'], ['A12', 'A11'],
  ['A13', 'A12'], ['A14', 'A13'], ['Buyer', 'A14'], ['A2', 'A'], ['A3', 'A'],
  ['B', 'R'], ['B1', 'B'], ['B2', 'B'], ['B3', 'B'], ['C', 'R'], ['Q', 'R'], ['O', null],
]);
const roles = [...sponsors.keys()];
const accounts = new Map();
const assertions = [];
const suffix = digitsFromRunId(runId);

for (let index = 0; index < roles.length; index += 1) {
  const role = roles[index];
  const sponsorRole = sponsors.get(role);
  const sponsorCode = sponsorRole ? accounts.get(sponsorRole)?.referralCode : null;
  assert(!sponsorRole || sponsorCode, `${role} sponsor ${sponsorRole} must already exist`);
  const phone = `9${suffix}${String(index + 1).padStart(2, '0')}`.slice(-10);
  const localAddress = `127.10.0.${index + 10}`;
  const otp = await api('/auth/users/register/otp/send', {
    method: 'POST', localAddress, body: { countryCode: '+1', phone },
  });
  assertApiOk(otp, `${role} OTP`);
  const challengeNo = otp.json?.data?.challengeNo;
  assert(typeof challengeNo === 'string' && challengeNo.startsWith('REG-'), `${role} OTP challenge missing`);
  const registration = await api('/auth/users/register', {
    method: 'POST', localAddress,
    body: { countryCode: '+1', phone, challengeNo, code: fixedOtp, password, sponsorCode },
  });
  assertApiOk(registration, `${role} registration`);
  const userId = Number(registration.json?.data?.user?.userId);
  const accessToken = registration.json?.data?.accessToken;
  assert(Number.isSafeInteger(userId) && userId > 0, `${role} userId missing`);
  assert(typeof accessToken === 'string' && accessToken.length > 20, `${role} access token missing`);
  const referral = await api('/api/app/referral-code', { token: accessToken, localAddress });
  assertApiOk(referral, `${role} referral code`);
  const referralCode = referral.json?.data?.referralCode;
  assert(typeof referralCode === 'string' && referralCode.length >= 4, `${role} referral code missing`);
  accounts.set(role, { role, userId, phone, localAddress, referralCode, accessToken, sponsorRole });
}

await verifyNetwork('R', 15, 4, { Buyer: 7, Q: 1 });
await verifyNetwork('A', 8, 3, { Buyer: 6, A1: 1, A2: 1, A3: 1 });
await verifyNetwork('B', 3, 3, { B1: 1, B2: 1, B3: 1 });
await verifyNetwork('Buyer', 0, 0, {});
await verifyNetwork('O', 0, 0, {});

// Identity comes only from the bearer token. Query-string impersonation must not expose R to O.
const outsideProbe = await api(`/api/app/team/network?userId=${accounts.get('R').userId}`, {
  token: accounts.get('O').accessToken,
  localAddress: accounts.get('O').localAddress,
});
assertApiOk(outsideProbe, 'outside-team identity probe');
assert(outsideProbe.json?.data?.totalMembers === 0, 'O must not read R network through userId query');
assertions.push({ name: 'outside-team-query-ignored', passed: true });

// Reusing a consumed challenge with another sponsor cannot mutate the existing immutable relation.
const replay = await api('/auth/users/register', {
  method: 'POST', localAddress: accounts.get('A').localAddress,
  body: {
    countryCode: '+1', phone: accounts.get('A').phone,
    challengeNo: 'REG-CONSUMED-NONEXISTENT', code: fixedOtp, password,
    sponsorCode: accounts.get('B').referralCode,
  },
});
assert(replay.json?.code !== 0, 'registration replay must fail');
await verifyNetwork('R', 15, 4, { A: 1, B: 1, C: 1, Q: 1 });
assertions.push({ name: 'registration-replay-does-not-rebind', passed: true, resultCode: replay.json?.code });

// A fresh login must reconstruct the same server-authoritative tree.
const relogin = await api('/auth/users/login', {
  method: 'POST', localAddress: accounts.get('R').localAddress,
  body: { countryCode: '+1', phone: accounts.get('R').phone, password },
});
assertApiOk(relogin, 'R relogin');
const oldRToken = accounts.get('R').accessToken;
accounts.get('R').accessToken = relogin.json?.data?.accessToken;
await verifyNetwork('R', 15, 4, { Buyer: 7 });
accounts.get('R').accessToken = oldRToken;
assertions.push({ name: 'relogin-server-authority', passed: true });

const secretFree = {
  runId,
  generatedAt: new Date().toISOString(),
  baseUrl: baseUrl.origin,
  roles: roles.map((role) => {
    const account = accounts.get(role);
    return {
      role,
      userId: account.userId,
      phoneMasked: `******${account.phone.slice(-4)}`,
      sourceIp: account.localAddress,
      sponsorRole: account.sponsorRole,
      referralCode: account.referralCode,
    };
  }),
  assertions,
};
await writeFile(outputPath, `${JSON.stringify(secretFree, null, 2)}\n`, 'utf8');
process.stdout.write(`${JSON.stringify({ runId, accountCount: roles.length, assertionCount: assertions.length, outputPath })}\n`);

async function verifyNetwork(role, totalMembers, directMembers, expectedLayers) {
  const account = accounts.get(role);
  const response = await api('/api/app/team/network', { token: account.accessToken, localAddress: account.localAddress });
  assertApiOk(response, `${role} network`);
  const data = response.json?.data;
  assert(data?.serverCanonical === true, `${role} network must be server canonical`);
  assert(data?.source === 'server', `${role} network source must be server`);
  assert(data?.totalMembers === totalMembers, `${role} total expected ${totalMembers}, got ${data?.totalMembers}`);
  assert(data?.directMembers === directMembers, `${role} directs expected ${directMembers}, got ${data?.directMembers}`);
  const byId = new Map((data?.members ?? []).map((member) => [Number(member.id), member]));
  for (const [memberRole, layer] of Object.entries(expectedLayers)) {
    const member = byId.get(accounts.get(memberRole).userId);
    assert(member, `${role} network missing ${memberRole}`);
    assert(member.layer === layer, `${role}->${memberRole} expected L${layer}, got L${member.layer}`);
  }
  assertions.push({ name: `${role}-network`, passed: true, totalMembers, directMembers });
}

function api(path, { method = 'GET', body, token, localAddress } = {}) {
  const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
  const headers = { Accept: 'application/json' };
  if (payload) {
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = String(payload.length);
  }
  if (token) headers.Authorization = `Bearer ${token}`;
  return new Promise((resolve, reject) => {
    const request = http.request({
      protocol: baseUrl.protocol,
      hostname: baseUrl.hostname,
      port: baseUrl.port,
      path,
      method,
      headers,
      localAddress,
      timeout: 15_000,
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let json;
        try { json = raw ? JSON.parse(raw) : null; } catch { json = null; }
        resolve({ status: response.statusCode, json, raw: raw.slice(0, 500) });
      });
    });
    request.on('timeout', () => request.destroy(new Error(`timeout ${path}`)));
    request.on('error', reject);
    if (payload) request.write(payload);
    request.end();
  });
}

function assertApiOk(response, label) {
  assert(response.status >= 200 && response.status < 300, `${label} HTTP ${response.status}: ${response.raw}`);
  assert(response.json?.code === 0, `${label} API ${response.json?.code}: ${response.json?.message ?? response.raw}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function digitsFromRunId(value) {
  let hash = 2166136261;
  for (const char of value) {
    hash ^= char.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return String(hash >>> 0).padStart(8, '0').slice(-7);
}
