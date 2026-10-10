import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

// BEGIN promotion concurrency facts gate (also exercised without the real driver).
function validatePromotionConcurrency(report) {
  const row = (value, label) => assert(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0, `Missing ${label}`);
  const text = (value, label) => assert(typeof value === 'string' && value.length > 0, `Missing ${label}`);
  const decimal = value => {
    assert(typeof value === 'string' || typeof value === 'number', 'Decimal fact must be a scalar');
    const raw = String(value);
    assert(/^-?\d+(?:\.\d+)?$/.test(raw), 'Missing or invalid decimal fact');
    const [whole, fraction = ''] = raw.split('.');
    const normalized = `${whole.replace(/^(-?)0+(?=\d)/, '$1')}${fraction.replace(/0+$/, '') ? '.' + fraction.replace(/0+$/, '') : ''}`;
    return /^-?0$/.test(normalized) ? '0' : normalized;
  };
  const amount = (actual, expected, label) => assert.equal(decimal(actual), decimal(expected), label);
  const positiveId = (value, label) => assert((typeof value === 'string' || typeof value === 'number') && /^[1-9]\d*$/.test(String(value)), `Missing or invalid ${label}`);
  const wallet = value => { row(value, 'wallet'); for (const field of ['usdt_available', 'nex_available']) decimal(value[field]); };
  const lock = (value, table, record) => {
    row(value, 'observed lock wait');
    for (const key of ['requestConnection', 'blockConnection', 'requestTransaction', 'blockTransaction']) positiveId(value[key], key);
    assert.notEqual(String(value.requestConnection), String(value.blockConnection), 'Lock connections must differ');
    assert.notEqual(String(value.requestTransaction), String(value.blockTransaction), 'Lock transactions must differ');
    assert.equal(value.objectSchema, 'growth_promotions_20261007');
    assert.equal(value.objectName, table); assert.equal(value.indexName, 'PRIMARY');
    assert.equal(value.lockedRecord, record, 'Lock wait must be on the scenario root');
    for (const key of ['requestMode', 'blockMode']) assert(typeof value[key] === 'string' && /^X(?:,REC_NOT_GAP)?$/.test(value[key]), 'Expected exclusive record lock');
  };
  const receipt = (value, asset) => {
    row(value, 'issued asset receipt'); text(value.issuedAt, 'receipt issue time');
    assert(Number.isFinite(Date.parse(value.issuedAt)), 'Invalid receipt issue time');
    assert(Array.isArray(value.deviceIds) && Array.isArray(value.instanceNos), 'Missing device receipt arrays');
    assert.equal(value.source, asset === 'DEVICE' ? 'PROMOTION_GIFT' : 'PROMOTION_REWARD');
    if (asset === 'DEVICE') {
      assert.equal(value.deviceIds.length, 1); assert.equal(value.instanceNos.length, 1);
      positiveId(value.deviceIds[0], 'device receipt ID'); text(value.instanceNos[0], 'device instance');
    } else {
      assert.equal(value.deviceIds.length, 0); assert.equal(value.instanceNos.length, 0);
      text(value.ledgerBizNo, 'reward ledger'); text(value.earningsEntryNo, 'earnings entry');
    }
  };
  assert.equal(report.completed, true, 'Concurrency report did not complete');
  assert.equal(report.scenarioCount, 13, 'Concurrency report must contain all 13 dimensions');
  assert(Array.isArray(report.checks)); assert.equal(report.checks.length, 13);
  const dimensions = new Map();
  for (const check of report.checks) {
    row(check, 'scenario facts'); text(check.run, 'scenario run'); text(check.activity, 'scenario activity');
    const key = [check.scenario, check.asset ?? '', check.firstAction ?? ''].join(':');
    assert(!dimensions.has(key), 'Duplicate concurrency dimension'); dimensions.set(key, check);
  }
  const take = (scenario, asset = '', action = '') => {
    const key = [scenario, asset, action].join(':');
    assert(dimensions.has(key), `Missing concurrency dimension ${key}`);
    const check = dimensions.get(key); dimensions.delete(key); return check;
  };
  for (const asset of ['DEVICE', 'USDT', 'NEX']) {
    const oldAmount = asset === 'DEVICE' ? '1' : '1.000001';
    const raceActivity = dimensions.get(`issue-refund-lock-order:${asset}:ISSUE`)?.activity;
    for (const action of ['ISSUE', 'REFUND']) {
      const check = take('issue-refund-lock-order', asset, action), issued = action === 'ISSUE';
      assert.equal(check.activity, raceActivity, 'Both lock orders must share one activity');
      positiveId(check.buyer, 'race buyer'); text(check.order, 'race order'); text(check.obligation, 'race obligation');
      lock(check.observedLockWait, 'nx_user', String(check.buyer));
      assert.equal(check.orderState, 'REFUNDED'); row(check.reward, 'reward state');
      assert.equal(check.reward.status, issued ? 'REVERSED' : 'CANCELLED'); assert.equal(check.reward.version, 1);
      for (const field of ['orderReceiptCount', 'obligationCount', 'reservationCount', 'refundLedgerCount', 'refundHoldCount']) assert.equal(check[field], 1, field);
      assert.equal(check.refundHoldState, 'EXECUTED'); assert.equal(check.issueAttemptCount, issued ? 1 : 0);
      wallet(check.walletBefore); wallet(check.walletAfter);
      for (const field of ['usdt_available', 'nex_available']) amount(check.walletAfter[field], check.walletBefore[field], 'Wallet must have zero net change');
      assert.equal(check.deviceReceiptCount, issued && asset === 'DEVICE' ? 1 : 0);
      assert.equal(check.ownedGiftCount, 0);
      for (const field of ['rewardLedgerCount', 'reverseLedgerCount']) assert.equal(check[field], issued && asset !== 'DEVICE' ? 1 : 0, field);
      amount(check.rewardLedgerNet, '0', 'Reward ledger net'); amount(check.earningsNet, '0', 'Earnings net');
      row(check.budget, 'race budget');
      for (const field of ['reserved', 'committed', 'unrecoverable']) amount(check.budget[field], '0', field);
      for (const field of ['issued', 'reversed']) amount(check.budget[field], oldAmount, field);
      if (issued) {
        receipt(check.assetReceipt, asset); row(check.reversal, 'reversal'); assert.equal(check.reversal.status, 'REVERSED');
        amount(check.reversal.amount, oldAmount, 'Original reversal amount'); amount(check.reversal.recovered, oldAmount, 'Recovered reversal amount'); amount(check.reversal.outstanding, '0', 'Reversal outstanding');
        if (asset !== 'DEVICE') {
          assert.equal(check.assetReceipt.ledgerBizNo, check.reward.original_ledger_no);
          assert.equal(check.assetReceipt.earningsEntryNo, check.reward.original_earnings_entry_no);
        }
      } else { assert.equal(check.assetReceipt, null); assert.equal(check.reversal, null); }
    }
    const paid = take('reserved-v1-pay-after-v2-pause', asset);
    text(paid.order, 'reserved order'); text(paid.obligation, 'reserved obligation');
    assert.equal(paid.currentVersion, 2); assert.equal(paid.currentState, 'PAUSED'); assert.equal(paid.orderState, 'PAID');
    assert.equal(paid.paidBeforePayBy, 1); assert.equal(paid.reservedVersion, 1); assert.equal(paid.obligationVersion, 1);
    text(paid.oldSnapshotHash, 'old snapshot hash'); assert(/^[a-f0-9]{64}$/.test(paid.oldSnapshotHash));
    assert.equal(paid.obligationSnapshotHash, paid.oldSnapshotHash);
    row(paid.projectionBefore, 'projection before'); row(paid.projectionAfter, 'projection after');
    assert.deepEqual(paid.projectionAfter, paid.projectionBefore, 'Reserved order projection must retain v1');
    decimal(paid.projectionBefore.amountUsdt); text(paid.projectionBefore.payBy, 'reserved pay deadline');
    assert(Number.isFinite(Date.parse(paid.projectionBefore.payBy)));
    assert(Array.isArray(paid.projectionBefore.rewards) && paid.projectionBefore.rewards.length === 1);
    const reward = paid.projectionBefore.rewards[0]; row(reward, 'projected reward'); assert.equal(reward.version, 1);
    row(reward.reward, 'v1 reward terms'); assert.equal(reward.reward.type, asset);
    amount(reward.reward[asset === 'DEVICE' ? 'quantity' : 'amount'], oldAmount, 'Original projected reward amount');
    amount(paid.oldRewardAmount, oldAmount, 'Original reserved amount'); receipt(paid.assetReceipt, asset); row(paid.budget, 'paid budget');
    for (const field of ['reserved', 'committed', 'reversed']) amount(paid.budget[field], '0', field);
    amount(paid.budget.issued, oldAmount, 'Only v1 amount must be issued');
    const rejected = take('v1-quote-rejected-after-v2', asset);
    text(rejected.quote, 'rejected quote'); assert.equal(rejected.quotedVersion, 1); assert.equal(rejected.currentVersion, 2);
    assert.equal(rejected.errorCode, 409); assert.equal(rejected.error, 'PROMOTION_QUOTE_VERSION_CHANGED');
    for (const field of ['orders', 'receipts', 'reservations', 'rewardObligations']) assert.equal(rejected[field], 0, field);
    assert(Array.isArray(rejected.budgetBefore) && rejected.budgetBefore.length > 0); assert(Array.isArray(rejected.budgetAfter));
    for (const budget of rejected.budgetBefore) {
      row(budget, 'rejected budget'); assert.equal(budget.activity_id, rejected.activity); assert.equal(budget.asset, asset);
      for (const field of ['total', 'reserved', 'committed', 'issued', 'reversed', 'unrecoverable']) decimal(budget[field]);
    }
    assert.deepEqual(rejected.budgetAfter, rejected.budgetBefore, 'Rejected quote must not move budget');
    row(rejected.quoteBefore, 'quote before'); row(rejected.quoteAfter, 'quote after'); assert.equal(rejected.quoteBefore.quote_id, rejected.quote);
    text(rejected.quoteBefore.quote_json, 'saved quote'); text(rejected.quoteBefore.quote_hash, 'saved quote hash');
    assert.deepEqual(rejected.quoteAfter, rejected.quoteBefore, 'Rejected quote must remain unchanged');
    wallet(rejected.walletBefore); wallet(rejected.walletAfter); positiveId(rejected.walletBefore.user_id, 'wallet owner');
    for (const value of [rejected.walletBefore, rejected.walletAfter]) for (const field of ['id', 'user_id', 'usdt_available', 'cregis_risk_held', 'nex_available', 'pending_withdraw', 'lifetime_earned', 'cumulative_deposit_usdt', 'version', 'created_at', 'updated_at', 'is_deleted', 'sandbox', 'earnings_usdt_debited', 'earnings_nex_debited']) assert(Object.hasOwn(value, field), `Missing whole-wallet column ${field}`);
    assert.equal(String(rejected.walletBefore.user_id), String(rejected.quoteBefore.user_id));
    assert.deepEqual(rejected.walletAfter, rejected.walletBefore, 'Rejected quote must not change any wallet column');
    assert(Array.isArray(rejected.walletLedgerBefore) && rejected.walletLedgerBefore.length > 0 && Array.isArray(rejected.walletLedgerAfter) && rejected.walletLedgerAfter.length > 0, 'Missing whole-wallet ledger readback');
    let previousId = 0n;
    for (const ledger of rejected.walletLedgerBefore) {
      row(ledger, 'wallet ledger row'); positiveId(ledger.id, 'wallet ledger ID');
      text(ledger.asset, 'wallet ledger asset'); assert(['IN', 'OUT'].includes(ledger.direction), 'Missing wallet ledger direction'); decimal(ledger.amount);
      assert(BigInt(String(ledger.id)) > previousId, 'Whole-wallet ledger must be ordered by ID'); previousId = BigInt(String(ledger.id));
      assert.equal(String(ledger.user_id), String(rejected.walletBefore.user_id));
    }
    const opening = rejected.walletLedgerBefore.find(ledger => ledger.biz_no === `PTEST-INITIAL-${rejected.walletBefore.user_id}`);
    row(opening, 'fixture opening ledger');
    assert.equal(opening.biz_type, 'ISOLATED_FIXTURE_FUNDING'); assert.equal(opening.asset, 'USDT'); assert.equal(opening.direction, 'IN'); assert.equal(opening.status, 'SUCCESS');
    amount(opening.amount, '1000000', 'Fixture opening ledger amount');
    assert.deepEqual(rejected.walletLedgerAfter, rejected.walletLedgerBefore, 'Rejected quote must not change any wallet ledger row');
  }
  const gift = take('one-gift-two-accounts');
  positiveId(gift.winner, 'gift winner'); positiveId(gift.loser, 'gift loser'); assert.notEqual(String(gift.winner), String(gift.loser));
  text(gift.order, 'winning order'); text(gift.giftProduct, 'gift product');
  lock(gift.observedLockWait, 'nx_promotion', `'${gift.activity}'`);
  amount(gift.giftInitialStock, '1', 'Only one initial gift'); amount(gift.giftFinalStock, '0', 'Gift stock exhausted'); amount(gift.reservedGiftQuantity, '1', 'Gift reservation conserved');
  assert.equal(gift.activityRemainingOrders, 99); amount(gift.deviceBudgetRemaining, '99', 'Device budget must remain available');
  for (const field of ['orderCount', 'receiptCount', 'reservationCount']) assert.equal(gift[field], 1, field);
  for (const field of ['loserOrderCount', 'loserReservationCount', 'loserConsumedQuoteCount']) assert.equal(gift[field], 0, field);
  assert.equal(gift.loserErrorCode, 409); assert.equal(gift.loserError, 'PROMOTION_CAPACITY_UNAVAILABLE');
  row(gift.loserQuoteBefore, 'loser quote before'); row(gift.loserQuoteAfter, 'loser quote after');
  text(gift.loserQuoteBefore.quote_id, 'loser quote'); assert.equal(String(gift.loserQuoteBefore.user_id), String(gift.loser));
  assert.deepEqual(gift.loserQuoteAfter, gift.loserQuoteBefore, 'Losing quote must remain unchanged');
  assert.equal(dimensions.size, 0, 'Unexpected concurrency dimension');
}
function readPromotionConcurrency(directory, suffix, start) {
  assert(typeof suffix === 'string' && /^-wf-[a-f0-9]{32}$/.test(suffix), 'Invalid isolated concurrency suffix');
  const file = path.join(directory, `promotion-concurrency-runtime${suffix}.json`);
  assert(fs.statSync(file).isFile(), 'Concurrency evidence must be a file');
  assert(fs.statSync(file).mtimeMs >= start, 'Concurrency evidence is stale');
  validatePromotionConcurrency(read(file));
  return file;
}
// END promotion concurrency facts gate.

// The isolated driver runs new transactions; old evidence never satisfies this gate.
const env = process.env;
for (const key of ['WORKFLOW_TASK_ID', 'WORKFLOW_STEP_ID', 'WORKFLOW_CHECK_ID', 'WORKFLOW_RUN_ID', 'WORKFLOW_REPO', 'WORKFLOW_SNAPSHOT_HASH', 'GROWTH_PROMOTION_ACCEPTANCE_DRIVER']) assert(env[key], `Missing ${key}`);
assert.equal(path.resolve(env.WORKFLOW_REPO).toLowerCase(), process.cwd().toLowerCase(), 'Wrong repository');
const index = process.argv.indexOf('--report');
assert(index >= 0 && process.argv[index + 1], '--report is required');
const reportFile = path.resolve(process.argv[index + 1]);
const directory = path.dirname(reportFile);
fs.mkdirSync(directory, { recursive: true });
const startedAt = new Date().toISOString(), start = Date.now();
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const driver = path.resolve(env.GROWTH_PROMOTION_ACCEPTANCE_DRIVER);
const driverRoot = path.dirname(driver);
const harnessFiles = [driver, ...['prepare-owned-http-accounts.mjs', 'prepare-owned-http-wallets.mjs', 'authenticate-fixtures.mjs', 'promotion-http-acceptance.mjs', 'promotion-restart-readback.mjs', 'start-local-services.ps1'].map(file => path.join(driverRoot, file))];
const harnessHashes = () => Object.fromEntries(harnessFiles.map(file => [file, createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
const harnessBefore = harnessHashes();
const sourceDigest = () => {
  const list = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { encoding: 'utf8', windowsHide: true });
  assert.equal(list.status, 0, 'Cannot inventory source');
  const hash = createHash('sha256');
  for (const file of [...new Set(list.stdout.split('\0').filter(Boolean))].sort()) {
    hash.update(file + '\0');
    hash.update(fs.existsSync(file) ? fs.readFileSync(file) : '<deleted>');
  }
  return hash.digest('hex');
};
const before = sourceDigest();
const unique = `${env.WORKFLOW_RUN_ID}-${start}`;
const driverReport = path.join(directory, `backend-driver-${unique}.json`);
const log = path.join(directory, `backend-runtime-${unique}.log`);
const fd = fs.openSync(log, 'wx');
let result;
try {
  result = spawnSync('pwsh', ['-NoProfile', '-File', driver, '-Report', driverReport], {
    env, stdio: ['ignore', fd, fd], windowsHide: true, timeout: 540000,
  });
} finally { fs.closeSync(fd); }
assert(!result.error && result.status === 0, `Fresh isolated runtime failed: ${log}`);
assert(fs.statSync(driverReport).mtimeMs >= start, 'Driver report is stale');
const proof = read(driverReport);
assert.equal(proof.completed, true);
assert.equal(proof.database, 'growth_promotions_20261007:33339');
assert.equal(path.resolve(proof.repo).toLowerCase(), process.cwd().toLowerCase());
const evidence = [driverReport, log];
let suites = 0, totalTests = 0;
for (const file of fs.readdirSync('target/surefire-reports').filter(file => /^TEST-.*\.xml$/.test(file))) {
  const full = path.resolve('target/surefire-reports', file);
  if (fs.statSync(full).mtimeMs < start) continue;
  const raw = fs.readFileSync(full), header = raw.toString('utf8').match(/<testsuite\b[^>]*>/)?.[0] ?? '';
  const counts = Object.fromEntries([...header.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)].map(([, key, value]) => [key, Number(value)]));
  assert(counts.tests > 0 && counts.failures === 0 && counts.errors === 0 && counts.skipped === 0, `Failed, empty or skipped suite: ${file}`);
  const frozen = path.join(directory, `backend-junit-${unique}-${file}`);
  fs.copyFileSync(full, frozen, fs.constants.COPYFILE_EXCL);
  assert(fs.readFileSync(frozen).equals(raw), `Frozen suite bytes changed: ${file}`);
  suites++; totalTests += counts.tests; evidence.push(frozen);
}
assert(suites >= 33 && totalTests >= 600, 'Required promotion and existing commerce regression suites did not all run');
for (const name of ['PromotionLifecycleMySqlTest', 'PromotionDisclosureMySqlTest', 'PromotionMetricsBreakdownMySqlTest', 'PromotionReversalQueueMySqlTest', 'EarningsSourceRecoveryMySqlTest', 'AdminRbacAuthorizationFilterTest', 'AppWalletBillsServiceTest', 'AppAcceptanceSandboxStartupMigrationContractTest']) assert(evidence.some(file => file.endsWith(`.${name}.xml`)), `Missing fresh suite ${name}`);
for (const file of [proof.lifecycle, proof.http, proof.restart]) assert(fs.statSync(file).mtimeMs >= start, `Stale evidence ${file}`);
const lifecycle = read(proof.lifecycle), http = read(proof.http), restart = read(proof.restart);
const concurrencyFile = readPromotionConcurrency(path.dirname(proof.lifecycle), proof.suffix, start);
evidence.push(concurrencyFile);
assert.equal(lifecycle.completed, true); assert.equal(http.completed, true); assert.equal(restart.completed, true);
assert.equal(restart.compiledFiles.count, restart.compiledFiles.targetCount, 'Runtime must contain every compiled target file');
assert.equal(restart.compiledFiles.manifestSha256, restart.compiledFiles.targetManifestSha256, 'Runtime and target artifacts must match in both directions');
assert.equal(http.run, restart.run);
assert(Date.parse(http.startedAt) >= start, 'HTTP transactions reused an earlier run');
for (const scenario of ['first-purchase-two-stale-snapshots', 'activity-last-capacity-race', 'multi-sku-quantities-and-lines', 'real-direct-inviter-two-beneficiaries']) assert(lifecycle.evidence.some(item => item.scenario === scenario && Object.keys(item.facts).length), `Missing concurrency/lifecycle observations ${scenario}`);
for (const asset of ['DEVICE', 'USDT', 'NEX']) {
  assert(http.steps.some(step => step.scenario === 'REAL_CANONICAL_BUY_REWARD_REFUND' && step.asset === asset && step.passed), `Missing real HTTP lifecycle ${asset}`);
  assert(restart.checks.some(step => step.asset === asset && step.passed && step.rewardState === 'REVERSED' && step.recoveryOutstanding === '0.000000'), `Missing post-restart readback ${asset}`);
}
assert.equal(before, sourceDigest(), 'Source changed during runtime verification');
assert.deepEqual(harnessBefore, harnessHashes(), 'Acceptance harness changed during runtime verification');
const steps = [
  { id: 'backend-persistence', evidence: [proof.http, proof.restart, ...evidence] },
  { id: 'backend-concurrency', evidence: [proof.lifecycle, ...evidence] },
  { id: 'backend-operations', evidence: [proof.http, proof.restart, ...evidence] },
].map(step => ({ ...step, status: 'pass', innerSkipped: 0 }));
fs.writeFileSync(reportFile, JSON.stringify({ taskId: env.WORKFLOW_TASK_ID, stepId: env.WORKFLOW_STEP_ID, checkId: env.WORKFLOW_CHECK_ID, runId: env.WORKFLOW_RUN_ID, repo: env.WORKFLOW_REPO, snapshotHash: env.WORKFLOW_SNAPSHOT_HASH, startedAt, at: new Date().toISOString(), verdict: 'pass', mode: 'full', treeMoved: false, capability: 'runtime', harnessFiles: harnessBefore, suites, totalTests, steps }, null, 2));
console.log(`Fresh ${suites} suites / ${totalTests} tests, three HTTP asset lifecycles and restart reads passed: ${reportFile}`);
