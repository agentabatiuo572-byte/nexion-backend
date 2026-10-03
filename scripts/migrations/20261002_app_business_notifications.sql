-- APP inbox delivery only. Existing read/deleted rows and unrelated queued campaigns stay untouched.
UPDATE nx_user_preference SET notify_system=1, updated_at=NOW()
 WHERE is_deleted=0 AND notify_system=0;

UPDATE nx_notification n JOIN nx_user u ON u.id=n.user_id AND u.is_deleted=0
 SET n.push_status='DELIVERED',n.pushed_at=COALESCE(n.pushed_at,NOW()),n.next_push_at=NULL,n.updated_at=NOW(),
     n.title=CASE WHEN n.type='WALLET' THEN
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '充值已到账' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Đã nhận tiền nạp' ELSE 'Deposit credited' END
       WHEN n.biz_no LIKE 'PAYMENT_METHOD_UNBOUND:%' THEN
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '支付方式已解绑' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Đã gỡ phương thức thanh toán' ELSE 'Payment method removed' END
       ELSE CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '请更换支付方式' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Cần thay phương thức thanh toán' ELSE 'Replace your payment method' END END,
     n.body=CASE WHEN n.type='WALLET' THEN
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '充值金额已到账，请查看钱包。' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Tiền nạp đã vào ví. Vui lòng kiểm tra ví.' ELSE 'Your deposit has been credited. Please check your wallet.' END
       WHEN n.biz_no LIKE 'PAYMENT_METHOD_UNBOUND:%' THEN
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '支付方式已从账户解绑。' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Phương thức thanh toán đã được gỡ khỏi tài khoản.' ELSE 'The payment method has been removed from your account.' END
       ELSE CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '请更换试用会话使用的支付方式。' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Vui lòng thay phương thức thanh toán dùng cho phiên dùng thử.' ELSE 'Please replace the payment method used by your trial.' END END,
     n.cta_label=CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '查看详情' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Xem chi tiết' ELSE 'View details' END,
     n.cta_href=CASE WHEN n.type='WALLET' THEN '/pages/me/wallet' WHEN n.biz_no LIKE 'PAYMENT_METHOD_UNBOUND:%' THEN '/pages/me/wallet-cards' ELSE '/pages/me/wallet-cards-new' END
 WHERE n.is_deleted=0 AND n.read_flag=0 AND n.push_status='PENDING'
   AND ((n.type='WALLET' AND n.biz_no LIKE 'HDPAY:%')
     OR (n.type='PAYMENT_METHOD' AND (n.biz_no LIKE 'PAYMENT_METHOD_UNBOUND:%' OR n.biz_no LIKE 'PAYMENT_METHOD_REBIND:%')));

INSERT INTO nx_event_schema_registry
 (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,sampling_policy,
  current_revision,status,created_by,reason,is_deleted)
VALUES
 ('withdraw.processing','withdraw','money','server','D2/APP',1,'100%',1002,'ACTIVE','migration:app-messages','Persisted payout dispatch or submission',0),
 ('withdraw.payout_held','withdraw','money','server','D2/APP',1,'100%',1002,'ACTIVE','migration:app-messages','Persisted payout review hold',0),
 ('withdraw.account_frozen','withdraw','money','server','D2/APP',1,'100%',1002,'ACTIVE','migration:app-messages','Withdrawal frozen by account status',0),
 ('withdraw.account_restored','withdraw','money','server','D2/APP',1,'100%',1002,'ACTIVE','migration:app-messages','Withdrawal restored after account unfreeze',0)
ON DUPLICATE KEY UPDATE consumers='D2/APP',is_server_authoritative=1,sampling_policy='100%',status='ACTIVE',is_deleted=0;

INSERT INTO nx_event_schema_property
 (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,1002,0 FROM nx_event_schema_registry s
 JOIN (SELECT 'withdrawal_id' property_name,'id' property_type UNION ALL SELECT 'state','string') p
 WHERE s.event_name IN ('withdraw.processing','withdraw.payout_held','withdraw.account_frozen','withdraw.account_restored')
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),required_field=1,is_deleted=0;

-- A refund remains a fact when a historical withdrawal has no risk score. Never invent a score.
UPDATE nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
 SET p.required_field=0 WHERE s.event_name='withdraw.refunded' AND p.property_name='risk_score';
INSERT INTO nx_event_schema_property
 (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT id,'risk_score_status','string',0,0,1002,0 FROM nx_event_schema_registry WHERE event_name='withdraw.refunded'
ON DUPLICATE KEY UPDATE required_field=0,is_deleted=0;

INSERT INTO nx_event_schema_revision(id,current_revision) VALUES(1,1002)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,1002);
