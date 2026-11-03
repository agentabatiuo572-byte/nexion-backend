-- Keep operator publications and drafts. Switch the mapped version and its seven chapters as one transaction.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
START TRANSACTION;
DROP TEMPORARY TABLE IF EXISTS handoff_disclosure_v2;
CREATE TEMPORARY TABLE handoff_disclosure_v2 AS
SELECT m.id matrix_id,d.id old_draft_id,m.jurisdiction_code code
  FROM nx_disclosure_jurisdiction m JOIN nx_disclosure_draft d
    ON d.jurisdiction_code=m.jurisdiction_code AND d.version_label=m.version_label
 WHERE m.jurisdiction_code IN ('CN','US','EU','SG','SBV') AND m.version_label='v1'
   AND m.status='PUBLISHED' AND m.is_deleted=0 AND d.status='PUBLISHED' AND d.is_deleted=0
   AND m.last_operator IN ('migration:i5-published-provisioning','migration:g7-i5-vietnam')
   AND d.last_operator IN ('migration:i5-published-provisioning','migration:g7-i5-vietnam')
   AND NOT EXISTS (SELECT 1 FROM nx_disclosure_draft v WHERE v.jurisdiction_code=m.jurisdiction_code AND v.version_label='v2');
INSERT INTO nx_disclosure_draft
  (jurisdiction_code,version_label,language_scope,effective_date,requires_reack,zh_body,vi_body,en_body,
   status,revision,content_hash,last_operator,created_at,updated_at,is_deleted)
SELECT code,'v2','zh+vi+en',DATE_FORMAT(CURRENT_DATE,'%Y-%m-%d'),1,
       '使用设备、钱包和奖励相关功能前，请阅读以下 7 章风险说明。','Trước khi sử dụng các tính năng thiết bị, ví và phần thưởng, vui lòng đọc 7 chương thông tin rủi ro dưới đây.','Before using device, wallet and reward features, read the 7 chapters of risk information below.',
       'DRAFT',1,'','migration:uvel-disclosure-v2',NOW(),NOW(),0 FROM handoff_disclosure_v2;
INSERT INTO nx_disclosure_chapter
  (jurisdiction_code,version_label,chapter_no,zh_title,vi_title,en_title,zh_body,vi_body,en_body,sort_order,created_at,updated_at,is_deleted)
SELECT target.code,'v2',c.chapter_no,c.zh_title,c.vi_title,c.en_title,c.zh_body,c.vi_body,c.en_body,c.sort_order,NOW(),NOW(),0
  FROM handoff_disclosure_v2 target CROSS JOIN (
  SELECT '01' chapter_no, 10 sort_order,
         '收益为预估，不构成承诺' zh_title, 'Thu nhập là ước tính, không phải cam kết' vi_title, 'Earnings are estimates, not promises' en_title,
         'App 内显示的日收益、月收益均为预估，依据当前任务需求、价格和设备状态计算。实际收益以到账记录为准，可能低于预估，某些时段也可能没有收益。过往收益不代表未来结果。' zh_body,
         'Thu nhập ngày và tháng hiển thị trong ứng dụng là số ước tính, dựa trên nhu cầu tác vụ, giá và trạng thái thiết bị hiện tại. Thu nhập thực tế căn cứ theo lịch sử ghi nhận, có thể thấp hơn ước tính và có thể có giai đoạn không phát sinh thu nhập. Thu nhập trong quá khứ không phản ánh kết quả trong tương lai.' vi_body,
         'Daily and monthly earnings shown in the app are estimates based on current task demand, prices and device status. Actual earnings follow your credited records, may be lower than estimated, and there may be periods with no earnings. Past earnings do not indicate future results.' en_body
  UNION ALL
  SELECT '02' chapter_no, 20 sort_order,
         '设备产出会变化' zh_title, 'Sản lượng thiết bị có thể thay đổi' vi_title, 'Device output varies' en_title,
         '任务供给随市场需求变化，设备在线时长、网络、电力和维护都会影响产出。设备产能按平台规则随使用时长逐步下降，收益可能随之减少。手机参与算力时，产出受机型、电量和系统限制。' zh_body,
         'Nguồn tác vụ thay đổi theo nhu cầu thị trường; thời gian trực tuyến, mạng, nguồn điện và bảo trì đều ảnh hưởng đến sản lượng. Công suất thiết bị giảm dần theo thời gian sử dụng theo quy định của nền tảng, thu nhập có thể giảm theo. Khi điện thoại tham gia điện toán, sản lượng bị giới hạn bởi dòng máy, pin và hệ điều hành.' vi_body,
         'Task supply changes with market demand, and online time, network, power and maintenance all affect output. Device capacity declines gradually with use under platform rules, and earnings may decline with it. When a phone contributes compute, its output is limited by the phone model, battery and operating system.' en_body
  UNION ALL
  SELECT '03' chapter_no, 30 sort_order,
         'UVT 价格波动' zh_title, 'Biến động giá UVT' vi_title, 'UVT price volatility' en_title,
         'UVT 是平台奖励代币，价格和流动性可能大幅波动，也可能暂时无法交易。UVT 的获取、释放和使用以平台公布的规则为准。' zh_body,
         'UVT là token thưởng của nền tảng. Giá và thanh khoản của UVT có thể biến động mạnh, và có lúc có thể không giao dịch được. Việc nhận, mở khóa và sử dụng UVT tuân theo quy định do nền tảng công bố.' vi_body,
         'UVT is the platform''s reward token. Its price and liquidity can change sharply, and at times it may not be tradable. How UVT is earned, released and used follows the rules published by the platform.' en_body
  UNION ALL
  SELECT '04' chapter_no, 40 sort_order,
         '购买、退款与活动' zh_title, 'Mua hàng, hoàn tiền và khuyến mãi' vi_title, 'Purchases, refunds and promotions' en_title,
         '设备购买后一般不支持退款，可按升级页规则置换。活动奖励、试用抵扣等以对应活动规则为准；发生退款时，相关奖励可能被收回。下单前请确认价格、费用和售后说明。' zh_body,
         'Thiết bị thường không được hoàn tiền sau khi mua và có thể được đổi lên đời theo quy định tại trang nâng cấp. Phần thưởng khuyến mãi và khoản khấu trừ dùng thử tuân theo quy định của từng chương trình; khi hoàn tiền, phần thưởng liên quan có thể bị thu hồi. Hãy kiểm tra giá, phí và thông tin hậu mãi trước khi đặt hàng.' vi_body,
         'Devices are generally non-refundable after purchase and can be traded in under the rules on the upgrade page. Promotion rewards and trial credits follow the rules of each promotion; if a refund is made, related rewards may be reclaimed. Check the price, fees and after-sales information before ordering.' en_body
  UNION ALL
  SELECT '05' chapter_no, 50 sort_order,
         '充值与提现' zh_title, 'Nạp và rút tiền' vi_title, 'Deposits and withdrawals' en_title,
         '充值和提现前请核对网络和地址，转错网络或地址的资产可能无法找回。链上转账确认后无法撤回，网络手续费和到账时间受网络状况影响，以页面显示为准。提现可能需要安全确认，处理时间以订单状态为准。' zh_body,
         'Hãy kiểm tra mạng lưới và địa chỉ trước khi nạp hoặc rút; tài sản gửi sai mạng hoặc sai địa chỉ có thể không lấy lại được. Giao dịch on-chain không thể hoàn tác sau khi đã xác nhận. Phí mạng và thời gian nhận tiền phụ thuộc vào tình trạng mạng và được hiển thị trên trang. Yêu cầu rút tiền có thể cần xác minh bảo mật, thời gian xử lý căn cứ theo trạng thái đơn.' vi_body,
         'Check the network and address before depositing or withdrawing; assets sent to the wrong network or address may not be recoverable. On-chain transfers cannot be reversed once confirmed. Network fees and arrival times depend on network conditions and are shown on the page. Withdrawals may need a security check, and processing time follows the order status.' en_body
  UNION ALL
  SELECT '06' chapter_no, 60 sort_order,
         '推荐与团队奖励' zh_title, 'Phần thưởng giới thiệu và đội nhóm' vi_title, 'Referral and team rewards' en_title,
         '推荐和团队奖励取决于被邀请用户的有效行为及当期规则，规则可能调整，不保证持续获得。通过虚假账户、批量注册或垃圾推广获得的奖励将作废，并可能导致账户停用。' zh_body,
         'Phần thưởng giới thiệu và đội nhóm phụ thuộc vào hoạt động hợp lệ của người được mời và quy định đang áp dụng. Quy định có thể thay đổi và phần thưởng không được bảo đảm duy trì. Phần thưởng có được từ tài khoản giả, đăng ký hàng loạt hoặc quảng bá rác sẽ bị hủy và có thể dẫn đến việc tạm khóa tài khoản.' vi_body,
         'Referral and team rewards depend on valid activity by invited users and the rules in effect at the time. Rules may change, and rewards are not guaranteed to continue. Rewards obtained through fake accounts, bulk sign-ups or spam are void and may lead to account suspension.' en_body
  UNION ALL
  SELECT '07' chapter_no, 70 sort_order,
         '账户安全与服务变更' zh_title, 'Bảo mật tài khoản và thay đổi dịch vụ' vi_title, 'Account security and service changes' en_title,
         '请妥善保管登录密码、验证码和支付密码，平台工作人员不会向你索要。为保障账户和资金安全或遵守适用法律，平台可能暂停部分功能或交易。服务可能因维护、网络或不可抗力中断，功能和规则调整会在 App 内通知。' zh_body,
         'Hãy bảo mật mật khẩu, mã xác minh và mã PIN thanh toán; nhân viên nền tảng sẽ không bao giờ yêu cầu bạn cung cấp. Để bảo vệ tài khoản, tài sản hoặc tuân thủ pháp luật hiện hành, nền tảng có thể tạm dừng một số tính năng hoặc giao dịch. Dịch vụ có thể bị gián đoạn do bảo trì, sự cố mạng hoặc bất khả kháng; thay đổi về tính năng và quy định sẽ được thông báo trong ứng dụng.' vi_body,
         'Keep your password, verification codes and payment PIN safe; platform staff will never ask for them. To protect accounts and funds, or to comply with applicable law, the platform may pause some features or transactions. The service may be interrupted by maintenance, network problems or force majeure, and changes to features and rules are announced in the app.' en_body
  ) c;
SET SESSION group_concat_max_len = 16777216;
UPDATE nx_disclosure_draft provisioned
JOIN (
  SELECT chapter.jurisdiction_code, chapter.version_label,
    GROUP_CONCAT(CONCAT(
      OCTET_LENGTH(COALESCE(chapter.chapter_no,'')),':',COALESCE(chapter.chapter_no,''),';',
      OCTET_LENGTH(COALESCE(chapter.zh_title,'')),':',COALESCE(chapter.zh_title,''),';',
      OCTET_LENGTH(COALESCE(chapter.vi_title,'')),':',COALESCE(chapter.vi_title,''),';',
      OCTET_LENGTH(COALESCE(chapter.en_title,'')),':',COALESCE(chapter.en_title,''),';',
      OCTET_LENGTH(COALESCE(chapter.zh_body,'')),':',COALESCE(chapter.zh_body,''),';',
      OCTET_LENGTH(COALESCE(chapter.vi_body,'')),':',COALESCE(chapter.vi_body,''),';',
      OCTET_LENGTH(COALESCE(chapter.en_body,'')),':',COALESCE(chapter.en_body,''),';'
    ) ORDER BY chapter.sort_order, chapter.id SEPARATOR '') chapter_canonical
  FROM nx_disclosure_chapter chapter
  JOIN handoff_disclosure_v2 target ON target.code=chapter.jurisdiction_code
  WHERE chapter.is_deleted=0 AND chapter.version_label='v2'
  GROUP BY chapter.jurisdiction_code, chapter.version_label
) canonical
  ON canonical.jurisdiction_code = provisioned.jurisdiction_code
 AND canonical.version_label = provisioned.version_label
SET provisioned.content_hash = LOWER(SHA2(CONCAT(
  OCTET_LENGTH(COALESCE(provisioned.version_label,'')),':',COALESCE(provisioned.version_label,''),';',
  OCTET_LENGTH(COALESCE(provisioned.jurisdiction_code,'')),':',COALESCE(provisioned.jurisdiction_code,''),';',
  OCTET_LENGTH(COALESCE(provisioned.language_scope,'')),':',COALESCE(provisioned.language_scope,''),';',
  OCTET_LENGTH(COALESCE(provisioned.effective_date,'')),':',COALESCE(provisioned.effective_date,''),';',
  OCTET_LENGTH(IF(provisioned.requires_reack=1,'true','false')),':',IF(provisioned.requires_reack=1,'true','false'),';',
  OCTET_LENGTH(COALESCE(provisioned.zh_body,'')),':',COALESCE(provisioned.zh_body,''),';',
  OCTET_LENGTH(COALESCE(provisioned.vi_body,'')),':',COALESCE(provisioned.vi_body,''),';',
  OCTET_LENGTH(COALESCE(provisioned.en_body,'')),':',COALESCE(provisioned.en_body,''),';',
  canonical.chapter_canonical
), 256)), provisioned.updated_at = NOW()
WHERE provisioned.is_deleted=0 AND provisioned.version_label='v2'
  AND provisioned.status='DRAFT' AND provisioned.last_operator='migration:uvel-disclosure-v2';
UPDATE nx_disclosure_draft old JOIN handoff_disclosure_v2 target ON target.old_draft_id=old.id
   SET old.status='SUPERSEDED',old.updated_at=NOW();
UPDATE nx_disclosure_draft d JOIN handoff_disclosure_v2 target ON target.code=d.jurisdiction_code
   SET d.status='PUBLISHED',d.updated_at=NOW()
 WHERE d.version_label='v2' AND d.last_operator='migration:uvel-disclosure-v2';
UPDATE nx_disclosure_jurisdiction m JOIN handoff_disclosure_v2 target ON m.id=target.matrix_id
   SET m.version_label='v2',m.published_at_label=DATE_FORMAT(CURRENT_DATE,'%m-%d'),m.ack_progress_pct=0,
       m.blocked_count=0,m.last_operator='migration:uvel-disclosure-v2',m.updated_at=NOW();
DROP TEMPORARY TABLE handoff_disclosure_v2;
COMMIT;
