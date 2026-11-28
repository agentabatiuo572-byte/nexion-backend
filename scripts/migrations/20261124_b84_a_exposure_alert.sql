-- Run only after all old Cregis writers stop. They can reopen this retired alert.
-- Resolve the historical threshold alert without reopening switches or touching funds.
UPDATE nx_cregis_risk_alert
   SET resolved_at = NOW(6)
 WHERE kind = 'UNRESOLVED_EXPOSURE'
   AND alert_key = 'unresolved-exposure-500'
   AND resolved_at IS NULL;
