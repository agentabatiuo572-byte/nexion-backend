-- Publish a conservative v6 baseline for the three canonical App locales.
-- High-risk licensing, custody, market-performance and jurisdiction claims are
-- intentionally excluded until their separate approval records exist.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT IGNORE INTO nx_legal_terms_version
  (locale, jurisdiction, version_label, effective_at, status, title, summary,
   sections_json, revision, last_operator, published_at, created_at, updated_at, is_deleted)
VALUES
  ('vi', 'GLOBAL', 'v6', '2026-09-02 00:00:00', 'PUBLISHED',
   'Điều khoản dịch vụ',
   'Các điều khoản này giải thích những quy tắc cơ bản khi bạn tạo tài khoản và sử dụng các tính năng NexGrid được cung cấp cho mình.',
   JSON_ARRAY(
     JSON_OBJECT('key','acceptance-eligibility','title','Chấp nhận & điều kiện','body','Bạn phải đủ 18 tuổi, có năng lực ký kết hợp đồng và được phép sử dụng dịch vụ theo pháp luật áp dụng tại nơi bạn sinh sống. Bạn phải cung cấp thông tin chính xác và cập nhật thông tin khi có thay đổi.','sortOrder',10),
     JSON_OBJECT('key','service-scope','title','Phạm vi dịch vụ','body','NexGrid cung cấp các tính năng tài khoản, thiết bị, tác vụ và giao dịch theo trạng thái hiển thị trong ứng dụng. Tính năng có thể khác nhau theo tài khoản, thiết bị, khu vực và thời điểm; trạng thái phía máy chủ là căn cứ xử lý hiện hành.','sortOrder',20),
     JSON_OBJECT('key','account-security','title','Tài khoản & bảo mật','body','Bạn có trách nhiệm bảo vệ thông tin đăng nhập, mã xác minh và thiết bị của mình. Hãy báo ngay cho bộ phận hỗ trợ nếu phát hiện truy cập trái phép. Chúng tôi có thể tạm hạn chế hoạt động để bảo vệ tài khoản hoặc điều tra hành vi lạm dụng.','sortOrder',30),
     JSON_OBJECT('key','hardware-operation','title','Thiết bị & vận hành','body','Thông tin sản phẩm, giá, điều kiện giao hàng, đổi trả và kích hoạt được hiển thị trước khi bạn xác nhận giao dịch. Quyền bắt buộc của người tiêu dùng theo pháp luật áp dụng không bị loại trừ. Khả năng và kết quả của thiết bị có thể thay đổi theo cấu hình và điều kiện sử dụng.','sortOrder',40),
     JSON_OBJECT('key','estimates-rewards','title','Ước tính & phần thưởng','body','Mọi con số dự báo hoặc ước tính trong ứng dụng chỉ là thông tin tham khảo, không phải cam kết về kết quả tương lai. Phần thưởng chỉ được ghi nhận khi điều kiện đang hiển thị được đáp ứng và máy chủ xác nhận giao dịch hợp lệ.','sortOrder',50),
     JSON_OBJECT('key','wallet-transactions','title','Ví & giao dịch','body','Trước khi xác nhận, hãy kiểm tra địa chỉ nhận, số tiền, phí, giới hạn và trạng thái kênh đang hiển thị. Thời gian xử lý và kết quả cuối cùng theo trạng thái đơn phía máy chủ. Một giao dịch có thể bị giữ lại hoặc từ chối khi cần kiểm tra an toàn hay tuân thủ pháp luật áp dụng.','sortOrder',60),
     JSON_OBJECT('key','referrals','title','Giới thiệu & ưu đãi','body','Chương trình giới thiệu hoặc ưu đãi chỉ áp dụng khi được bật và công bố trong ứng dụng. Điều kiện, thời hạn và giá trị thực tế theo quy tắc đang hiển thị cùng xác nhận phía máy chủ. Spam, tài khoản giả và lạm dụng chương trình đều bị cấm.','sortOrder',70),
     JSON_OBJECT('key','prohibited-conduct','title','Hành vi bị cấm','body','Bạn không được sử dụng NexGrid cho hoạt động trái pháp luật, gian lận, xâm nhập trái phép, né cơ chế an toàn, tạo nhiều tài khoản để lạm dụng ưu đãi, hoặc gây cản trở dịch vụ và người dùng khác. Vi phạm có thể dẫn đến hạn chế hoặc chấm dứt quyền truy cập.','sortOrder',80),
     JSON_OBJECT('key','fees-changes','title','Phí, thuế & thay đổi','body','Các khoản phí và số tiền thực nhận phải được hiển thị trước khi bạn xác nhận giao dịch. Bạn tự chịu trách nhiệm về nghĩa vụ thuế áp dụng cho mình. Khi thay đổi quan trọng ảnh hưởng đến quyền hoặc nghĩa vụ, ứng dụng sẽ yêu cầu bạn xem và xác nhận phiên bản mới trước khi tiếp tục.','sortOrder',90),
     JSON_OBJECT('key','rights-support','title','Quyền của bạn & hỗ trợ','body','Không nội dung nào trong các điều khoản này loại trừ quyền bắt buộc mà pháp luật áp dụng dành cho bạn. Nếu có câu hỏi, khiếu nại hoặc tranh chấp, hãy dùng kênh hỗ trợ chính thức được hiển thị trong ứng dụng để được ghi nhận và xử lý.','sortOrder',100)
   ), 1, 'migration:safe-baseline-terms-v6', '2026-09-02 00:00:00', NOW(), NOW(), 0),
  ('zh', 'GLOBAL', 'v6', '2026-09-02 00:00:00', 'PUBLISHED',
   '服务条款',
   '本条款说明你创建账户及使用向你开放的 NexGrid 功能时应遵守的基本规则。',
   JSON_ARRAY(
     JSON_OBJECT('key','acceptance-eligibility','title','接受与资格','body','你须年满 18 周岁、具备签约能力，并可依居住地适用法律使用本服务。你应提供真实准确的信息，并在信息发生变化时及时更新。','sortOrder',10),
     JSON_OBJECT('key','service-scope','title','服务范围','body','NexGrid 按应用内显示的状态提供账户、设备、任务及交易相关功能。功能可能因账户、设备、地区和时间而不同；当前处理结果以服务端状态为准。','sortOrder',20),
     JSON_OBJECT('key','account-security','title','账户与安全','body','你有责任保护登录凭据、验证码和所用设备。如发现未授权访问，请立即联系支持。为保护账户或调查滥用行为，我们可能临时限制相关操作。','sortOrder',30),
     JSON_OBJECT('key','hardware-operation','title','设备与运行','body','产品信息、价格、交付、退换和激活条件会在你确认交易前显示。适用法律规定的不可放弃消费者权利不受排除。设备能力与结果可能随配置和使用条件变化。','sortOrder',40),
     JSON_OBJECT('key','estimates-rewards','title','估算与奖励','body','应用内任何预测或估算仅供参考，不构成对未来结果的承诺。奖励仅在满足当时显示的条件且服务端确认交易有效后记入。','sortOrder',50),
     JSON_OBJECT('key','wallet-transactions','title','钱包与交易','body','确认前请核对页面显示的收款地址、金额、费用、限额及通道状态。处理时间和最终结果以服务端订单状态为准。为开展安全检查或遵守适用法律，交易可能被暂停或拒绝。','sortOrder',60),
     JSON_OBJECT('key','referrals','title','推荐与优惠','body','推荐或优惠活动仅在应用内启用并公布时适用。条件、期限及实际价值以当时显示的规则和服务端确认为准。禁止垃圾推广、虚假账户及其他活动滥用行为。','sortOrder',70),
     JSON_OBJECT('key','prohibited-conduct','title','禁止行为','body','你不得利用 NexGrid 从事违法、欺诈、未经授权的访问、规避安全机制、使用多账户滥用优惠，或干扰服务及其他用户。违规可能导致访问受限或终止。','sortOrder',80),
     JSON_OBJECT('key','fees-changes','title','费用、税费与变更','body','费用和实际到账金额应在你确认交易前显示。你应自行承担适用于你的税务义务。如重要变更影响你的权利或义务，应用会要求你查看并明确确认新版本后再继续。','sortOrder',90),
     JSON_OBJECT('key','rights-support','title','你的权利与支持','body','本条款不排除适用法律赋予你的不可放弃权利。如有问题、投诉或争议，请使用应用内显示的官方支持渠道提交，以便记录并处理。','sortOrder',100)
   ), 1, 'migration:safe-baseline-terms-v6', '2026-09-02 00:00:00', NOW(), NOW(), 0),
  ('en', 'GLOBAL', 'v6', '2026-09-02 00:00:00', 'PUBLISHED',
   'Terms of Service',
   'These terms explain the basic rules that apply when you create an account and use NexGrid features made available to you.',
   JSON_ARRAY(
     JSON_OBJECT('key','acceptance-eligibility','title','Acceptance & eligibility','body','You must be at least 18, able to enter a contract, and permitted to use the service under the laws that apply where you live. You must provide accurate information and update it when it changes.','sortOrder',10),
     JSON_OBJECT('key','service-scope','title','Service scope','body','NexGrid provides account, device, task, and transaction features according to the status shown in the app. Features may vary by account, device, location, and time; the current server-side state controls processing.','sortOrder',20),
     JSON_OBJECT('key','account-security','title','Account & security','body','You are responsible for protecting your login credentials, verification codes, and devices. Contact support promptly if you detect unauthorized access. We may temporarily restrict activity to protect an account or investigate abuse.','sortOrder',30),
     JSON_OBJECT('key','hardware-operation','title','Devices & operation','body','Product information, price, delivery, return, and activation conditions are shown before you confirm a transaction. Mandatory consumer rights under applicable law are not excluded. Device capability and results may vary with configuration and operating conditions.','sortOrder',40),
     JSON_OBJECT('key','estimates-rewards','title','Estimates & rewards','body','Any forecast or estimate shown in the app is informational and is not a promise of future results. A reward is recorded only after the displayed conditions are met and the server confirms a valid transaction.','sortOrder',50),
     JSON_OBJECT('key','wallet-transactions','title','Wallet & transactions','body','Before confirming, review the displayed destination address, amount, fees, limits, and channel status. Processing time and final outcome follow the server-side order state. A transaction may be held or rejected when a safety review or applicable law requires it.','sortOrder',60),
     JSON_OBJECT('key','referrals','title','Referrals & promotions','body','A referral or promotion applies only while it is enabled and published in the app. Its conditions, period, and actual value follow the displayed rules and server confirmation. Spam, fake accounts, and other program abuse are prohibited.','sortOrder',70),
     JSON_OBJECT('key','prohibited-conduct','title','Prohibited conduct','body','You may not use NexGrid for unlawful activity, fraud, unauthorized access, bypassing safety controls, abusing promotions through multiple accounts, or interfering with the service or other users. Violations may result in restricted or terminated access.','sortOrder',80),
     JSON_OBJECT('key','fees-changes','title','Fees, taxes & changes','body','Fees and the amount you will receive must be shown before you confirm a transaction. You are responsible for tax obligations that apply to you. If a material change affects your rights or obligations, the app will ask you to review and explicitly accept the new version before continuing.','sortOrder',90),
     JSON_OBJECT('key','rights-support','title','Your rights & support','body','Nothing in these terms excludes mandatory rights provided to you by applicable law. For questions, complaints, or disputes, use the official support channel shown in the app so the matter can be recorded and handled.','sortOrder',100)
   ), 1, 'migration:safe-baseline-terms-v6', '2026-09-02 00:00:00', NOW(), NOW(), 0);

-- If an operator already published another reviewed version, keep it
-- authoritative and retire only the untouched baseline row inserted here.
UPDATE nx_legal_terms_version seeded
JOIN (
  SELECT locale, jurisdiction
  FROM nx_legal_terms_version
  WHERE status='PUBLISHED' AND is_deleted=0
    AND COALESCE(last_operator,'') NOT IN ('migration:safe-baseline-terms-v6', 'migration:formal-terms-v5')
    AND NOT (
      locale='en' AND jurisdiction='GLOBAL' AND version_label='v4'
      AND title='Nexion Acceptance Terms seven-closures-20260817 post-fix-v4'
      AND summary='QA acceptance fixture'
    )
  GROUP BY locale, jurisdiction
) operator_terms
  ON operator_terms.locale=seeded.locale
 AND operator_terms.jurisdiction=seeded.jurisdiction
SET seeded.status='SUPERSEDED', seeded.updated_at=NOW()
WHERE seeded.status='PUBLISHED' AND seeded.is_deleted=0
  AND seeded.version_label='v6'
  AND seeded.last_operator='migration:safe-baseline-terms-v6';

-- Retire only exact rows produced by the earlier local v5 repair. Any CMS edit
-- changes its revision or operator and is therefore preserved.
UPDATE nx_legal_terms_version legacy
JOIN (
  SELECT locale, jurisdiction
  FROM nx_legal_terms_version
  WHERE version_label='v6' AND status='PUBLISHED' AND is_deleted=0
  GROUP BY locale, jurisdiction
) safe_terms
  ON safe_terms.locale=legacy.locale
 AND safe_terms.jurisdiction=legacy.jurisdiction
SET legacy.status='SUPERSEDED', legacy.updated_at=NOW(),
    legacy.last_operator='migration:safe-baseline-terms-v6'
WHERE legacy.status='PUBLISHED' AND legacy.is_deleted=0
  AND legacy.version_label='v5' AND legacy.revision=1
  AND legacy.last_operator='migration:formal-terms-v5'
  AND legacy.title IN ('Điều khoản dịch vụ', '服务条款', 'Terms of Service');

-- Retire only the exact historical acceptance fixture after a published
-- English fallback exists. Fuzzy marker matching would risk operator content.
UPDATE nx_legal_terms_version
SET status='SUPERSEDED', updated_at=NOW(), last_operator='migration:safe-baseline-terms-v6'
WHERE status='PUBLISHED' AND is_deleted=0
  AND locale='en' AND jurisdiction='GLOBAL' AND version_label='v4'
  AND title='Nexion Acceptance Terms seven-closures-20260817 post-fix-v4'
  AND summary='QA acceptance fixture'
  AND EXISTS (
    SELECT 1 FROM (
      SELECT id FROM nx_legal_terms_version
      WHERE locale='en' AND jurisdiction='GLOBAL'
        AND status='PUBLISHED' AND is_deleted=0
        AND NOT (version_label='v4'
          AND title='Nexion Acceptance Terms seven-closures-20260817 post-fix-v4'
          AND summary='QA acceptance fixture')
      LIMIT 1
    ) safe_terms
  );

COMMIT;
