-- Product confirmed on 2026-11-20 that the platform had no opening lock stock.
-- Keep the original principal and provenance for reconciliation.
UPDATE nx_treasury_legacy_lock_liability
   SET status = 'VOID',
       source_note = CONCAT(source_note, ' | 2026-11-20: voided; product confirmed no pre-launch lock stock')
 WHERE liability_no = 'D3-LEGACY-OPENING-20260720'
   AND source_ref = 'prd:d3:legacy-lock-opening'
   AND status IN ('ACTIVE', 'LOCKED')
   AND is_deleted = 0;
