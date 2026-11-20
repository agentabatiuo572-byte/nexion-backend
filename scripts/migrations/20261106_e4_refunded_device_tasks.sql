-- Stop unfinished work for already-refunded purchased devices. Preserve posted and retired evidence.
UPDATE nx_user_device d
  JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id
  LEFT JOIN nx_compute_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
    AND t.source_environment='PRODUCTION' AND t.is_deleted=0
    AND t.status IN ('CLAIMED','RUNNING','PAUSED') AND t.completed_at IS NULL AND t.proof_consumed_at IS NULL
    AND NOT EXISTS(SELECT 1 FROM nx_compute_retired_task a WHERE a.task_id=t.id)
  LEFT JOIN nx_simulated_task s ON s.user_device_id=d.id AND s.user_id=d.user_id
    AND s.status='RUNNING' AND s.completed_at IS NULL AND s.posted_at IS NULL
    AND NOT EXISTS(SELECT 1 FROM nx_compute_retired_task a WHERE a.user_id=s.user_id AND a.task_no=s.task_no COLLATE utf8mb4_0900_ai_ci)
  LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
    AND (r.online_status<>'OFFLINE' OR r.active_task_no IS NOT NULL)
  LEFT JOIN nx_simulated_device_runtime sr ON sr.user_device_id=d.id AND sr.user_id=d.user_id
    AND (sr.state NOT IN ('STOPPED','EXPIRED') OR sr.current_task_no IS NOT NULL OR sr.next_action_at IS NOT NULL)
   SET t.status='CANCELLED',t.last_error='ORDER_REFUND',t.next_retry_at=NULL,t.updated_at=NOW(6),
       s.status='CANCELLED',s.cancel_reason='ORDER_REFUND',
       r.online_status='OFFLINE',r.paused_reason='ORDER_REFUND',r.active_task_no=NULL,r.updated_at=NOW(6),
       sr.state='STOPPED',sr.stop_at=COALESCE(sr.stop_at,d.deactivated_at,NOW(6)),sr.stop_after_current=0,
       sr.finish_after_at=NULL,sr.current_task_no=NULL,sr.next_action_at=NULL,sr.last_error='ORDER_REFUND'
 WHERE d.is_deleted=0 AND d.ownership_status='REFUNDED'
   AND d.source_environment='PRODUCTION' AND d.run_id='' AND d.source_channel<>'PROMOTION_GIFT'
   AND o.is_deleted=0 AND o.payment_status='REFUNDED' AND o.order_status='REFUNDED';
