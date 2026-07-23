-- ============================================================
-- P0 修复(2026-07-23): nx_commission_event 唯一键加 currency 列
-- 根因: F2 unilevel 每层派发 USDT + NEX 两条 commission_event(同 commission_type/order_no/user_id/layer_no,
--        仅 currency 异),原唯一键 uk(type,order,user,layer) 不含 currency → NEX 插入撞键
--        → SQLIntegrityConstraintViolationException → @Transactional 整笔回滚 → 生产零佣金
-- 修复: 唯一键加 currency,USDT/NEX 独立 event 不冲突
-- 验证: F2RT-001C 引擎数学(subagent 临时加 currency 验证)— L1 USDT 98.01 + NEX 4900.5;
--       L2 USDT 119.01(×InfluenceScore 2.38) + NEX 2380.21,全部落库无撞键
-- ============================================================

ALTER TABLE nx_commission_event DROP INDEX uk_commission_order_user_layer;
ALTER TABLE nx_commission_event ADD UNIQUE KEY uk_commission_order_user_layer (commission_type, order_no, user_id, layer_no, currency);
