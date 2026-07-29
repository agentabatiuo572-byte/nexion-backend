-- M4: complete the authoritative nine-category support SLA matrix.
-- INSERT IGNORE deliberately preserves every existing operations-configured row.
INSERT IGNORE INTO nx_support_sla_rule
  (category, first_response_mins, resolution_hours, queue, escalation,
   version, status, created_at, updated_at, is_deleted)
VALUES
  ('account',    30, 24, '账户台',     'C5 security',                1, 1, NOW(), NOW(), 0),
  ('withdrawal', 15, 12, '支付台',     'D2 withdrawal review',      1, 1, NOW(), NOW(), 0),
  ('deposit',    15, 12, '支付台',     'D1 deposit reconciliation', 1, 1, NOW(), NOW(), 0),
  ('kyc',        30, 24, '合规台',     'C4 KYC ledger',             1, 1, NOW(), NOW(), 0),
  ('hardware',   45, 48, '设备运维台', 'E5 device ops',             1, 1, NOW(), NOW(), 0),
  ('earnings',   30, 24, '收益台',     'F3/E6 earnings ledger',     1, 1, NOW(), NOW(), 0),
  ('genesis',    20, 18, '创世节点台', 'G4 Genesis economy',        1, 1, NOW(), NOW(), 0),
  ('technical',  60, 72, '技术支持台', 'A3 system config',          1, 1, NOW(), NOW(), 0),
  ('other',      60, 72, '综合支持台', 'M2 manual triage',          1, 1, NOW(), NOW(), 0);
