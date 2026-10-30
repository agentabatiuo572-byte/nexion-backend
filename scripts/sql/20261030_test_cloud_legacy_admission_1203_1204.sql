-- Explicit TEST-only review input for the two activated, unexecuted tasks observed in baseline.json.
-- Not a startup migration. Run once on TEST only after deploying the empty admission schema.
-- Does not alter tasks, runtime, orders, balances, receipts, ledgers or earning events.
-- Enable nexion.compute-task.test-cloud.fleet-legacy-enabled separately only in TEST/dev.
INSERT INTO nx_test_cloud_legacy_admission
  (device_id,owner_id,instance_no,source_order_no,source_channel,old_task_no,task_config_id,
   deployment_scope,enabled,reason,evidence_sha256,reviewed_at)
SELECT d.id,d.user_id,d.instance_no,d.source_order_no,d.source_channel,t.task_no,t.task_config_id,
       'TEST',1,'User-authorized 2026-10-30 TEST recovery: reviewed activated old task has no execution or settlement evidence',
       '512af50145b47cde80b08e060f07c3edf4594c7350bb2bb3b5f6d5d95eb84f9b',NOW(6)
  FROM (
    SELECT 1203 device_id,'NEX-ORD-E5F05725DF108D97D44361AA' instance_no,
           'ORD-F83467CD34A042A8AD4CFB35FE7332A1' order_no,'ORDER' channel,
           'CTA-1564D04228294172A8E0A0F08FCEF293' task_no
    UNION ALL SELECT 1204,'DEV-TI-27D1233EBF21434FB85EC6B49D66507F',
           'TIO-27D1233EBF21434FB85EC6B49D66507F','TRADE_IN','CTA-050E786C8A3641678BF606D734BA0993'
  ) reviewed
  JOIN nx_user_device d ON d.id=reviewed.device_id AND d.user_id=60723153007
    AND d.instance_no=reviewed.instance_no AND d.source_order_no=reviewed.order_no AND d.source_channel=reviewed.channel
  JOIN nx_user u ON u.id=d.user_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
  JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id AND o.payment_status='PAID'
    AND o.order_status='COMPLETED' AND o.subtotal_usdt>0 AND o.amount_usdt>=0 AND o.is_deleted=0
  JOIN nx_compute_task t ON t.task_no=reviewed.task_no AND t.user_id=d.user_id AND t.user_device_id=d.id
    AND t.task_config_id='E2-20260924-EM' AND t.model_name='BGE-M3' AND t.client_name='UVEL App'
    AND t.status IN ('CLAIMED','RUNNING') AND t.source_environment='PRODUCTION' AND t.is_deleted=0
    AND t.proof_consumed_at IS NULL AND t.completed_at IS NULL AND COALESCE(t.last_error,'')=''
 WHERE d.status='ACTIVE' AND d.ownership_status='OWNED' AND d.activated_at IS NOT NULL
   AND d.deactivated_at IS NULL AND d.pending_deactivate=0 AND d.is_deleted=0
   AND d.source_environment='PRODUCTION' AND d.run_id=''
   AND (SELECT COUNT(*) FROM nx_compute_task active WHERE active.user_id=d.user_id AND active.user_device_id=d.id
         AND active.status IN ('CLAIMED','RUNNING') AND active.source_environment='PRODUCTION' AND active.is_deleted=0)=1
   AND NOT EXISTS(SELECT 1 FROM nx_test_cloud_execution e WHERE e.task_no=t.task_no)
   AND NOT EXISTS(SELECT 1 FROM nx_compute_receipt r WHERE r.task_no=t.task_no)
   AND NOT EXISTS(SELECT 1 FROM nx_wallet_ledger l WHERE l.biz_no=t.task_no OR l.biz_no=CONCAT('CTR-',RIGHT(t.task_no,32)))
   AND NOT EXISTS(SELECT 1 FROM nx_earning_event e WHERE e.event_no=CONCAT('EARN-',t.task_no)
                    OR e.receipt_no=CONCAT('CTR-',RIGHT(t.task_no,32)))
   AND NOT EXISTS(SELECT 1 FROM nx_audit_log a WHERE a.resource_id=t.task_no AND COALESCE(a.action,'')<>'TASK_ASSIGNMENT_CLAIMED')
   AND NOT EXISTS(SELECT 1 FROM nx_user_device_runtime r WHERE r.user_device_id=d.id
                    AND (COALESCE(r.agent_version,'')<>'' OR COALESCE(r.client_name,'') NOT IN ('','UVEL App')
                         OR COALESCE(r.paused_reason,'')<>''
                         OR (COALESCE(r.active_task_no,'')<>'' AND r.active_task_no<>t.task_no)));

-- Expected: exactly two immutable review rows with the baseline digest above. The backend rechecks
-- the entire paid ORDER / completed TRADE_IN delivery contract on discovery and every live operation.
SELECT device_id,owner_id,instance_no,source_order_no,source_channel,old_task_no,task_config_id,
       deployment_scope,enabled,evidence_sha256 FROM nx_test_cloud_legacy_admission ORDER BY device_id;
