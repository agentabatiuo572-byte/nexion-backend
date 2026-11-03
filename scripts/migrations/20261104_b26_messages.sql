-- Optional translations are part of the original immutable version or editable draft.
SET NAMES utf8mb4;
SET @b26_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='nx_i18n_message_version' AND column_name='translations_json'),
    'SELECT 1','ALTER TABLE nx_i18n_message_version ADD COLUMN translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl; EXECUTE b26_stmt; DEALLOCATE PREPARE b26_stmt;
SET @b26_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='nx_content_copy_version' AND column_name='translations_json'),
    'SELECT 1','ALTER TABLE nx_content_copy_version ADD COLUMN translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl; EXECUTE b26_stmt; DEALLOCATE PREPARE b26_stmt;
SET @b26_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='nx_notification_campaign' AND column_name='translations_json'),
    'SELECT 1','ALTER TABLE nx_notification_campaign ADD COLUMN translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl; EXECUTE b26_stmt; DEALLOCATE PREPARE b26_stmt;
SET @b26_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='nx_nova_template' AND column_name='translations_json'),
    'SELECT 1','ALTER TABLE nx_nova_template ADD COLUMN translations_json JSON NULL');
PREPARE b26_stmt FROM @b26_ddl; EXECUTE b26_stmt; DEALLOCATE PREPARE b26_stmt;

-- Register the already shipped notification templates in I6. Existing revisions keep their ownership.
START TRANSACTION;
DROP TEMPORARY TABLE IF EXISTS b26_notification_templates;
CREATE TEMPORARY TABLE b26_notification_templates (
    message_key VARCHAR(128) PRIMARY KEY, zh VARCHAR(1024), en VARCHAR(1024), vi VARCHAR(1024), translations JSON
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
INSERT INTO b26_notification_templates (message_key,zh,en,vi,translations) VALUES
  ('notifications.business.purchase_delivered','付款成功，设备已开通，请查看订单。','Payment received and your device is ready. View your order.','Đã nhận thanh toán và thiết bị đã sẵn sàng. Xem đơn hàng.',NULL),
  ('notifications.business.purchase_timeout','付款超时，订单已取消。如已付款，请勿重复付款。','Payment timed out and your order was cancelled. If you already paid, do not pay again.','Thanh toán hết hạn, đơn hàng đã hủy. Nếu đã thanh toán, vui lòng không thanh toán lại.',NULL),
  ('notifications.business.purchase_review','付款状态已更新，请查看订单处理进度。','Your payment status changed. View your order progress.','Trạng thái thanh toán đã thay đổi. Xem tiến độ đơn hàng.',NULL),
  ('notifications.business.deposit','充值已到账，请查看钱包记录。','Your deposit has been credited. View your wallet history.','Tiền nạp đã được ghi có. Vui lòng xem lịch sử ví.',NULL),
  ('notifications.business.withdrawal_completed','提现已完成，请查看该笔提现详情。','Your withdrawal is complete. View this withdrawal''s details.','Rút tiền đã hoàn tất. Xem chi tiết giao dịch rút tiền này.',NULL),
  ('notifications.business.withdrawal_returned','提现未完成，资金已退回，请查看该笔提现详情。','Your withdrawal was not completed and funds were returned. View this withdrawal''s details.','Rút tiền chưa hoàn tất và tiền đã được hoàn lại. Xem chi tiết giao dịch này.',NULL),
  ('notifications.business.withdrawal_failed','提现失败，请查看该笔提现的状态、原因与处理指引。','Your withdrawal failed. View its status, reason and next steps.','Rút tiền không thành công. Xem trạng thái, lý do và hướng dẫn xử lý.',NULL),
  ('notifications.business.withdrawal_rejected','提现审核未通过，请查看该笔提现详情。','Your withdrawal review was not approved. View this withdrawal''s details.','Yêu cầu rút tiền không được duyệt. Xem chi tiết giao dịch này.',NULL),
  ('notifications.business.withdrawal_frozen','提现已冻结，请查看该笔提现的状态与处理指引。','Your withdrawal was frozen. View its status and next steps.','Yêu cầu rút tiền đã bị đóng băng. Xem trạng thái và hướng dẫn xử lý.',NULL),
  ('notifications.business.withdrawal_delayed','提现处理已延后，请查看该笔提现详情。','Your withdrawal processing was delayed. View this withdrawal''s details.','Việc xử lý rút tiền đã bị hoãn. Xem chi tiết giao dịch này.',NULL),
  ('notifications.business.withdrawal_review_required','提现需要复核，请查看该笔提现的状态与处理指引。','Your withdrawal requires review. View its status and next steps.','Yêu cầu rút tiền cần được xem xét. Xem trạng thái và hướng dẫn xử lý.',NULL),
  ('notifications.business.withdrawal_state','提现状态提醒：该笔提现失败或需要关注，请查看原单状态、原因与处理指引。','Withdrawal status reminder: this withdrawal failed or needs attention. View its current details and next steps.','Nhắc trạng thái rút tiền: giao dịch thất bại hoặc cần chú ý. Xem chi tiết hiện tại và hướng dẫn xử lý.',NULL),
  ('notifications.business.refund','订单退款已完成，请查看钱包记录。','Your order refund is complete. View your wallet history.','Hoàn tiền đơn hàng đã hoàn tất. Vui lòng xem lịch sử ví.',NULL),
  ('notifications.business.password','您的登录密码已更新。如非本人操作，请及时检查账号安全。','Your sign-in password was updated. Review account security if this was not you.','Mật khẩu đăng nhập đã được cập nhật. Kiểm tra bảo mật nếu không phải bạn thực hiện.',NULL),
  ('notifications.business.payment_method','您的收付款信息已变更。如非本人操作，请及时检查账号安全。','Your payment details changed. Review account security if this was not you.','Thông tin thanh toán đã thay đổi. Kiểm tra bảo mật nếu không phải bạn thực hiện.',NULL),
  ('notifications.business.security','您的账号安全状态已变更，请查看详情。','Your account security status changed. View details.','Trạng thái bảo mật tài khoản đã thay đổi. Vui lòng xem chi tiết.',JSON_OBJECT('id','Status keamanan akun Anda telah berubah. Lihat detailnya.','ms','Status keselamatan akaun anda telah berubah. Lihat butirannya.','fil','Nagbago ang status ng seguridad ng iyong account. Tingnan ang mga detalye.','ru','Статус безопасности вашей учётной записи изменился. Посмотрите подробности.','kk','Аккаунтыңыздың қауіпсіздік күйі өзгерді. Толығырақ қараңыз.','uz','Hisobingiz xavfsizlik holati o‘zgardi. Tafsilotlarni ko‘ring.')),
  ('notifications.business.new_signin','您的账号出现新的登录环境，请检查账号安全。','A new sign-in environment was detected. Review account security.','Phát hiện môi trường đăng nhập mới. Vui lòng kiểm tra bảo mật tài khoản.',NULL),
  ('notifications.business.support','客服有新回复，请打开会话查看。','Support sent a new reply. Open your conversation to view it.','Bộ phận hỗ trợ có phản hồi mới. Mở cuộc trò chuyện để xem.',NULL),
  ('notifications.business.device_alert','您的设备持续异常，请查看设备状态。','Your device has a persistent issue. Review its status.','Thiết bị đang gặp sự cố kéo dài. Vui lòng kiểm tra trạng thái.',NULL),
  ('notifications.business.trial_24h','设备试用将在24小时内结束，请查看试用详情。','Your device trial ends within 24 hours. View trial details.','Dùng thử thiết bị sẽ kết thúc trong 24 giờ. Vui lòng xem chi tiết.',NULL),
  ('notifications.business.trial_1h','设备试用将在1小时内结束，请查看试用详情。','Your device trial ends within 1 hour. View trial details.','Dùng thử thiết bị sẽ kết thúc trong 1 giờ. Vui lòng xem chi tiết.',NULL),
  ('notifications.business.trial_end','设备试用期已结束，请查看抵扣保留期详情。','Your device trial has ended. View the deduction retention details.','Thời gian dùng thử thiết bị đã kết thúc. Xem thời hạn giữ khấu trừ.',NULL),
  ('notifications.business.retention_24h','试用抵扣保留期将在24小时内结束，请查看详情。','Your trial deduction retention ends within 24 hours. View details.','Thời hạn giữ khấu trừ dùng thử sẽ kết thúc trong 24 giờ. Xem chi tiết.',NULL),
  ('notifications.business.retention_1h','试用抵扣保留期将在1小时内结束，请查看详情。','Your trial deduction retention ends within 1 hour. View details.','Thời hạn giữ khấu trừ dùng thử sẽ kết thúc trong 1 giờ. Xem chi tiết.',NULL),
  ('notifications.business.retention_end','试用抵扣保留期已结束，请查看试用详情。','Your trial deduction retention has ended. View trial details.','Thời hạn giữ khấu trừ dùng thử đã kết thúc. Vui lòng xem chi tiết.',NULL),
  ('notifications.details','查看详情','View details','Xem chi tiết',JSON_OBJECT('id','Lihat detail','ms','Lihat butiran','fil','Tingnan ang detalye','ru','Подробнее','kk','Толығырақ','uz','Batafsil')),
  ('notifications.disclosure.title','风险披露已更新，请重新确认','Risk disclosure updated','Công bố rủi ro đã được cập nhật',NULL),
  ('notifications.disclosure.body','{jurisdiction} 风险披露已更新至 {version}，请在继续受限操作前重新阅读并确认。','{jurisdiction} risk disclosure is now {version}. Please read and acknowledge it again.','Công bố rủi ro {jurisdiction} đã cập nhật lên {version}. Vui lòng đọc và xác nhận lại.',NULL),
  ('notifications.disclosure.cta','立即确认','Review now','Xác nhận ngay',NULL),
  ('notifications.deposit.title','充值已到账','Deposit credited','Nạp tiền thành công',NULL),
  ('notifications.deposit.body','{amount} USDT 已到账。','{amount} USDT has been credited to your wallet.','{amount} USDT đã được ghi có vào ví của bạn.',NULL),
  ('notifications.payment.unbound.title','支付方式已解绑','Payment method removed','Đã gỡ phương thức thanh toán',NULL),
  ('notifications.payment.unbound.body','尾号 {last4} 的支付方式已从 UVEL 账户解绑，不再用于后续扣款。','The payment method ending in {last4} was removed from your UVEL account and will no longer be charged.','Phương thức thanh toán có số cuối {last4} đã được gỡ khỏi tài khoản UVEL và sẽ không bị tính phí tiếp.',NULL),
  ('notifications.payment.rebind.title','请更换试用扣款支付方式','Replace your trial payment method','Thay phương thức thanh toán dùng thử',NULL),
  ('notifications.payment.rebind.body','尾号 {last4} 的支付方式正在被试用会话占用，请先完成换绑。','The payment method ending in {last4} is linked to an active trial. Please replace it first.','Phương thức thanh toán có số cuối {last4} đang được dùng cho phiên dùng thử. Vui lòng thay thế trước.',NULL),
  ('notifications.payment.cta','查看支付方式','View payment methods','Xem phương thức thanh toán',NULL);
INSERT INTO nx_i18n_message_version
  (message_key,version_no,zh_value,en_value,vi_value,translations_json,status,created_at,updated_at,is_deleted)
SELECT s.message_key,1,s.zh,s.en,s.vi,s.translations,'PUBLISHED',NOW(),NOW(),0
FROM b26_notification_templates s
WHERE NOT EXISTS (SELECT 1 FROM nx_i18n_message_version v WHERE v.message_key=s.message_key)
  AND NOT EXISTS (SELECT 1 FROM nx_i18n_message m WHERE m.message_key=s.message_key);
INSERT IGNORE INTO nx_i18n_message (message_key,locale,message_value,status,created_at,updated_at,is_deleted)
SELECT v.message_key,l.locale,CASE l.code WHEN 'en' THEN v.en_value WHEN 'vi' THEN v.vi_value WHEN 'zh' THEN v.zh_value
       ELSE JSON_UNQUOTE(JSON_EXTRACT(v.translations_json,CONCAT('$.',l.code))) END,1,NOW(),NOW(),0
FROM b26_notification_templates s
JOIN nx_i18n_message_version v ON v.message_key=s.message_key AND v.version_no=1 AND v.status='PUBLISHED' AND v.is_deleted=0
JOIN (SELECT 'zh' code,'zh-CN' locale UNION ALL SELECT 'en','en-US' UNION ALL SELECT 'vi','vi-VN'
      UNION ALL SELECT 'id','id-ID' UNION ALL SELECT 'ms','ms-MY' UNION ALL SELECT 'fil','fil-PH'
      UNION ALL SELECT 'ru','ru-RU' UNION ALL SELECT 'kk','kk-KZ' UNION ALL SELECT 'uz','uz-UZ') l
WHERE l.code IN ('en','vi','zh') OR JSON_CONTAINS_PATH(v.translations_json,'one',CONCAT('$.',l.code));
DROP TEMPORARY TABLE b26_notification_templates;
COMMIT;
