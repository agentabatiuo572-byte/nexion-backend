import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

// Read-only package-integrity check. This is not a product or implementation test.
const root = path.dirname(fileURLToPath(import.meta.url));
const readJson = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8'));
const manifest = readJson('manifest.json');
const state = readJson('EXECUTION-STATE.json');
const problems = [];
const check = (ok, message) => { if (!ok) problems.push(message); };
const within = target => {
  const rel = path.relative(root, target);
  return rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel);
};

check(manifest.taskId === state.taskId, 'Task identifiers differ');
check(manifest.approval.status === 'product_approved', 'Product approval missing');
check(manifest.files.length === 5, 'Expected all five source snapshots');
for (const item of manifest.files) {
  const target = path.resolve(root, item.path);
  check(within(target), `Snapshot escapes package: ${item.path}`);
  if (!within(target) || !fs.existsSync(target)) {
    problems.push(`Snapshot missing: ${item.path}`);
    continue;
  }
  const bytes = fs.readFileSync(target);
  check(bytes.length === item.bytes, `Snapshot size changed: ${item.path}`);
  check(crypto.createHash('sha256').update(bytes).digest('hex') === item.sha256,
    `Snapshot digest changed: ${item.path}`);
}

const byId = new Map(state.steps.map(step => [step.id, step]));
check(state.steps.length === 9 && byId.size === 9, 'Expected nine unique steps');
const active = new Set(), visited = new Set();
function visit(id) {
  if (visited.has(id)) return;
  if (active.has(id)) { problems.push(`Dependency cycle: ${id}`); return; }
  const step = byId.get(id);
  if (!step) { problems.push(`Unknown dependency: ${id}`); return; }
  active.add(id);
  for (const dependency of step.dependsOn) visit(dependency);
  active.delete(id);
  visited.add(id);
}
for (const step of state.steps) {
  visit(step.id);
  const target = path.resolve(root, step.spec);
  check(within(target) && fs.existsSync(target), `Missing SPEC: ${step.spec}`);
  check(['pending', 'running', 'blocked', 'passed'].includes(step.status), `Invalid status: ${step.id}`);
  if (step.status === 'passed') {
    check(step.checks.length > 0 && Boolean(step.independentReview), `No completion evidence: ${step.id}`);
  }
}

const contract = fs.readFileSync(path.join(root,
  'inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md'), 'utf8');
const ids = [...contract.matchAll(/^\| (S\d{2}) \|/gm)].map(match => match[1]);
const index = fs.readFileSync(path.join(root, 'SPEC-INDEX.md'), 'utf8');
const mapped = [...index.matchAll(/^\| (S\d{2}) \|/gm)].map(match => match[1]);
check(ids.length === 41 && new Set(ids).size === 41, 'Boundary source must contain S01-S41');
check(JSON.stringify(ids) === JSON.stringify(mapped), 'Boundary ownership mapping differs');
check(JSON.stringify(ids) === JSON.stringify(state.boundaryResults.map(item => item.id)),
  'Boundary state mapping differs');

// Validate authored relative file links and the common contract links.
// Full historical PRD snapshots intentionally retain pre-existing references.
const authored = fs.readdirSync(root).filter(name => name.endsWith('.md'))
  .concat(fs.readdirSync(path.join(root, 'specs')).filter(name => name.endsWith('.md')).map(name => `specs/${name}`))
  .concat(['inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md']);
for (const name of authored) {
  const file = path.join(root, name);
  const text = fs.readFileSync(file, 'utf8');
  for (const match of text.matchAll(/\]\(([^)]+)\)/g)) {
    const link = match[1].replace(/^<|>$/g, '').split('#')[0];
    if (!link || /^[a-z][a-z\d+.-]*:/i.test(link)) continue;
    const target = path.resolve(path.dirname(file), link);
    check(within(target) && fs.existsSync(target), `Unresolved package link: ${name} -> ${link}`);
  }
}
if (problems.length) {
  for (const problem of problems) process.stderr.write(`${problem}\n`);
  process.exitCode = 1;
} else {
  process.stdout.write('PASS: five snapshot hashes, nine SPEC dependencies, 41 boundary mappings and authored file links. Product implementation remains unverified.\n');
}
