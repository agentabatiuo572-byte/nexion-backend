-- Publish the reviewed GLOBAL defaults only where the migration-owned v7 is still current.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
START TRANSACTION;
SET @published_at=UTC_TIMESTAMP();
-- Fresh installs can still have the formal v6 baseline. Reproduce the reviewed
-- brand-only v7 transition only for those defaults with both target labels free.
DROP TEMPORARY TABLE IF EXISTS handoff_terms_v7;
CREATE TEMPORARY TABLE handoff_terms_v7 AS
SELECT v.id old_id,v.locale,REPLACE(v.title,'NexGrid','UVEL') title,
       REPLACE(v.summary,'NexGrid','UVEL') summary,
       REPLACE(CAST(v.sections_json AS CHAR CHARACTER SET utf8mb4),'NexGrid','UVEL') sections_json
  FROM nx_legal_terms_version v
 WHERE v.jurisdiction='GLOBAL' AND v.locale IN ('zh','en','vi') AND v.version_label='v6'
   AND v.status='PUBLISHED' AND v.is_deleted=0 AND v.last_operator='migration:safe-baseline-terms-v6'
   AND NOT EXISTS (SELECT 1 FROM nx_legal_terms_version existing WHERE existing.locale=v.locale
                   AND existing.jurisdiction='GLOBAL' AND existing.version_label IN ('v7','v8'));
UPDATE nx_legal_terms_version v JOIN handoff_terms_v7 seed ON seed.old_id=v.id
   SET v.status='SUPERSEDED',v.updated_at=@published_at;
INSERT INTO nx_legal_terms_version
  (locale,jurisdiction,version_label,effective_at,status,title,summary,sections_json,revision,last_operator,
   published_at,created_at,updated_at,is_deleted)
SELECT locale,'GLOBAL','v7',@published_at,'PUBLISHED',title,summary,CAST(sections_json AS JSON),2,
       'migration:uvel-brand-terms-v7',@published_at,@published_at,@published_at,0 FROM handoff_terms_v7;
DROP TEMPORARY TABLE handoff_terms_v7;
DROP TEMPORARY TABLE IF EXISTS handoff_terms_v8;
CREATE TEMPORARY TABLE handoff_terms_v8 AS
SELECT v.id old_id,v.locale,seed.title,seed.summary,seed.sections_json
  FROM nx_legal_terms_version v JOIN (
  SELECT 'zh' locale, '服务条款' title,
         '本条款适用于 UVEL AI LTD 运营的 UVEL 服务。注册或使用即表示你同意以下内容。' summary,
         '[{"key":"account","title":"账户与安全","body":"你须年满 18 周岁并提供真实信息。请妥善保管登录密码、验证码和支付密码，账户内的操作由你负责；发现异常请立即联系客服。","sortOrder":10},{"key":"service","title":"服务内容","body":"UVEL 提供设备购买、算力任务、钱包及相关功能。功能、价格和规则以 App 内当前显示为准，可能因地区和时间调整。","sortOrder":20},{"key":"purchase","title":"购买与售后","body":"下单前请确认商品、价格和费用。退款与售后以结算页「售后说明」为准。","sortOrder":30},{"key":"earnings","title":"收益与奖励","body":"App 内显示的收益为预估，以实际到账为准，平台不承诺收益。奖励在满足当时公布的条件后发放。","sortOrder":40},{"key":"wallet","title":"钱包与提现","body":"提现前请核对收款信息、金额和手续费，链上转账确认后无法撤回。为保障账户安全，部分交易可能需要额外确认或延后处理。","sortOrder":50},{"key":"conduct","title":"禁止行为","body":"禁止欺诈、多账户刷取奖励、攻击或干扰平台等行为。违规可能导致功能受限、奖励取消或账户停用。","sortOrder":60},{"key":"updates","title":"条款更新与联系","body":"条款如有重大变更，我们会在 App 内通知。如有疑问，请联系 App 内客服或 staff@uvel.ai。","sortOrder":70}]' sections_json
  UNION ALL
  SELECT 'en' locale, 'Terms of Service' title,
         'These terms apply to the UVEL service operated by UVEL AI LTD. By signing up or using UVEL, you agree to them.' summary,
         '[{"key":"account","title":"Account & security","body":"You must be at least 18 and provide accurate information. Keep your password, verification codes and payment PIN safe. You are responsible for activity on your account; contact support right away if you notice anything unusual.","sortOrder":10},{"key":"service","title":"Our service","body":"UVEL offers device purchases, compute tasks, a wallet and related features. Features, prices and rules are as currently shown in the app and may vary by region and over time.","sortOrder":20},{"key":"purchase","title":"Purchases & after-sales","body":"Check the product, price and fees before you order. Refunds and after-sales service follow the After-sales information at checkout.","sortOrder":30},{"key":"earnings","title":"Earnings & rewards","body":"Earnings shown in the app are estimates; the amount actually credited applies. UVEL does not promise any earnings. Rewards are issued once the conditions published at the time are met.","sortOrder":40},{"key":"wallet","title":"Wallet & withdrawals","body":"Check the recipient details, amount and fees before you withdraw. On-chain transfers cannot be reversed once confirmed. To protect your account, some transactions may need extra confirmation or take longer.","sortOrder":50},{"key":"conduct","title":"Prohibited conduct","body":"Fraud, using multiple accounts to claim rewards, and attacking or disrupting the platform are prohibited. Violations may lead to restricted features, cancelled rewards or account suspension.","sortOrder":60},{"key":"updates","title":"Updates & contact","body":"We will notify you in the app of any material change to these terms. For questions, contact in-app support or staff@uvel.ai.","sortOrder":70}]' sections_json
  UNION ALL
  SELECT 'vi' locale, 'Điều khoản dịch vụ' title,
         'Điều khoản này áp dụng cho dịch vụ UVEL do UVEL AI LTD vận hành. Khi đăng ký hoặc sử dụng UVEL, bạn đồng ý với các nội dung dưới đây.' summary,
         '[{"key":"account","title":"Tài khoản và bảo mật","body":"Bạn phải đủ 18 tuổi và cung cấp thông tin chính xác. Hãy bảo mật mật khẩu, mã xác minh và mã PIN thanh toán. Bạn chịu trách nhiệm về mọi thao tác trên tài khoản của mình; nếu phát hiện bất thường, hãy liên hệ bộ phận hỗ trợ ngay.","sortOrder":10},{"key":"service","title":"Dịch vụ","body":"UVEL cung cấp tính năng mua thiết bị, tác vụ điện toán, ví và các tính năng liên quan. Tính năng, giá và quy định được áp dụng theo nội dung đang hiển thị trong ứng dụng và có thể thay đổi theo khu vực và thời gian.","sortOrder":20},{"key":"purchase","title":"Mua hàng và hậu mãi","body":"Hãy kiểm tra sản phẩm, giá và phí trước khi đặt hàng. Việc hoàn tiền và hậu mãi được áp dụng theo mục Hậu mãi tại trang thanh toán.","sortOrder":30},{"key":"earnings","title":"Thu nhập và phần thưởng","body":"Thu nhập hiển thị trong ứng dụng là số ước tính, số tiền thực nhận là căn cứ cuối cùng. UVEL không cam kết bất kỳ khoản thu nhập nào. Phần thưởng được trao khi bạn đáp ứng điều kiện được công bố tại thời điểm đó.","sortOrder":40},{"key":"wallet","title":"Ví và rút tiền","body":"Hãy kiểm tra thông tin người nhận, số tiền và phí trước khi rút. Giao dịch on-chain không thể hoàn tác sau khi đã được xác nhận. Để bảo vệ tài khoản, một số giao dịch có thể cần xác nhận thêm hoặc mất nhiều thời gian hơn.","sortOrder":50},{"key":"conduct","title":"Hành vi bị cấm","body":"Nghiêm cấm gian lận, dùng nhiều tài khoản để nhận thưởng, tấn công hoặc gây gián đoạn nền tảng. Vi phạm có thể dẫn đến việc hạn chế tính năng, hủy phần thưởng hoặc tạm khóa tài khoản.","sortOrder":60},{"key":"updates","title":"Cập nhật và liên hệ","body":"Chúng tôi sẽ thông báo trong ứng dụng khi điều khoản có thay đổi quan trọng. Nếu có thắc mắc, hãy liên hệ bộ phận hỗ trợ trong ứng dụng hoặc staff@uvel.ai.","sortOrder":70}]' sections_json
  ) seed ON seed.locale=v.locale
 WHERE v.jurisdiction='GLOBAL' AND v.version_label='v7' AND v.status='PUBLISHED' AND v.is_deleted=0
   AND v.last_operator='migration:uvel-brand-terms-v7'
   AND NOT EXISTS (SELECT 1 FROM nx_legal_terms_version existing WHERE existing.locale=v.locale
                   AND existing.jurisdiction='GLOBAL' AND existing.version_label='v8');

UPDATE nx_legal_terms_version v JOIN handoff_terms_v8 seed ON v.id=seed.old_id
   SET v.status='SUPERSEDED',v.updated_at=@published_at;
INSERT INTO nx_legal_terms_version
  (locale,jurisdiction,version_label,effective_at,status,title,summary,sections_json,revision,last_operator,
   published_at,created_at,updated_at,is_deleted)
SELECT locale,'GLOBAL','v8',@published_at,'PUBLISHED',title,summary,CAST(sections_json AS JSON),2,
       'migration:uvel-terms-v8',@published_at,@published_at,@published_at,0 FROM handoff_terms_v8;
INSERT INTO nx_audit_log
  (service_name,action,resource_type,resource_id,actor_type,actor_username,result,risk_level,detail_json,created_at,is_deleted)
SELECT 'nexion-backend','LEGAL_TERMS_PUBLISHED','LEGAL_TERMS_VERSION',CONCAT(locale,':GLOBAL:v8'),
       'SYSTEM','migration:uvel-terms-v8','SUCCESS','HIGH',
       JSON_OBJECT('previousVersion','v7','version','v8','locale',locale),@published_at,0 FROM handoff_terms_v8;
DROP TEMPORARY TABLE handoff_terms_v8;
COMMIT;
