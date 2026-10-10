import assert from 'node:assert/strict';
import { readFileSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const openapiFile = path.join(repo, 'docs/specs/growth-promotions/openapi.json');
const fixtureFile = path.join(repo, 'src/test/resources/growth-promotions/contracts.json');
const migrationFile = path.join(repo, 'scripts/migrations/20261007_growth_promotions.sql');
const document = JSON.parse(readFileSync(openapiFile, 'utf8'));
const runtimeSchemas = JSON.parse(readFileSync(path.join(repo, 'src/main/resources/promotion/contract-schemas.json'), 'utf8'));
const assertRuntimeSchemas = resource => assert.deepEqual(resource.components?.schemas, document.components.schemas,
  'Java validator resource must preserve components.schemas and exactly match the current OpenAPI');
assertRuntimeSchemas(runtimeSchemas);
assert.throws(() => assertRuntimeSchemas(document.components.schemas), undefined, 'Gate failed to reject a missing Java schema wrapper');
const fixtures = JSON.parse(readFileSync(fixtureFile, 'utf8'));
const migration = readFileSync(migrationFile, 'utf8');
const receiptMigration = readFileSync(path.join(repo, 'scripts/migrations/20261007_growth_promotions_order_receipt.sql'), 'utf8');
const listMigration = readFileSync(path.join(repo, 'scripts/migrations/20261007_growth_promotions_list_snapshot.sql'), 'utf8');
const quotaRestoreMigration = readFileSync(path.join(repo, 'scripts/migrations/20261007_growth_promotions_quota_restore.sql'), 'utf8');
const walletBillMigration = readFileSync(path.join(repo, 'scripts/migrations/20261007_e4_wallet_bill_prerequisite.sql'), 'utf8');
const clone = value => JSON.parse(JSON.stringify(value));
const templates = ['SKU_GIFT', 'FIRST_PURCHASE', 'DIRECT_REFERRAL', 'MULTI_PRODUCT', 'REPURCHASE'];
const rewardStates = ['PENDING', 'READY', 'PROCESSING', 'ISSUED', 'RETRYABLE_FAILED',
  'OUTCOME_UNKNOWN', 'CANCELLED', 'REVERSAL_PENDING', 'REVERSED', 'MANUAL_REVIEW'];
const requiredOperations = [
  'listPublicPromotions', 'getPublicPromotion', 'quote', 'createOrder', 'createBundle', 'payOrder', 'cancelOrder',
  'listOwnRewards', 'getOwnReward', 'getOwnReferralProgress', 'getOwnCommand', 'getAdminCommand',
  'getPromotionCatalog', 'listPolicies', 'getPolicy', 'createPolicy', 'createPolicyVersion',
  'approvePolicy', 'revokePolicy', 'listDeviceRightsProfiles', 'getDeviceRightsProfile',
  'listPromotions', 'createPromotion', 'getPromotion', 'saveDraft', 'listVersions', 'getVersion',
  'createDraftVersion', 'copyPromotion', 'simulate', 'audiencePreview', 'submitPromotion', 'withdrawPromotion',
  'approvePromotion', 'rejectPromotion', 'publishPromotion', 'pausePromotion', 'resumePromotion',
  'endPromotion', 'archivePromotion', 'listRewards', 'getReward', 'retryReward', 'reconcileReward',
  'cancelReward', 'reverseReward', 'resolveReward', 'getMetrics', 'createExport', 'getExport',
  'downloadExport', 'refundOrder', 'refundOrderPatch',
];
const money = (value, aggregate = false) => {
  assert.equal(typeof value, 'string', 'Money must be a decimal string');
  assert.match(value, aggregate ? /^(0|[1-9][0-9]*)(\.[0-9]{1,6})?$/ : /^(0|[1-9][0-9]{0,11})(\.[0-9]{1,6})?$/);
  const [whole, fraction = ''] = value.split('.');
  return BigInt(whole) * 1000000n + BigInt(fraction.padEnd(6, '0'));
};

function resolveRef(ref, doc, fixture, currentFile = openapiFile) {
  assert.equal(typeof ref, 'string');
  const [filePart, pointer = ''] = ref.split('#');
  const file = filePart ? path.resolve(path.dirname(currentFile), filePart) : currentFile;
  const relative = path.relative(repo, realpathSync(file));
  assert(!relative.startsWith('..') && !path.isAbsolute(relative), 'Ref escapes repository');
  const root = file === openapiFile ? doc : file === fixtureFile ? fixture
    : JSON.parse(readFileSync(file, 'utf8'));
  assert(pointer === '' || pointer.startsWith('/'), 'Only JSON Pointer refs are supported');
  let value = root;
  for (const part of pointer.split('/').slice(1)) {
    const key = part.replace(/~1/g, '/').replace(/~0/g, '~');
    assert(value && Object.hasOwn(value, key), `Unresolved reference ${ref}`);
    value = value[key];
  }
  return { value, file };
}

function validate(value, schema, doc, fixture, label = '$', currentFile = openapiFile) {
  assert(schema && typeof schema === 'object', `${label}: missing schema`);
  if (schema.$ref) {
    const target = resolveRef(schema.$ref, doc, fixture, currentFile);
    validate(value, target.value, doc, fixture, label, target.file);
    return;
  }
  const matches = candidate => {
    try { validate(value, candidate, doc, fixture, label, currentFile); return true; }
    catch { return false; }
  };
  if (schema.oneOf) assert.equal(schema.oneOf.filter(matches).length, 1, `${label}: oneOf`);
  if (schema.anyOf) assert(schema.anyOf.some(matches), `${label}: anyOf`);
  if (schema.allOf) schema.allOf.forEach(candidate => validate(value, candidate, doc, fixture, label, currentFile));
  if (schema.if) {
    const branch = matches(schema.if) ? schema.then : schema.else;
    if (branch) validate(value, branch, doc, fixture, label, currentFile);
  }
  if (schema.not) assert(!matches(schema.not), `${label}: forbidden shape`);
  if (Object.hasOwn(schema, 'const')) assert.deepEqual(value, schema.const, `${label}: const`);
  if (schema.enum) assert(schema.enum.some(v => JSON.stringify(v) === JSON.stringify(value)), `${label}: enum`);
  const isType = type => type === 'null' ? value === null
    : type === 'array' ? Array.isArray(value)
      : type === 'integer' ? Number.isSafeInteger(value)
        : type === 'number' ? typeof value === 'number' && Number.isFinite(value)
          : type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value)
            : typeof value === type;
  if (schema.type) assert((Array.isArray(schema.type) ? schema.type : [schema.type]).some(isType), `${label}: type`);
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    if (schema.minProperties !== undefined) assert(Object.keys(value).length >= schema.minProperties, `${label}: minProperties`);
    for (const name of schema.required || []) assert(Object.hasOwn(value, name), `${label}.${name}: required`);
    if (schema.additionalProperties === false) {
      for (const name of Object.keys(value)) assert(Object.hasOwn(schema.properties || {}, name), `${label}.${name}: unknown field`);
    }
    for (const [name, child] of Object.entries(schema.properties || {})) {
      if (Object.hasOwn(value, name)) validate(value[name], child, doc, fixture, `${label}.${name}`, currentFile);
    }
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined) assert(value.length >= schema.minItems, `${label}: minItems`);
    if (schema.maxItems !== undefined) assert(value.length <= schema.maxItems, `${label}: maxItems`);
    if (schema.uniqueItems) assert.equal(new Set(value.map(v => JSON.stringify(v))).size, value.length, `${label}: duplicate`);
    if (schema.items) value.forEach((v, i) => validate(v, schema.items, doc, fixture, `${label}[${i}]`, currentFile));
  }
  if (typeof value === 'string') {
    if (schema.minLength !== undefined) assert([...value].length >= schema.minLength, `${label}: minLength`);
    if (schema.maxLength !== undefined) assert([...value].length <= schema.maxLength, `${label}: maxLength`);
    if (schema.pattern) assert(new RegExp(schema.pattern).test(value), `${label}: pattern`);
    if (schema.format === 'date-time') {
      assert(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value)), `${label}: UTC date-time`);
    }
    if (schema.format === 'uri') assert(new URL(value).protocol, `${label}: uri`);
  }
  if (typeof value === 'number') {
    if (schema.minimum !== undefined) assert(value >= schema.minimum, `${label}: minimum`);
    if (schema.maximum !== undefined) assert(value <= schema.maximum, `${label}: maximum`);
  }
}
function walk(value, visit) {
  if (!value || typeof value !== 'object') return;
  visit(value);
  Object.values(value).forEach(child => walk(child, visit));
}
function dereference(value, doc, fixture) {
  return value.$ref ? resolveRef(value.$ref, doc, fixture).value : value;
}
function quoteSemantics(quote) {
  assert.equal(quote.itemCount, quote.items.length, 'itemCount must count rows');
  assert.equal(quote.quantity, quote.items.reduce((sum, i) => sum + i.quantity, 0), 'quantity must count units');
  assert(quote.quantity <= 100, 'Existing order capacity');
  assert(quote.items.length <= 8, 'Existing bundle SKU capacity');
  assert.equal(new Set(quote.items.map(i => i.productNo)).size, quote.items.length, 'Duplicate SKU lines');
  let subtotal = 0n, discount = 0n, payable = 0n;
  for (const line of quote.items) {
    assert.equal(money(line.unitPriceUsdt) * BigInt(line.quantity), money(line.subtotalUsdt));
    assert.equal(money(line.subtotalUsdt) - money(line.discountUsdt), money(line.payableUsdt));
    subtotal += money(line.subtotalUsdt); discount += money(line.discountUsdt); payable += money(line.payableUsdt);
  }
  assert.equal(subtotal, money(quote.subtotalUsdt));
  assert.equal(discount, money(quote.discountUsdt));
  assert.equal(payable, money(quote.amountUsdt));
  assert.equal(quote.reserved, false, 'Quote cannot reserve budget');
}
function draftSemantics(draft) {
  assert(Date.parse(draft.endsAt) > Date.parse(draft.startsAt));
  assert.equal(new Set(draft.rules.map(r => r.ruleId)).size, draft.rules.length, 'Stable rule IDs unique');
  const rewardIds = [];
  for (const rule of draft.rules) {
    assert.equal(rule.buyerReward.beneficiaryRole, 'BUYER');
    rewardIds.push(rule.buyerReward.rewardRuleId);
    if (rule.inviterReward) {
      assert.equal(draft.template, 'DIRECT_REFERRAL');
      assert.equal(rule.inviterReward.beneficiaryRole, 'DIRECT_INVITER');
      rewardIds.push(rule.inviterReward.rewardRuleId);
    }
  }
  assert.equal(new Set(rewardIds).size, rewardIds.length, 'Reward rule IDs unique');
  if (['FIRST_PURCHASE', 'DIRECT_REFERRAL'].includes(draft.template)) {
    assert.equal(draft.buyerAudience.purchaseHistory, 'NEVER_PAID');
    assert(draft.policies.firstPurchase);
  }
  if (draft.template === 'DIRECT_REFERRAL') assert(draft.inviterAudience);
  if (draft.template === 'REPURCHASE') assert.equal(draft.buyerAudience.purchaseHistory, 'HAS_VALID_PURCHASE');
}
function uniqueColumns(sql, name) {
  const match = sql.match(new RegExp('UNIQUE KEY\\s+' + name + '\\s*\\(([^)]+)\\)', 'i'));
  assert(match, `SQL missing unique key ${name}`);
  return match[1].split(',').map(value => value.trim().toLowerCase());
}
const requiredForeignKeys = [
  [
    "fk_promotion_version_root",
    "nx_promotion_version",
    "activity_id",
    "nx_promotion",
    "activity_id"
  ],
  [
    "fk_promotion_rule_root",
    "nx_promotion_rule_identity",
    "activity_id",
    "nx_promotion",
    "activity_id"
  ],
  [
    "fk_promotion_reward_rule",
    "nx_promotion_reward_identity",
    "activity_id,rule_id",
    "nx_promotion_rule_identity",
    "activity_id,rule_id"
  ],
  [
    "fk_promotion_budget_root",
    "nx_promotion_budget",
    "activity_id",
    "nx_promotion",
    "activity_id"
  ],
  [
    "fk_promotion_usage_root",
    "nx_promotion_usage",
    "activity_id",
    "nx_promotion",
    "activity_id"
  ],
  [
    "fk_promotion_reservation_version",
    "nx_promotion_reservation",
    "activity_id,version",
    "nx_promotion_version",
    "activity_id,version"
  ],
  [
    "fk_promotion_reward_reservation",
    "nx_promotion_reward",
    "reservation_id",
    "nx_promotion_reservation",
    "reservation_id"
  ],
  [
    "fk_promotion_device_reward",
    "nx_promotion_device_receipt",
    "obligation_id",
    "nx_promotion_reward",
    "obligation_id"
  ],
  [
    "fk_promotion_device_actual",
    "nx_promotion_device_receipt",
    "device_id",
    "nx_user_device",
    "id"
  ],
  [
    "fk_promotion_device_profile",
    "nx_promotion_device_receipt",
    "profile_id,profile_version",
    "nx_promotion_policy",
    "policy_id,version"
  ],
  [
    "fk_promotion_reversal_reward",
    "nx_promotion_reversal",
    "obligation_id",
    "nx_promotion_reward",
    "obligation_id"
  ],
  [
    "fk_promotion_attempt_reward",
    "nx_promotion_reward_attempt",
    "obligation_id",
    "nx_promotion_reward",
    "obligation_id"
  ],
  [
    "fk_promotion_export_snapshot",
    "nx_promotion_export_job",
    "snapshot_id",
    "nx_promotion_report_snapshot",
    "snapshot_id"
  ]
];
const requiredSqlChecks = {
  "chk_promotion_category": [
    "nx_promotion",
    "category IN ('PROMOTION','REFERRAL')"
  ],
  "chk_promotion_template": [
    "nx_promotion",
    "template IN ('SKU_GIFT','FIRST_PURCHASE','DIRECT_REFERRAL','MULTI_PRODUCT','REPURCHASE')"
  ],
  "chk_promotion_state": [
    "nx_promotion",
    "status IN ('DRAFT','SCHEDULED','ACTIVE','PAUSED','ENDED','ARCHIVED')"
  ],
  "chk_promotion_revision": [
    "nx_promotion",
    "revision > 0"
  ],
  "chk_promotion_activity_count": [
    "nx_promotion",
    "reserved_orders >= 0 AND used_orders >= 0"
  ],
  "chk_promotion_policy_kind": [
    "nx_promotion_policy",
    "kind IN ('FIRST_PURCHASE','DEVICE_AUDIENCE','DEVICE_RIGHTS','ASSET','SETTLEMENT','STACKING','REFUND','QUOTE','AUTHORIZATION')"
  ],
  "chk_promotion_policy_status": [
    "nx_promotion_policy",
    "status IN ('DRAFT','APPROVED','REVOKED')"
  ],
  "chk_promotion_policy_approval": [
    "nx_promotion_policy",
    "status <> 'APPROVED' OR (approval_ref IS NOT NULL AND approved_by IS NOT NULL AND approved_at IS NOT NULL)"
  ],
  "chk_promotion_policy_version": [
    "nx_promotion_policy",
    "version > 0 AND revision > 0"
  ],
  "chk_promotion_version_state": [
    "nx_promotion_version",
    "status IN ('DRAFT','PENDING_APPROVAL','APPROVED','PUBLISHED')"
  ],
  "chk_promotion_version_time": [
    "nx_promotion_version",
    "(starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at) AND (status='DRAFT' OR (starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at > starts_at))"
  ],
  "chk_promotion_version_revision": [
    "nx_promotion_version",
    "version > 0 AND revision > 0"
  ],
  "chk_promotion_version_approval": [
    "nx_promotion_version",
    "status NOT IN ('APPROVED','PUBLISHED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND approval_ref IS NOT NULL)"
  ],
  "chk_promotion_version_publish": [
    "nx_promotion_version",
    "status <> 'PUBLISHED' OR (published_by IS NOT NULL AND published_at IS NOT NULL AND resolved_policies_json IS NOT NULL)"
  ],
  "chk_promotion_reward_role": [
    "nx_promotion_reward_identity",
    "beneficiary_role IN ('BUYER','DIRECT_INVITER')"
  ],
  "chk_promotion_budget_kind": [
    "nx_promotion_budget",
    "(asset IN ('USDT','NEX') AND product_no='') OR (asset='DEVICE' AND product_no<>'')"
  ],
  "chk_promotion_budget_nonnegative": [
    "nx_promotion_budget",
    "total >= 0 AND reserved >= 0 AND committed >= 0 AND issued >= 0 AND reversed >= 0 AND unrecoverable >= 0"
  ],
  "chk_promotion_budget_available": [
    "nx_promotion_budget",
    "total - reserved - committed - issued + reversed >= 0"
  ],
  "chk_promotion_budget_loss": [
    "nx_promotion_budget",
    "reversed <= issued AND unrecoverable <= issued - reversed"
  ],
  "chk_promotion_budget_devices": [
    "nx_promotion_budget",
    "asset <> 'DEVICE' OR (total=FLOOR(total) AND reserved=FLOOR(reserved) AND committed=FLOOR(committed) AND issued=FLOOR(issued) AND reversed=FLOOR(reversed) AND unrecoverable=FLOOR(unrecoverable))"
  ],
  "chk_promotion_usage_counts": [
    "nx_promotion_usage",
    "reserved_orders>=0 AND used_orders>=0 AND reserved_groups>=0 AND used_groups>=0"
  ],
  "chk_promotion_usage_role": [
    "nx_promotion_usage",
    "beneficiary_role IN ('BUYER','DIRECT_INVITER')"
  ],
  "chk_promotion_reservation_state": [
    "nx_promotion_reservation",
    "status IN ('RESERVED','COMMITTED','RELEASED')"
  ],
  "chk_promotion_reservation_amount": [
    "nx_promotion_reservation",
    "amount > 0 AND unit_seq >= 0"
  ],
  "chk_promotion_reservation_asset": [
    "nx_promotion_reservation",
    "(asset IN ('USDT','NEX') AND product_no='') OR (asset='DEVICE' AND product_no<>'' AND amount=FLOOR(amount))"
  ],
  "chk_promotion_reward_state": [
    "nx_promotion_reward",
    "status IN ('PENDING','READY','PROCESSING','ISSUED','RETRYABLE_FAILED','OUTCOME_UNKNOWN','CANCELLED','REVERSAL_PENDING','REVERSED','MANUAL_REVIEW')"
  ],
  "chk_promotion_reward_sequence": [
    "nx_promotion_reward",
    "unit_seq >= 0 AND revision > 0"
  ],
  "chk_promotion_device_sequence": [
    "nx_promotion_device_receipt",
    "device_unit_seq >= 0 AND profile_version > 0"
  ],
  "chk_promotion_hold_state": [
    "nx_promotion_refund_hold",
    "status IN ('HELD','RELEASED','EXECUTED','OUTCOME_UNKNOWN')"
  ],
  "chk_promotion_hold_source": [
    "nx_promotion_refund_hold",
    "source_type IN ('A2_OPERATION','E4_REFUND')"
  ],
  "chk_promotion_hold_executed": [
    "nx_promotion_refund_hold",
    "status <> 'EXECUTED' OR (refund_no IS NOT NULL AND refund_ledger_biz_no IS NOT NULL)"
  ],
  "chk_promotion_reversal_state": [
    "nx_promotion_reversal",
    "status IN ('REVERSAL_PENDING','REVERSED','MANUAL_REVIEW')"
  ],
  "chk_promotion_reversal_asset": [
    "nx_promotion_reversal",
    "asset IN ('DEVICE','USDT','NEX')"
  ],
  "chk_promotion_reversal_devices": [
    "nx_promotion_reversal",
    "asset <> 'DEVICE' OR (amount=FLOOR(amount) AND recovered=FLOOR(recovered) AND outstanding=FLOOR(outstanding) AND reusable=FLOOR(reusable))"
  ],
  "chk_promotion_reversal_basis": [
    "nx_promotion_reversal",
    "(basis_type='WHOLE_ORDER_REFUND' AND refund_no IS NOT NULL AND basis_ref=refund_no AND approval_operation_id IS NULL) OR (basis_type='APPROVED_CORRECTION' AND refund_no IS NULL AND approval_operation_id IS NOT NULL AND basis_ref=approval_operation_id)"
  ],
  "chk_promotion_reversal_amount": [
    "nx_promotion_reversal",
    "amount > 0 AND recovered >= 0 AND outstanding >= 0 AND recovered + outstanding = amount AND reusable >= 0 AND reusable <= recovered"
  ],
  "chk_promotion_export_state": [
    "nx_promotion_export_job",
    "status IN ('PROCESSING','READY','FAILED','EXPIRED')"
  ],
  "chk_promotion_export_ready": [
    "nx_promotion_export_job",
    "status <> 'READY' OR (storage_ref IS NOT NULL AND row_count IS NOT NULL AND content_hash IS NOT NULL AND expires_at IS NOT NULL)"
  ]
};
const normalizeSql = value => value.replace(/\s+/g, '').toLowerCase();
// Ignore documentary SQL without accidentally stripping comment-like characters in literals.
function executableSql(sql) {
  let result = '', quote = null;
  for (let i = 0; i < sql.length;) {
    const c = sql[i], next = sql[i + 1];
    if (quote) {
      result += c; i++;
      if (c === '\\' && i < sql.length) result += sql[i++];
      else if (c === quote) {
        if (sql[i] === quote) result += sql[i++];
        else quote = null;
      }
    } else if (['\'', '"', '`'].includes(c)) { quote = c; result += c; i++; }
    else if (c === '#' || (c === '-' && next === '-' && /\s/.test(sql[i + 2] ?? ' '))) {
      while (i < sql.length && sql[i] !== '\n') i++;
      result += '\n';
    } else if (c === '/' && next === '*') {
      assert.notEqual(sql[i + 2], '!', 'Executable version comments are not allowed in this migration');
      const end = sql.indexOf('*/', i + 2); assert(end >= 0, 'Unclosed SQL comment');
      result += ' '; i = end + 2;
    } else { result += c; i++; }
  }
  assert.equal(quote, null, 'Unclosed SQL literal');
  return result;
}
function foreignKeyInventory(sql) {
  const keys = new Map();
  for (const [, table, body] of sql.matchAll(/CREATE TABLE IF NOT EXISTS (\w+)\s*\(([\s\S]*?)\)\s*ENGINE=/g)) {
    for (const [, name, columns, parent, parentColumns, actions] of body.matchAll(
      /CONSTRAINT (fk_\w+) FOREIGN KEY\s*\(([^)]+)\) REFERENCES (\w+)\s*\(([^)]+)\)([^\n]*)/g)) {
      assert(!keys.has(name), 'Duplicate FK');
      assert(!/CASCADE|SET\s+NULL/i.test(actions), 'History must use restrictive FK actions');
      keys.set(name, [table, normalizeSql(columns), parent, normalizeSql(parentColumns)]);
    }
  }
  return keys;
}

function sqlIntegrity(sql) {
  sql = executableSql(sql);
  const foreignKeys = foreignKeyInventory(sql), checks = new Map();
  for (const [, table, body] of sql.matchAll(/CREATE TABLE IF NOT EXISTS (\w+)\s*\(([\s\S]*?)\)\s*ENGINE=/g)) {
    for (const match of body.matchAll(/CONSTRAINT (chk_\w+) CHECK\s*\(/g)) {
      const start = match.index + match[0].length;
      let depth = 1, end = start;
      for (; end < body.length && depth; end++) {
        if (body[end] === '(') depth++;
        if (body[end] === ')') depth--;
      }
      assert.equal(depth, 0, 'Unbalanced SQL CHECK');
      assert(!/^\s*NOT\s+ENFORCED\b/i.test(body.slice(end)), 'CHECK must be enforced: ' + match[1]);
      assert(!checks.has(match[1]), 'Duplicate CHECK');
      checks.set(match[1], [table, normalizeSql(body.slice(start, end - 1))]);
    }
  }
  assert.equal(foreignKeys.size, requiredForeignKeys.length, 'Required FK inventory');
  for (const [name, ...expected] of requiredForeignKeys) assert.deepEqual(foreignKeys.get(name), expected, 'FK relationship ' + name);
  assert.equal(checks.size, Object.keys(requiredSqlChecks).length, 'Required CHECK inventory');
  for (const [name, [table, expression]] of Object.entries(requiredSqlChecks)) {
    assert.deepEqual(checks.get(name), [table, normalizeSql(expression)], 'CHECK relationship ' + name);
  }
  assert(/starts_at DATETIME\(6\) NULL/.test(sql) && /ends_at DATETIME\(6\) NULL/.test(sql), 'Unconfigured draft times must persist without dummy dates');
  assert(sql.includes('KEY idx_promotion_device_profile (profile_id, profile_version)'), 'Profile FK needs explicit composite index');
}

function validatePublication(draft, doc, fixture) {
  validate(draft, doc.components.schemas.PromotionContract, doc, fixture, 'storedPublication');
  draftSemantics(draft);
}
function metricSemantics(metrics) {
  assert(Array.isArray(metrics.salesBySku), 'Per-SKU sales must come from the frozen report');
  for (const field of ['grossPaidUsdt','refundUsdt','netReceivedUsdt']) assert.equal(metrics.salesBySku.reduce((sum, row) => sum + money(row[field], true), 0n), money(metrics[field], true), 'Per-SKU sum differs from order total: ' + field);
  for (const row of metrics.salesBySku) assert.equal(money(row.netReceivedUsdt, true), money(row.grossPaidUsdt, true) - money(row.refundUsdt, true));
  for (const name of ['effectiveFirstPurchasePeople', 'directInvitedQualifiedPurchasers', 'rewardFailureMetrics', 'recoveryDuration']) {
    const metric = metrics[name];
    assert(metric, 'Required metric dimension ' + name);
    if (metric.completeness === 'UNAVAILABLE') assert.equal(metric.value, null, 'Unavailable metrics must not fabricate zero');
    if (name === 'rewardFailureMetrics' && metric.completeness === 'UNAVAILABLE') assert.equal(metric.breakdown, null);
    if (name === 'recoveryDuration' && metric.sampleCount === 0) assert.equal(metric.value, null, 'No resolved samples has no mean duration');
  }
}

function check(doc, fixture, sql) {
  sql = executableSql(sql);
  sqlIntegrity(sql);
  assert.equal(doc.openapi, '3.1.0');
  assert.equal(fixture.fixtureOnly, true);
  assert.equal(fixture.productionApproval, false);
  assert.equal(doc['x-contract'].fixturePolicyBypass, false);
  walk(doc, node => { if (node.$ref) resolveRef(node.$ref, doc, fixture); });
  assert.deepEqual(doc.components.schemas.Template.enum, templates);
  assert.deepEqual(doc.components.schemas.RewardState.enum, rewardStates);
  assert.equal(doc.components.schemas.Amount.type, 'string');
  assert.equal(doc.components.schemas.PositiveAmount.type, 'string');
  for (const [schema, property] of [['QuoteInput','items'],['Quote','items'],['BundleOrderInput','productNos'],['BundleOrderInput','items'],['SimulateInput','items']])
    assert.equal(doc.components.schemas[schema].properties[property].maxItems, 8, 'Existing eight-SKU boundary');
  assert.equal(doc.components.schemas.Quantity.maximum, 100, 'Existing unit boundary');
  assert.deepEqual(doc['x-contract'].refundLifecycleBoundary, {mode:'SAME_PROCESS_TRANSACTION',entrypoints:['holdA2Refund','clearA2Refund','confirmE4Refund'],sourceFacts:['nx_audit_operation_ticket','nx_order','nx_wallet_ledger'],ordinaryAppRefundEntry:false});
  assert(!Object.keys(doc.paths).some(route => route.startsWith('/internal/growth/promotions/refunds/')), 'No undeployed refund HTTP surface');
  assert.deepEqual(doc.components.schemas.PromotionContract.properties.shortagePolicy.enum, ['RULE_STOP', 'ACTIVITY_PAUSE']);
  assert.deepEqual(doc.components.schemas.Draft.required, ['category', 'template']);
  assert.deepEqual(doc.components.schemas.DraftPatch.required, []);
  assert(doc.components.schemas.PromotionContract.required.includes('name'), 'Internal name required for progression');
  assert(doc.components.schemas.DraftPatch.properties.name, 'Internal name editable as a distinct field');
  assert.equal(doc.paths['/api/admin/growth/promotions/{activityId}/withdraw'].post['x-required-permission'],'growth_promotion_submit');
  assert.equal(doc.paths['/api/admin/growth/promotions/{activityId}/withdraw'].post['x-request-schema'],'PublishAction');
  for(const field of ['query','name','category','template','from','to','timezone','querySnapshot','sort']) assert(doc.paths['/api/admin/growth/promotions'].get.parameters.some(p=>p.name===field),'List filter missing '+field);
  for(const field of ['total','query','querySnapshot','asOf','expiresAt','summary']) assert(doc.components.schemas.PromotionPage.required.includes(field),'List snapshot missing '+field);
  for(const field of ['reserved','issued','unresolved','unpaidOrders','pendingRewards','failedRewards','unknownRewards','reversingRewards','manualReviewRewards','reservedOrders','budgets']) assert(doc.components.schemas.PromotionImpact.required.includes(field),'Impact missing '+field);
  assert.deepEqual(doc['x-contract'].withdrawal,{operation:'withdrawPromotion',permission:'growth_promotion_submit',from:['PENDING_APPROVAL','APPROVED'],to:'DRAFT',activeVersionUnchanged:true});
  assert.equal(doc.paths['/api/admin/growth/promotions/{activityId}/simulate'].post['x-response-schema'],'AdminSimulation');
  assert.deepEqual(doc['x-contract'].adminDiagnostics,{audienceRequiresCompleteContract:false,unknownIsRejected:false,simulationSchema:'AdminSimulation',publicQuoteDiagnostics:false,readOnly:true});
  for(const field of ['ruleResults','rewardUnits','resources','quotaImpact','publicPreview','blockers','configHash']) assert(doc.components.schemas.AdminSimulation.required.includes(field),'Missing admin diagnostic '+field);
  assert.equal(doc.components.schemas.AdminSimulation.properties.resources.minItems,1,'Even unknown simulation retains resource evidence');
  for(const field of ['matched','rejected','unknown','total','conditionSummary','source','reasonCounts','samples']) assert(doc.components.schemas.AudiencePreview.required.includes(field),'Missing audience evidence '+field);
  for(const field of ['beneficiaryId','type']) assert(doc.paths['/api/admin/growth/promotion-rewards'].get.parameters.some(p=>p.name===field),'Missing reward filter '+field);
  for(const field of ['total','query','asOf']) assert(doc.components.schemas.RewardAdminPage.required.includes(field),'Missing reward query evidence '+field);
  for(const field of ['orders','refundedOrders','orderWindowBasis','paidWindowBasis','conversionRate','acquisitionCostUsdt','roi']) assert(doc.components.schemas.Metrics.required.includes(field),'Missing report field '+field);
  assert.equal(doc.components.schemas.PromotionListSummary.properties.budgets.items.$ref,'#/components/schemas/AggregateBudget');
  assert.equal(doc.components.schemas.Metrics.properties.grossPaidUsdt.$ref,'#/components/schemas/AggregateAmount');
  assert.throws(()=>validate('1200000000000.000000',doc.components.schemas.Amount,doc,fixture),'Single entry must keep its 18,6 limit');
  validate('1200000000000.000000',doc.components.schemas.AggregateAmount,doc,fixture);
  for (const time of ['2026-10-10T04:37:57Z', '2026-10-10T04:37:57.889Z', '2026-10-10T04:37:57.889337Z',
    '2026-10-10T04:37:57.889337200Z', '2026-10-10T06:37:57.889337200Z']) {
    validate(time, doc.components.schemas.Time, doc, fixture, 'Time precision ' + time);
  }
  for (const time of ['2026-10-10T04:37:57.8893372000Z', '2026-10-10T04:37:57.889337200+00:00', 'invalid-timeZ', '2026-13-10T04:37:57Z']) {
    assert.throws(() => validate(time, doc.components.schemas.Time, doc, fixture), undefined, 'Invalid UTC Time accepted: ' + time);
  }
  assert.deepEqual(doc.components.schemas.MetricRewardGroupBy.enum,['SKU','TEMPLATE','BENEFICIARY']);
  assert(doc.components.schemas.Metrics.required.includes('breakdown'),'Report requires server-side reward groups');
  assert(doc.components.schemas.Metrics.required.includes('salesBySku'),'Report requires exact per-SKU sales');
  assert.equal(doc.components.schemas.Metrics.properties.breakdown.$ref,'#/components/schemas/MetricRewardBreakdown');
  for(const field of ['groupBy','rows','total','nextCursor','hasMore','rewardWindowBasis']) assert(doc.components.schemas.MetricRewardBreakdown.required.includes(field),'Missing grouped page field '+field);
  for(const field of ['groupKey','purchaseProductNo','template','beneficiaryId','beneficiaryRole','asset','giftProductNo','obligations','orders','beneficiaries','amount','issued','pending','reversed','unrecoverable','cancelled']) assert(doc.components.schemas.MetricRewardGroupRow.required.includes(field),'Missing reward grouping evidence '+field);
  for(const field of ['amount','issued','pending','reversed','unrecoverable','cancelled']) assert.equal(doc.components.schemas.MetricRewardGroupRow.properties[field].$ref,'#/components/schemas/AggregateAmount');
  for(const field of ['groupBy','querySnapshot','cursor','limit']) assert(doc.paths['/api/admin/growth/promotions/{activityId}/metrics'].get.parameters.some(p=>p.name===field),'Missing grouped report parameter '+field);
  assert.deepEqual(doc['x-contract'].metricsBreakdown,{groupBy:['SKU','TEMPLATE','BENEFICIARY'],snapshot:'nx_promotion_report_snapshot',sameActor:true,strictBaseQuery:true,immutablePages:true,splitAssets:true,exportIncludesAllGroups:true});
  assert.equal(doc.components.schemas.DraftWrite.properties.draft.$ref, '#/components/schemas/DraftPatch');
  assert.deepEqual(doc.components.schemas.PublicPromotion.properties.placement.enum, ['home.purchase-promotion', null]);
  assert.equal(doc.paths['/api/promotions'].get['x-placement-filter'], 'EXPLICIT_SELECTED_ACTIVE_PUBLISHED');
  assert(doc.paths['/api/promotions'].get.parameters.some(p => p.name === 'placement' && p.schema.enum?.[0] === 'home.purchase-promotion'));
  const opMap = new Map();
  for (const [route, verbs] of Object.entries(doc.paths)) {
    assert(route.startsWith('/'));
    for (const [method, operation] of Object.entries(verbs)) {
      assert(['get', 'post', 'put', 'patch'].includes(method), 'Unexpected HTTP method');
      assert(!opMap.has(operation.operationId), 'Duplicate operationId');
      opMap.set(operation.operationId, operation);
      if (['submitPromotion', 'approvePromotion', 'publishPromotion'].includes(operation.operationId)) {
        assert.equal(operation['x-stored-contract-schema'], '#/components/schemas/PromotionContract');
        assert.equal(operation['x-required-policy-state'], 'APPROVED');
        assert.equal(operation['x-resolved-policy-schema'], '#/components/schemas/ResolvedApprovedPolicy');
        for (const item of fixture.publicationCases) assert.throws(() => validatePublication(item.storedDraft, doc, fixture), undefined, operation.operationId + ': ' + item.id);
        for (const item of fixture.policyGateCases) assert.throws(() => validate(item.policy, doc.components.schemas.ResolvedApprovedPolicy, doc, fixture), undefined, operation.operationId + ': ' + item.id);
      }
      assert(operation.security?.length, 'Every operation declares security');
      for (const name of [...route.matchAll(/{([^}]+)}/g)].map(m => m[1])) {
        assert(operation.parameters.some(p => p.in === 'path' && p.name === name && p.required), 'Missing path parameter');
      }
      if (operation['x-audience'] === 'ADMIN') {
        assert(operation['x-required-permission'], 'Admin permission required');
        assert(doc['x-contract'].permissionCodes.includes(operation['x-required-permission']), 'Unregistered permission');
        assert.deepEqual(operation.security, [{ AdminBearer: [] }]);
      }
      assert.notEqual(operation['x-audience'], 'INTERNAL', 'Refund integration is same-process only');
      if (method !== 'get' && !['simulate', 'audiencePreview'].includes(operation.operationId)) {
        assert(operation.parameters.some(p => p.$ref === '#/components/parameters/IdempotencyKey'), 'Mutation idempotency required');
      }
      for (const code of ['200', '400', '401', '403', '404', '409', '422', '503']) assert(operation.responses[code], `Missing ${code} response`);
      const item = fixture.cases.filter(c => c.operationId === operation.operationId);
      assert.equal(item.length, 1, 'Every operation has exactly one fixture');
      if (operation.requestBody) {
        const content = dereference(operation.requestBody, doc, fixture).content['application/json'];
        const example = fixture.examples[item[0].requestExample];
        assert(example, 'Request example missing');
        const linkedExample = resolveRef(content.examples.fixture.$ref, doc, fixture).value;
        assert.deepEqual(linkedExample, example, 'OpenAPI request fixture mismatch');
        validate(example.value, content.schema, doc, fixture, operation.operationId + '.request');
      }
      const content = dereference(operation.responses['200'], doc, fixture).content['application/json'];
      const example = fixture.examples[item[0].responseExample];
      assert(example, 'Response example missing');
      assert.deepEqual(resolveRef(content.examples.fixture.$ref, doc, fixture).value, example);
      validate(example.value, content.schema, doc, fixture, operation.operationId + '.response');
    }
  }
  assert.deepEqual([...opMap.keys()].sort(), [...requiredOperations].sort(), 'Required API inventory');
  assert.equal(fixture.cases.length, requiredOperations.length);
  for (const item of [...fixture.templates, ...fixture.rewardStates, ...fixture.legacyRequests, ...fixture.positiveRequests]) {
    validate(item.value, doc.components.schemas[item.schema], doc, fixture, item.schema);
  }
  assert.deepEqual(fixture.templates.map(t => t.value.template), templates);
  assert.deepEqual(fixture.policyKinds.map(p => p.value.content.kind), doc.components.schemas.PolicyContent.oneOf.map(s => s.properties.kind.const), 'Every executable policy kind has a fixture');
  fixture.policyKinds.forEach(p => validate(p.value, doc.components.schemas[p.schema], doc, fixture, 'policy.' + p.value.content.kind));
  assert(doc.components.schemas.Policy.required.includes('resolvedDeviceRights'));
  const deviceRights = doc.components.schemas.ResolvedDeviceRights;
  for (const name of ['activationMode','effectiveOn','durationDays','taskEnabled','taskRule','earningsRule','countsAsDeviceHolding','countsForRank','transferable','exchangeable','revocationMode']) assert(deviceRights.required.includes(name), 'Missing resolved device right ' + name);
  fixture.templates.forEach(t => draftSemantics(t.value));
  fixture.templates.forEach(t => validatePublication(t.value, doc, fixture));
  metricSemantics(fixture.examples.response_Metrics.value.data);
  for (const item of fixture.positiveRequests.filter(p => p.schema === 'Metrics')) metricSemantics(item.value);
  assert.deepEqual(fixture.rewardStates.map(t => t.value.state), rewardStates);
  for (const item of fixture.negativeRequests) {
    assert.throws(() => validate(item.value, doc.components.schemas[item.schema], doc, fixture, item.id), undefined, `Negative fixture accepted: ${item.id}`);
  }
  const quote = fixture.examples.response_Quote.value.data;
  quoteSemantics(quote);
  const promoted = fixture.examples.response_OrderReceipt.value.data;
  for (const property of Object.keys(doc.components.schemas.OrderReceipt.properties)) assert(Object.hasOwn(promoted, property), 'Promoted response missing ' + property);
  assert.equal(promoted.itemCount, 2); assert.equal(promoted.quantity, 3);
  assert.equal(promoted.items.length, promoted.itemCount);
  assert.equal(promoted.items.reduce((sum, item) => sum + item.quantity, 0), promoted.quantity);
  assert.equal(new Set(promoted.items.map(item => item.lineId)).size, promoted.itemCount);
  for (const reward of promoted.rewards) assert(promoted.items.some(item => item.lineId === reward.lineId), 'Reward must join a canonical purchased order item');
  const budget = fixture.schemas.budget;
  assert.equal(money(budget.available), money(budget.total) - money(budget.reserved) - money(budget.committed) - money(budget.issued) + money(budget.reversed));
  assert(money(budget.unrecoverable) <= money(budget.issued) - money(budget.reversed));
  const transitions = doc['x-contract'].transitions;
  for (const [domain, schema] of Object.entries({activity:'ActivityState',version:'VersionState',reward:'RewardState',reservation:'ReservationState',hold:'HoldState'})) {
    const states = doc.components.schemas[schema].enum;
    assert.deepEqual(Object.keys(transitions[domain]).sort(), [...states].sort());
    for (const targets of Object.values(transitions[domain])) for (const target of targets) assert(states.includes(target));
  }
  for (const terminal of ['CANCELLED', 'REVERSED']) assert.deepEqual(transitions.reward[terminal], [], 'Reward terminal cannot revive');
  assert.deepEqual(transitions.hold.EXECUTED, [], 'Executed refund cannot release hold');
  assert(!/\b(?:DROP|TRUNCATE|DELETE|ALTER)\s+(?:TABLE|FROM)\b/i.test(sql), 'Migration must be additive');
  const obligationKey = ['activity_id','order_line_id','beneficiary_id','beneficiary_role','reward_rule_id','unit_seq'];
  assert.deepEqual(uniqueColumns(sql, 'uk_promotion_obligation'), obligationKey, 'No version in reward deduplication');
  assert.deepEqual(uniqueColumns(sql, 'uk_promotion_reservation_unit'), obligationKey);
  assert.deepEqual(uniqueColumns(sql, 'uk_promotion_reversal'), ['obligation_id','refund_no']);
  assert.deepEqual(uniqueColumns(sql, 'uk_promotion_disposition'), ['obligation_id','basis_type','basis_ref']);
  assert(doc.components.schemas.PromotionContract.required.includes('activityLimit'), 'Published activity-wide order capacity is mandatory');
  assert(sql.includes('reserved_orders BIGINT NOT NULL DEFAULT 0') && sql.includes('used_orders BIGINT NOT NULL DEFAULT 0'));
  assert(sql.includes('chk_promotion_activity_count'), 'Activity count persists independently of beneficiary usage');
  for (const shape of doc.components.schemas.PublicRewardSpec.oneOf) {
    assert(!shape.properties.assetPolicy && !shape.properties.deviceRightsProfile, 'Public rules must not leak internal policy references');
  }
  for (const shape of [...doc.components.schemas.PublicRewardSpec.oneOf, ...['ExpectedReward','Reward','RewardAdmin'].map(name=>doc.components.schemas[name])]) {
    assert(shape.required.includes('disclosure') && shape.properties.disclosure.$ref === '#/components/schemas/RewardDisclosure', 'All promised benefits require the frozen public disclosure');
  }
  assert.deepEqual(doc.components.schemas.RewardDisclosure.required, ['title','terms','refundTerms','benefitDescription','deviceRights','deviceName']);
  assert.equal(doc.components.schemas.RewardDisclosure.additionalProperties, false);
  assert(doc.components.schemas.RewardAdmin.required.includes('dispositionOptions'), 'Admin action bases must be selectable from server-resolved facts');
  assert(!doc.components.schemas.Reward.properties.dispositionOptions, 'APP rewards cannot disclose internal approval choices');
  assert(sql.includes('PRIMARY KEY (activity_id, account_id, beneficiary_role, rule_id)'), 'Limits must span versions');
  assert(sql.includes('PRIMARY KEY (activity_id, rule_id)'), 'Stable purchase rule identity');
  assert(sql.includes('UNIQUE KEY uk_promotion_rule_role (activity_id, rule_id, beneficiary_role)'), 'Stable reward identity');
  assert(sql.includes('total - reserved - committed - issued + reversed >= 0'), 'Budget nonnegative SQL gate');
  assert(sql.includes('unrecoverable <= issued - reversed'), 'Loss is subset, not second budget debit');
  assert(sql.includes('recovered + outstanding = amount'), 'Recovery conservation');
  for (const table of ['policy','version','rule_identity','reward_identity','budget','quote','usage','reservation','reward','device_receipt','refund_hold','reversal','reward_attempt','report_snapshot','export_job']) {
    assert(sql.includes('CREATE TABLE IF NOT EXISTS nx_promotion_' + table + ' ('), 'Missing table ' + table);
  }
  for (const state of [...rewardStates,...doc.components.schemas.HoldState.enum]) assert(sql.includes("'" + state + "'"), 'Missing SQL state ' + state);
  for (const permission of doc['x-contract'].permissionCodes.filter(p => p !== 'device_e4_order_refund')) assert(sql.includes("'" + permission + "'"), 'Missing permission seed');
  for(const permission of doc['x-contract'].permissionCodes.filter(p=>p.startsWith('growth_promotion_'))){
    const suffix=permission.slice('growth_promotion_'.length);
    const type=['read','metrics_read','policy_read','reward_read','simulate'].includes(suffix)?'READ':['edit','policy_write','submit'].includes(suffix)?'WRITE':'HIGH';
    assert(executableSql(sql).includes("WHEN '"+permission+"' THEN '"+type+"'"),'Permission classification missing '+permission);
  }
  assert(!/INSERT[\s\S]{0,40}INTO\s+nx_admin_role_/i.test(sql), 'Migration cannot grant roles');
  assert(!/INSERT[\s\S]{0,40}INTO\s+nx_promotion_policy/i.test(sql), 'No approved policy seed');
  return opMap.size;
}

function receiptIntegrity(sql) {
  sql = executableSql(sql);
  assert.match(sql, /CREATE TABLE IF NOT EXISTS nx_promotion_order_receipt\s*\(/);
  assert.match(sql, /order_no VARCHAR\(96\) NOT NULL PRIMARY KEY/);
  for (const column of ['order_id BIGINT NOT NULL','quote_id VARCHAR(64) NOT NULL','buyer_id BIGINT NOT NULL','projection_json JSON NOT NULL','pay_by DATETIME(6) NOT NULL'])
    assert(sql.includes(column), 'Durable order receipt column ' + column);
  for (const relation of [
    'UNIQUE KEY uk_promotion_receipt_order_id (order_id)',
    'UNIQUE KEY uk_promotion_receipt_quote (quote_id)',
  ]) assert(sql.includes(relation), 'Durable receipt relationship ' + relation);
  const keys = foreignKeyInventory(sql);
  assert.equal(keys.size, 2, 'Receipt FK inventory');
  assert.deepEqual(keys.get('fk_promotion_receipt_order'), ['nx_promotion_order_receipt','order_id','nx_order','id']);
  assert.deepEqual(keys.get('fk_promotion_receipt_quote'), ['nx_promotion_order_receipt','quote_id','nx_promotion_quote','quote_id']);
  assert(!/\b(?:DROP|TRUNCATE|DELETE|ALTER|INSERT)\s/i.test(sql.replace(/^--.*$/gm,'')), 'Receipt migration cannot mutate business data');
}
receiptIntegrity(receiptMigration);
function listSnapshotIntegrity(source) {
  const sql = executableSql(source);
  assert.match(sql, /CREATE TABLE IF NOT EXISTS nx_promotion_list_snapshot\s*\(/);
  for (const column of ['snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY', 'actor_id BIGINT NOT NULL',
    'query_json JSON NOT NULL', 'rows_json JSON NOT NULL', 'summary_json JSON NOT NULL',
    'total BIGINT NOT NULL', 'as_of DATETIME(6) NOT NULL', 'expires_at DATETIME(6) NOT NULL',
    'KEY idx_promotion_list_snapshot_owner (actor_id, expires_at)']) assert(sql.includes(column), 'List snapshot column/index ' + column);
  assert(!/\b(?:DROP|TRUNCATE|DELETE|ALTER|INSERT)\s/i.test(sql), 'List migration is additive without seeded data');
  assert(!/NOT\s+ENFORCED/i.test(sql), 'List snapshot CHECKs must be enforced');
  for (const [name, expression] of Object.entries({
    total:'total >= 0 AND total = JSON_LENGTH(rows_json)',
    time:'expires_at > as_of',
    shape:"JSON_TYPE(query_json)='OBJECT' AND JSON_TYPE(rows_json)='ARRAY' AND JSON_TYPE(summary_json)='OBJECT'",
  })) assert(normalizeSql(sql).includes(normalizeSql('CONSTRAINT chk_promotion_list_snapshot_' + name + ' CHECK (' + expression + ')')), 'List CHECK ' + name);
}
listSnapshotIntegrity(listMigration);
function quotaRestoreIntegrity(source){
  const sql=executableSql(source);
  assert(!/\b(?:INSERT|UPDATE|DELETE|DROP|TRUNCATE)\b/i.test(sql),'Quota marker cannot mutate history or permissions');
  assert.deepEqual([...sql.matchAll(/'ALTER TABLE ([^']+)'/g)].map(m=>m[1]),['nx_promotion_order_receipt ADD COLUMN quota_restored_at DATETIME(6) NULL DEFAULT NULL'],'Only one nullable additive quota marker is permitted');
  assert(sql.includes("TABLE_NAME='nx_promotion_order_receipt' AND COLUMN_NAME='quota_restored_at'"),'Idempotent column guard required');
  assert.equal((sql.match(/ALTER TABLE/g)||[]).length,1,'No other schema changes');
  assert(sql.includes("'SELECT 1'"),'Existing receipt path performs no mutation');
}
quotaRestoreIntegrity(quotaRestoreMigration);
function walletBillIntegrity(source){
  const sql=executableSql(source);
  assert.equal((sql.match(/CREATE TABLE/gi)||[]).length,1,'Wallet prerequisite defines only its existing E4 receipt');
  assert.match(sql,/CREATE TABLE IF NOT EXISTS nx_wallet_bill\s*\(/);
  for(const field of ['id BIGINT UNSIGNED NOT NULL PRIMARY KEY','user_id BIGINT NOT NULL','bill_no VARCHAR(128) NOT NULL','type VARCHAR(64) NOT NULL','token VARCHAR(16) NOT NULL','amount DECIMAL(18,6) NOT NULL','UNIQUE KEY uk_wallet_bill_no (bill_no)']) assert(sql.includes(field),'Missing E4 wallet receipt invariant '+field);
  assert(!/\b(?:ALTER|INSERT|UPDATE|DELETE|DROP|TRUNCATE)\b/i.test(sql),'Wallet prerequisite cannot modify existing schema or financial facts');
}
walletBillIntegrity(walletBillMigration);
for(const field of ['user_id BIGINT NOT NULL','type VARCHAR(64) NOT NULL','token VARCHAR(16) NOT NULL','amount DECIMAL(18,6) NOT NULL','UNIQUE KEY uk_wallet_bill_no (bill_no)']) assert.throws(()=>walletBillIntegrity(walletBillMigration.replace(field,'/* '+field+' */')),undefined,'Commented wallet prerequisite invariant '+field);
assert.throws(()=>walletBillIntegrity(walletBillMigration.replace('DECIMAL(18,6)','DECIMAL(20,2)')),undefined,'Wrong E4 amount precision');
assert.throws(()=>walletBillIntegrity(walletBillMigration+'\nUPDATE nx_wallet_bill SET amount=0;'),undefined,'Cannot rewrite E4 history');
for(const damaged of [quotaRestoreMigration.replace('ADD COLUMN quota_restored_at','ADD COLUMN absent'),quotaRestoreMigration.replace('DATETIME(6) NULL DEFAULT NULL','DATETIME(6) NOT NULL DEFAULT NOW(6)'),quotaRestoreMigration+'\nUPDATE nx_promotion_usage SET used_orders=0;'])assert.throws(()=>quotaRestoreIntegrity(damaged),undefined,'Quota marker gate rejects invalid/backfill mutation');
for (const marker of ['actor_id BIGINT', 'query_json JSON', 'rows_json JSON', 'summary_json JSON',
  'chk_promotion_list_snapshot_total', 'chk_promotion_list_snapshot_time', 'chk_promotion_list_snapshot_shape']) {
  assert.throws(() => listSnapshotIntegrity(listMigration.split('\n').filter(line => !line.includes(marker)).join('\n')), undefined, 'Lost list snapshot invariant ' + marker);
}
for (const source of [listMigration.replace(/\r\n?/g, '\n'), listMigration.replace(/\r\n?/g, '\n').replace(/\n/g, '\r\n')]) {
  const damaged = source.replace(/^([\t ]*CONSTRAINT[^\r\n]*)\r?$/gm, '-- $1');
  assert.notEqual(damaged, source, 'List CHECK comment mutation must change both LF and CRLF input');
  assert.throws(() => listSnapshotIntegrity(damaged), undefined, 'Commented list CHECKs rejected');
}
assert.throws(() => listSnapshotIntegrity(listMigration.replace('CHECK (expires_at > as_of)', 'CHECK (expires_at > as_of) NOT ENFORCED')), undefined, 'Unenforced list CHECK rejected');
for (const key of ['uk_promotion_receipt_order_id','uk_promotion_receipt_quote','fk_promotion_receipt_order','fk_promotion_receipt_quote']) {
  assert.throws(() => receiptIntegrity(receiptMigration.split('\n').filter(line => !line.includes(key)).join('\n')), undefined, 'Receipt gate must reject lost relation ' + key);
}
for (const source of [receiptMigration.replace(/\r\n?/g, '\n'), receiptMigration.replace(/\r\n?/g, '\n').replace(/\n/g, '\r\n')]) {
  const damaged = source.replace(/^([\t ]*CONSTRAINT fk_[^\r\n]*)\r?$/gm, '-- $1');
  assert.notEqual(damaged, source, 'Receipt FK comment mutation must change both LF and CRLF input');
  assert.throws(() => receiptIntegrity(damaged), undefined, 'Commented receipt FKs must fail');
}
assert.throws(() => receiptIntegrity(receiptMigration.replace('REFERENCES nx_order(id)', 'REFERENCES nx_order(user_id)')), undefined, 'Wrong receipt FK column must fail');
const count = check(document, fixtures, migration);
const mutations = [
  ['broken ref', d => { d.components.schemas.Quote.properties.quoteId.$ref = '#/components/schemas/Absent'; }],
  ['removed path', d => { delete d.paths['/api/orders/quote']; }],
  ['quote replay key omitted', d => { d.paths['/api/orders/quote'].post.parameters = []; }],
  ['permission removed', d => { delete d.paths['/api/admin/growth/promotions'].post['x-required-permission']; }],
  ['undeployed refund HTTP restored', d => { d.paths['/internal/growth/promotions/refunds/{refundRequestId}/hold'] = {}; }],
  ['bundle SKU limit expanded', d => { d.components.schemas.BundleOrderInput.properties.items.maxItems = 100; }],
  ['refund source verification removed', d => { d['x-contract'].refundLifecycleBoundary.sourceFacts = []; }],
  ['reward terminal revived', d => { d['x-contract'].transitions.reward.REVERSED = ['READY']; }],
  ['fixture bypass', d => { d['x-contract'].fixturePolicyBypass = true; }],
  ['obsolete shortage policy', d => { d.components.schemas.PromotionContract.properties.shortagePolicy.enum = ['REJECT_ORDER', 'REQUOTE_WITHOUT_PROMOTION']; }],
  ['draft requires full contract', d => { d.components.schemas.Draft.required = [...d.components.schemas.PromotionContract.required]; }],
  ['publication accepts incomplete draft', d => { d.components.schemas.PromotionContract = clone(d.components.schemas.Draft); }],
  ['publication accepts unapproved policy', d => { d.components.schemas.ResolvedApprovedPolicy = clone(d.components.schemas.Policy); }],
  ['simulation leaked into public quote', d => { d.paths['/api/admin/growth/promotions/{activityId}/simulate'].post['x-response-schema']='Quote'; }],
  ['audience unknown removed', d => { d.components.schemas.AudiencePreview.required=d.components.schemas.AudiencePreview.required.filter(k=>k!=='unknown'); }],
  ['reward beneficiary filter removed', d => { d.paths['/api/admin/growth/promotion-rewards'].get.parameters=d.paths['/api/admin/growth/promotion-rewards'].get.parameters.filter(p=>p.name!=='beneficiaryId'); }],
  ['created orders missing', d => { d.components.schemas.Metrics.required=d.components.schemas.Metrics.required.filter(k=>k!=='orders'); }],
  ['grouped report missing', d => { d.components.schemas.Metrics.required=d.components.schemas.Metrics.required.filter(k=>k!=='breakdown'); }],
  ['grouped report total optional', d => { d.components.schemas.MetricRewardBreakdown.required=d.components.schemas.MetricRewardBreakdown.required.filter(k=>k!=='total'); }],
  ['grouped report snapshot removed', d => { d.paths['/api/admin/growth/promotions/{activityId}/metrics'].get.parameters=d.paths['/api/admin/growth/promotions/{activityId}/metrics'].get.parameters.filter(p=>p.name!=='querySnapshot'); }],
  ['grouped export incomplete', d => { d['x-contract'].metricsBreakdown.exportIncludesAllGroups=false; }],
  ['grouped aggregate too narrow', d => { d.components.schemas.MetricRewardGroupRow.properties.issued.$ref='#/components/schemas/Amount'; }],
  ['aggregate reuses single entry', d => { d.components.schemas.PromotionListSummary.properties.budgets.items.$ref='#/components/schemas/Budget'; }],
  ['forced homepage placement', d => { d.components.schemas.PublicPromotion.properties.placement.enum = ['home.purchase-promotion']; }],
  ['historical disclosure optional', d => { d.components.schemas.ExpectedReward.required = d.components.schemas.ExpectedReward.required.filter(k=>k!=='disclosure'); }],
  ['refund terms missing', d => { d.components.schemas.RewardDisclosure.required = d.components.schemas.RewardDisclosure.required.filter(k=>k!=='refundTerms'); }],
  ['public disclosure extra internals', d => { d.components.schemas.RewardDisclosure.additionalProperties = true; }],
  ['admin basis selector removed', d => { d.components.schemas.RewardAdmin.required = d.components.schemas.RewardAdmin.required.filter(k=>k!=='dispositionOptions'); }],
  ['admin correction leaked to APP', d => { d.components.schemas.Reward.properties.dispositionOptions = d.components.schemas.RewardAdmin.properties.dispositionOptions; }],
  ['homepage filter removed', d => { d.paths['/api/promotions'].get.parameters = d.paths['/api/promotions'].get.parameters.filter(p => p.name !== 'placement'); }],
  ...['effectiveFirstPurchasePeople', 'directInvitedQualifiedPurchasers', 'rewardFailureMetrics', 'recoveryDuration'].map(name => [
    'required metric removed ' + name, d => { delete d.components.schemas.Metrics.properties[name]; d.components.schemas.Metrics.required = d.components.schemas.Metrics.required.filter(p => p !== name); },
  ]),
];
for (const [name, mutate] of mutations) {
  const damaged = clone(document); mutate(damaged);
  assert.throws(() => check(damaged, fixtures, migration), undefined, 'Gate failed to detect ' + name);
}
assert.throws(() => check(document, fixtures, migration.replace(
  'uk_promotion_obligation (activity_id, order_line_id',
  'uk_promotion_obligation (activity_id, version, order_line_id')), undefined, 'Gate failed to detect version dedup bypass');
const damagedFixture = clone(fixtures);
damagedFixture.examples.response_Quote.value.data.quantity = 2;
assert.throws(() => check(document, damagedFixture, migration), undefined, 'Gate failed to detect quantity regression');
assert.throws(() => quoteSemantics({...fixtures.examples.response_Quote.value.data, amountUsdt:'349.999999'}), undefined, 'Gate failed to detect allocation mismatch');
let sqlMutations = 0;
for (const name of [...requiredForeignKeys.map(fk => fk[0]), ...Object.keys(requiredSqlChecks)]) {
  const damagedSql = migration.split('\n').filter(line => !line.includes('CONSTRAINT ' + name + ' ')).join('\n');
  assert.throws(() => sqlIntegrity(damagedSql), undefined, 'Gate failed to detect removed constraint ' + name);
  sqlMutations++;
}
const withoutForeignKeys = migration.split('\n').filter(line => !/CONSTRAINT fk_/.test(line)).join('\n');
assert.throws(() => check(document, fixtures, withoutForeignKeys), undefined, 'Gate failed after removing all foreign keys');
sqlMutations++;
for (const [name, damagedSql] of [
  ['unenforced CHECK', migration.replace(/(CONSTRAINT chk_promotion_activity_count CHECK[^\r\n]*)/, '$1 NOT ENFORCED')],
  ['commented foreign keys (LF)', migration.replace(/\r\n?/g, '\n').replace(/^([\t ]*CONSTRAINT fk_[^\r\n]*)$/gm, '-- $1')],
  ['commented foreign keys (CRLF)', migration.replace(/\r\n?/g, '\n').replace(/\n/g, '\r\n').replace(/^([\t ]*CONSTRAINT fk_[^\r\n]*)\r?$/gm, '-- $1')],
  ['unsupported reversal asset', migration.replace("asset IN ('DEVICE','USDT','NEX')", "asset IN ('DEVICE','USDT','NEX','BTC')")],
  ['fractional device reversal', migration.replace('amount=FLOOR(amount) AND recovered=FLOOR(recovered)', 'amount >= 0 AND recovered >= 0')],
  ['fictional device target', migration.replace('REFERENCES nx_user_device(id)', 'REFERENCES nx_promotion_reward(obligation_id)')],
]) {
  assert.throws(() => sqlIntegrity(damagedSql), undefined, 'Gate failed to detect ' + name);
  sqlMutations++;
}
console.log('PASS growth promotions S1: ' + count + ' operations, all refs/permissions/request-response fixtures, '
  + templates.length + ' templates, ' + rewardStates.length + ' reward states, '
  + fixtures.positiveRequests.length + ' focused positive fixtures, ' + fixtures.negativeRequests.length + ' negative fixtures, '
  + ((fixtures.publicationCases.length + fixtures.policyGateCases.length) * 3) + ' publication rejection checks, '
  + (mutations.length + 3 + sqlMutations) + ' adversarial gate mutations, '
  + requiredForeignKeys.length + ' FK and ' + Object.keys(requiredSqlChecks).length + ' CHECK contracts.');
console.log('Durable order receipt: two unique bindings, two real FKs, six destructive relation mutations rejected; disabled CHECK constraints are rejected.');
console.log('Activity list snapshot: owner/query/rows/summary/time persisted, three enforced CHECKs, nine destructive mutations rejected.');
console.log('Refund quota restore: one nullable receipt marker without history mutation; three destructive mutations rejected.');
console.log('Existing E4 wallet receipt prerequisite: owner/type/token, unique bill number and DECIMAL(18,6); seven destructive mutations rejected.');
console.log('STATIC CONTRACT ONLY: no API, MySQL migration, asset settlement or production approval is claimed.');
