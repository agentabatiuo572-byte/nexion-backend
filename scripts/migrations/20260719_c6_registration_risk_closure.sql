-- C6 registration/login risk closure: fact-derived counters, bounded CAPTCHA recovery,
-- and a mandatory optimistic version row used as the transaction serialization point.
INSERT INTO nx_config_item(
  config_key,config_value,value_type,config_group,visibility,remark,status,created_at,updated_at,is_deleted)
VALUES
  ('auth.risk.c6.version','0','NUMBER','auth','ADMIN','C6 optimistic concurrency version',1,NOW(),NOW(),0),
  ('auth.risk.captcha_off_window','','STRING','auth','ADMIN','C6 absolute CAPTCHA restore deadline',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE is_deleted=0,status=1;

UPDATE nx_config_item
   SET status=0,
       remark='Retired by C6 fact-derived statistics closure',
       updated_at=NOW()
 WHERE config_key IN (
   'auth.risk.otp_sent_today',
   'auth.risk.captcha_triggered_today',
   'auth.risk.locked_short_count',
   'auth.risk.locked_long_count',
   'auth.risk.stuffing_clusters_7d'
 );
