// =====================================================================
// E2 任务定价 — 通过后端 API 创建初始数据（不直连 DB / 不走 SQL）
// ---------------------------------------------------------------------
// 用法：node scripts/seed-e2-tasks.mjs
//
// 说明：用户要求 E2 数据经 API 注入。本脚本依次：
//   1) POST /api/admin/auth/login  换 accessToken
//   2) POST /api/admin/devices/tasks  创建 6 类任务（Idempotency-Key 防重）
//   3) GET  /api/admin/devices/tasks  回读校验
//
// 字段口径：task_class / model / min_reward / max_reward / min_vram 严格对齐
// 后端 DeviceCatalogMapper.backfillDefaultTaskExtensions() 的 TK-1~TK-6 期望值，
// 其余展示字段满足 OpsDeviceService.requireTaskCommand 全部枚举/区间校验。
// 手机档位(nx_admin_phone_tier_reward)后端无创建 API，已由 SQL seed 单独灌入，
// 本脚本不涉及。
// =====================================================================

const BASE = "http://localhost:8110";
const USERNAME = "superadmin";
const PASSWORD = "Admin@123456";
const REASON = "初始化E2任务定价数据";
const OPERATOR = "superadmin";

async function login() {
  const r = await fetch(`${BASE}/api/admin/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const j = await r.json();
  if (j.code !== 0 || !j.data?.accessToken) {
    throw new Error(`login failed: ${r.status} ${JSON.stringify(j)}`);
  }
  return j.data.accessToken;
}

// 6 类任务（task_class/model/reward/vram 对齐后端 backfill 期望值）
const TASKS = [
  { name: "405B 大模型推理", price: 2.4,   unit: "/1k",  requirement: "需 NexionRack",    saturation: 0.55, taskClass: "llm-inference", model: "Llama-3.1-405B", minReward: 0.8,  maxReward: 2.4, minVram: "80GB" },
  { name: "70B 模型推理",    price: 0.9,   unit: "/1k",  requirement: "需 NexionBox Pro", saturation: 0.65, taskClass: "llm-inference", model: "Llama-3.1-70B",  minReward: 0.3,  maxReward: 0.9, minVram: "24GB" },
  { name: "图像生成",        price: 0.7,   unit: "/job", requirement: "S1+",              saturation: 0.72, taskClass: "image-gen",     model: "SDXL",           minReward: 0.2,  maxReward: 0.7, minVram: "12GB" },
  { name: "视频生成",        price: 4.2,   unit: "/job", requirement: "需 NexionBox Pro", saturation: 0.38, taskClass: "video-render",  model: "HunyuanVideo",   minReward: 1.6,  maxReward: 4.2, minVram: "48GB" },
  { name: "模型微调",        price: 7.5,   unit: "/job", requirement: "需 NexionBox Pro", saturation: 0.25, taskClass: "fine-tune",     model: "LoRA",           minReward: 3.0,  maxReward: 7.5, minVram: "48GB" },
  { name: "向量嵌入",        price: 0.22,  unit: "/1k",  requirement: "手机+",            saturation: 0.80, taskClass: "embedding",     model: "BGE-M3",         minReward: 0.06, maxReward: 0.22, minVram: "8GB"  },
];

async function main() {
  const token = await login();
  console.log(`[login] OK, token len=${token.length}`);

  let seq = 0;
  let okCount = 0;
  for (const t of TASKS) {
    seq += 1;
    const body = { ...t, status: "active", killInit: "派发中", reason: REASON, operator: OPERATOR };
    const r = await fetch(`${BASE}/api/admin/devices/tasks`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        Authorization: `Bearer ${token}`,
        "Idempotency-Key": `e2-seed-task-${seq}-${Date.now()}`,
      },
      body: JSON.stringify(body),
    });
    const j = await r.json().catch(() => ({}));
    if (r.status === 200 && j.code === 0) {
      okCount += 1;
      const d = j.data;
      console.log(`[#${seq}] OK  ${d.taskId} | ${d.name} | ${d.taskClass} | reward ${d.minReward}~${d.maxReward} | ${d.minVram}`);
    } else {
      console.log(`[#${seq}] FAIL http=${r.status} code=${j.code} msg=${j.message}`);
    }
  }
  console.log(`[create] ${okCount}/${TASKS.length} succeeded`);

  // 回读校验
  const v = await fetch(`${BASE}/api/admin/devices/tasks?pageNum=1&pageSize=50`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const vj = await v.json().catch(() => ({}));
  const records = vj.data?.records ?? [];
  console.log(`[verify] GET /tasks total=${vj.data?.total ?? "?"} returned=${records.length}`);
  for (const x of records) {
    console.log(`   - ${x.taskId} | ${x.name} | ${x.taskClass} | ${x.model} | ${x.minReward}~${x.maxReward} | ${x.minVram} | ${x.status}`);
  }
}

main().catch((e) => {
  console.error("ERROR:", e.message);
  process.exit(1);
});
