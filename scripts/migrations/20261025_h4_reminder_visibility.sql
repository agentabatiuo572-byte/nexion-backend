-- Repair only H4 reminders already marked notified but hidden by the old SKIPPED status.
-- Keep original content, creation/read times and user preferences. Do not queue external push.
UPDATE nx_notification n
JOIN nx_growth_event_reminder r
  ON n.user_id=r.user_id AND n.biz_no=CONCAT('H4-REMINDER:',r.event_code)
SET n.push_status='DELIVERED', n.pushed_at=COALESCE(n.pushed_at,NOW()), n.updated_at=NOW()
WHERE r.notified_at IS NOT NULL
  AND n.type='EVENT' AND n.push_status='SKIPPED' AND n.is_deleted=0;
