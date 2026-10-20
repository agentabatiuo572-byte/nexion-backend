# SPEC-03 后端验收记录

状态：**本步通过**（2026-10-20）。允许进入 SPEC-04/06；不代表四端最终验收、部署或发布通过。

候选：backend detached `4113c61e19509ec8e7e0ca182d4ac0d35f1ba445` 上未提交改动。精确源文件哈希及测试清单在专用证据目录 `D:/workspace/audit-artifacts/app-single-session-phone-replacement-20261019/` 的 `SPEC03-SOURCE-MANIFEST.json` / `SPEC03-TEST-MANIFEST.json`；123个累计候选代码/脚本文件，22个相关测试类最新报告合计686项，failure/error/skip均0。包含上游受影响回归，不是全部1284测试类。

## BE03-A1：策略原子性、权限与审计

- 四参数完整组、单参组合校验、版本CAS、整数/溢出、显式0、并发出版竞争、持久原回执及A2/A4回滚：8项真实MySQL出版测试及8项单元测试通过。自建8个schema逐项清理；没有连接生产。
- 新增6项独立权限测试：当前DB的A2 approve AND E6 read允许checker；少任一、旧JWT、停用/删除/非管理员拒绝。直接调用不绕A2，原回执查询继续复验当前权限。真实maker/checker HTTP成功，2个A2 ticket均approved，PPO与A4各2条；policy 1→2→3，最终四项值恢复off/1/30/2，旧回执仍是version2且currentResource为version3。
- 迁移canonical WhatIf/执行/重复成功；补existing flag权限的insert-if-absent依赖后再次重复，保留version3与2条原回执，不改变现存显式值。普通RISK/SUPPORT未获特批细权。

## BE03-A2：C5处置与数据范围

- 当前权限、账户数据范围、证据归属、跨账号/run、只读普通客服、确认与理由、受限分页的18项应用服务测试及6项实际method-security测试通过。
- 12项独立AdminRead适配测试覆盖冻结账号、跨audience/deleted拒绝、overview锁序与锁读绑定、领域权威终态、受限历史元数据与审计引用。不暴露evidenceJson、凭据、requestHash或原命令请求正文。
- 最新API在localhost18119。`SPEC03-LIVE-C5-1.json` 16项：GET前后绑定/申请/授权/命令/核实记录一致；明确核实→签发→按号/键恢复；冻结后概览、撤销、关闭及原成功重放；冻结新签发403。绑定版本及绑定安装未变，授权已REVOKED、另一申请CLOSED。
- `SPEC03-LIVE-HISTORY-FINAL.json` 最新候选4项：GRANT的核实引用/操作者/目标/审计操作可读且不泄露原证据；REQUEST包含关闭审计；未知历史计数仍null。由synthetic UID900003夹具完成，不冒充原生真实换机。

## BE03-A3：恢复、冲突和异常

- A2真实approve→dispatcher→publisher→GlobalExceptionHandler结构化冲突通过；A2 wrapper不吞data。未知操作返回404 OPERATION_RESULT_UNKNOWN；禁止据此造新键。
- 原命令跨24h、权限撤回、跨run原键、账号冻结、授权终态、版本后续变化均有相关单元/真实MySQL/HTTP证据。领域真实13项和会话投影/激活回归通过。
- 500只读事务冲突在LIVE-READS-2保留，已通过正确事务及统一锁序修复；LIVE-READS-3和最终history重启读回200。测试编译失败TARGETED5/6已保留并在7关闭。

## 检查与独立复核

最新必要重跑：TARGETED10=177/0/0/0，TARGETED11=186/0/0/0（含架构23、OpsUser和幂等），TARGETED12=59/0/0/0（含最新历史与架构）。不将重跑次数相加当独立覆盖量。早期TARGETED3/4/7/8及COMPILE1/2/9保留原日志。

独立复核由capacity_review、session_auth_resume、phone_domain_resume完成；发现的权限、冻结恢复、旧RR投影、跨run定位、过期会话投影、A4字段与冲突细节问题已修并定向重验。无未解决P0/P1/P2。复核报告原发现保留，最新整改以本记录和原始测试/HTTP输出为准。

## 范围与后续

本步无提交/推送、无线上TEST或生产变更。隔离API仍有基线无关scheduler缺表/告警，不宣称整个环境健康。本次maker/checker夹具管理员已停用，特批已撤销，测试账号冻结；保留任务数据库和审计供后续联调。MySQL真实单测owned schemas已清理。

完整DTO与权限交给 BACKEND-CONTRACT-NOTES.md 和 FRONTEND-IMPLEMENTATION-NOTES.md。SPEC04/06可并行，SPEC05消费App候选；单台三星不能证明SPEC07两安装接管全链路。未完成各端验收前不回写最终完成结论、不删除临时任务目录。

### 追加接线验收（SPEC06反馈）

核实POST结果未知此前缺少GET恢复入口，已补仅当前GRANT细权可查询的精确账号/原键GET，并修正前置filter额外read权限误门。新增独立3服务/1method-security及root filter回归；TARGETED13=178/0/0/0。重启后HTTP3项通过：原核实快照完全相同，缺失及跨账号均404，未重新POST。最终22类相关报告为691项/0失败/0错误/0跳过，最新source/test manifest已刷新；本步继续PASSED。
