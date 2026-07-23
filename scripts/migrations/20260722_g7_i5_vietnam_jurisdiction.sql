-- G7/I5 closure: the primary Vietnam user flow must have a published disclosure.
-- `disclosure.gate.staking=true` is a per-user acknowledgment requirement; without
-- an SBV -> VN matrix row, every Vietnam staking/repurchase command fails forever.

INSERT INTO nx_disclosure_jurisdiction_catalog
  (jurisdiction_code,jurisdiction_name,status,revision,last_operator,created_at,updated_at,is_deleted)
VALUES
  ('SBV','越南国家银行','ACTIVE',1,'migration:g7-i5-vietnam',NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  jurisdiction_name=VALUES(jurisdiction_name),status='ACTIVE',revision=GREATEST(revision,1),
  last_operator=VALUES(last_operator),updated_at=NOW(),is_deleted=0;

INSERT INTO nx_disclosure_jurisdiction
  (jurisdiction_code,jurisdiction_name,country_codes,version_label,status,published_at_label,
   affected_count,ack_progress_pct,blocked_count,last_operator,created_at,updated_at,is_deleted)
SELECT 'SBV','越南国家银行','VN','v1','PUBLISHED','07-22',
       COUNT(*),0,0,'migration:g7-i5-vietnam',NOW(),NOW(),0
  FROM nx_user
 WHERE is_deleted=0 AND UPPER(TRIM(country_code)) IN ('VN','84','+84','VIETNAM','VIỆT NAM','越南')
ON DUPLICATE KEY UPDATE
  jurisdiction_name=VALUES(jurisdiction_name),country_codes='VN',version_label='v1',status='PUBLISHED',
  published_at_label='07-22',affected_count=VALUES(affected_count),last_operator=VALUES(last_operator),
  updated_at=NOW(),is_deleted=0;

-- The v1 row is this migration's canonical slot. Clear any stale competing
-- published slot first so the unique published-slot constraint cannot redirect
-- the upsert to another version.
UPDATE nx_disclosure_draft
   SET status='SUPERSEDED',updated_at=NOW()
 WHERE jurisdiction_code='SBV' AND version_label<>'v1'
   AND status='PUBLISHED' AND is_deleted=0;

INSERT INTO nx_disclosure_draft
  (jurisdiction_code,version_label,language_scope,effective_date,requires_reack,
   zh_body,vi_body,en_body,status,revision,content_hash,last_operator,created_at,updated_at,is_deleted)
VALUES
  ('SBV','v1','zh+vi+en','2026-07-22',1,
   '在继续使用锁仓及其他资金功能前，请阅读并确认本版本全部七章风险披露。',
   'Trước khi tiếp tục sử dụng tính năng staking và các tính năng tài chính khác, vui lòng đọc và xác nhận đầy đủ bảy chương công bố rủi ro của phiên bản này.',
   'Before using staking or other financial features, read and acknowledge all seven chapters of this risk disclosure.',
   'PUBLISHED',1,'','migration:g7-i5-vietnam',NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  language_scope='zh+vi+en',effective_date='2026-07-22',requires_reack=1,
  zh_body=VALUES(zh_body),vi_body=VALUES(vi_body),en_body=VALUES(en_body),status='PUBLISHED',
  revision=GREATEST(revision,1),content_hash='',last_operator=VALUES(last_operator),updated_at=NOW(),is_deleted=0;

UPDATE nx_disclosure_chapter
   SET is_deleted=1,last_operator='migration:g7-i5-vietnam',updated_at=NOW()
 WHERE jurisdiction_code='SBV' AND version_label='v1'
   AND chapter_no NOT IN ('01','02','03','04','05','06','07') AND is_deleted=0;

INSERT INTO nx_disclosure_chapter
  (jurisdiction_code,version_label,chapter_no,zh_title,vi_title,en_title,
   zh_body,vi_body,en_body,sort_order,last_operator,created_at,updated_at,is_deleted)
VALUES
  ('SBV','v1','01','收益预估不构成承诺','Ước tính thu nhập không phải là cam kết','Earnings estimates are not guarantees',
   '展示的收益仅为估算，实际结果可能因市场、设备和运营条件而变化。',
   'Thu nhập hiển thị chỉ là ước tính; kết quả thực tế có thể thay đổi theo thị trường, thiết bị và điều kiện vận hành.',
   'Displayed earnings are estimates; actual results may vary with market, device, and operating conditions.',
   1,'migration:g7-i5-vietnam',NOW(),NOW(),0),
  ('SBV','v1','02','硬件衰减与产量波动','Suy giảm phần cứng và biến động sản lượng','Hardware decay and output variance',
   '设备性能会随时间衰减，维护、网络和任务供给也会造成产量波动。',
   'Hiệu năng thiết bị suy giảm theo thời gian; bảo trì, mạng và nguồn nhiệm vụ cũng có thể làm sản lượng biến động.',
   'Device performance decays over time; maintenance, network, and task supply can also change output.',
   2,'migration:g7-i5-vietnam',NOW(),NOW(),0),
  ('SBV','v1','03','NEX 市场风险','Rủi ro thị trường NEX','NEX market risk',
   'NEX 的价格和流动性可能剧烈变化，历史表现不代表未来结果。',
   'Giá và thanh khoản của NEX có thể biến động mạnh; kết quả trong quá khứ không đảm bảo cho tương lai.',
   'NEX price and liquidity can change materially; past performance does not guarantee future results.',
   3,'migration:g7-i5-vietnam',NOW(),NOW(),0),
  ('SBV','v1','04','提现窗口与合规审查','Khung thời gian rút tiền và kiểm tra tuân thủ','Withdrawal windows and compliance review',
   '提现可能受处理窗口、额度、身份核验及合规审查影响。',
   'Việc rút tiền có thể chịu ảnh hưởng của khung xử lý, hạn mức, xác minh danh tính và kiểm tra tuân thủ.',
   'Withdrawals may be affected by processing windows, limits, identity verification, and compliance review.',
   4,'migration:g7-i5-vietnam',NOW(),NOW(),0),
  ('SBV','v1','05','锁仓不可撤销','Staking không thể hủy ngang','Staking is irrevocable',
   '锁定期内的资金通常不能提前撤销；如产品允许提前赎回，可能产生罚金并放弃相应权益。',
   'Khoản tiền trong thời gian khóa thường không thể hủy sớm; nếu sản phẩm cho phép rút sớm, có thể phát sinh phí phạt và mất các quyền lợi liên quan.',
   'Funds are generally irreversible during the lock period; where early withdrawal is allowed, penalties and forfeited benefits may apply.',
   5,'migration:g7-i5-vietnam',NOW(),NOW(),0),
  ('SBV','v1','06','网络经济与推荐激励','Kinh tế mạng lưới và phần thưởng giới thiệu','Network economy and referral incentives',
   '推荐激励取决于真实有效的活动和当期规则，不保证固定或持续收益。',
   'Phần thưởng giới thiệu phụ thuộc vào hoạt động hợp lệ và quy tắc hiện hành, không bảo đảm thu nhập cố định hoặc liên tục.',
   'Referral incentives depend on valid activity and current rules and do not guarantee fixed or continuing income.',
   6,'migration:g7-i5-vietnam',NOW(),NOW(),0),
  ('SBV','v1','07','托管、KYC 与监管管辖','Lưu ký, KYC và thẩm quyền quản lý','Custody, KYC, and regulatory jurisdiction',
   '账户可能适用托管、KYC、制裁筛查及所在地监管要求。',
   'Tài khoản có thể chịu yêu cầu về lưu ký, KYC, sàng lọc cấm vận và quy định tại khu vực áp dụng.',
   'Accounts may be subject to custody, KYC, sanctions screening, and applicable local regulation.',
   7,'migration:g7-i5-vietnam',NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  zh_title=VALUES(zh_title),vi_title=VALUES(vi_title),en_title=VALUES(en_title),
  zh_body=VALUES(zh_body),vi_body=VALUES(vi_body),en_body=VALUES(en_body),
  sort_order=VALUES(sort_order),last_operator=VALUES(last_operator),updated_at=NOW(),is_deleted=0;

-- Match DisclosureContentHash's length-prefixed canonical SHA-256.
SET SESSION group_concat_max_len = 16777216;
UPDATE nx_disclosure_draft d
JOIN (
  SELECT jurisdiction_code,version_label,
    GROUP_CONCAT(CONCAT(
      OCTET_LENGTH(COALESCE(chapter_no,'')),':',COALESCE(chapter_no,''),';',
      OCTET_LENGTH(COALESCE(zh_title,'')),':',COALESCE(zh_title,''),';',
      OCTET_LENGTH(COALESCE(vi_title,'')),':',COALESCE(vi_title,''),';',
      OCTET_LENGTH(COALESCE(en_title,'')),':',COALESCE(en_title,''),';',
      OCTET_LENGTH(COALESCE(zh_body,'')),':',COALESCE(zh_body,''),';',
      OCTET_LENGTH(COALESCE(vi_body,'')),':',COALESCE(vi_body,''),';',
      OCTET_LENGTH(COALESCE(en_body,'')),':',COALESCE(en_body,''),';'
    ) ORDER BY sort_order,id SEPARATOR '') chapter_canonical
  FROM nx_disclosure_chapter
  WHERE jurisdiction_code='SBV' AND version_label='v1' AND is_deleted=0
  GROUP BY jurisdiction_code,version_label
) c ON c.jurisdiction_code=d.jurisdiction_code AND c.version_label=d.version_label
SET d.content_hash=LOWER(SHA2(CONCAT(
  OCTET_LENGTH(COALESCE(d.version_label,'')),':',COALESCE(d.version_label,''),';',
  OCTET_LENGTH(COALESCE(d.jurisdiction_code,'')),':',COALESCE(d.jurisdiction_code,''),';',
  OCTET_LENGTH(COALESCE(d.language_scope,'')),':',COALESCE(d.language_scope,''),';',
  OCTET_LENGTH(COALESCE(d.effective_date,'')),':',COALESCE(d.effective_date,''),';',
  OCTET_LENGTH(IF(d.requires_reack=1,'true','false')),':',IF(d.requires_reack=1,'true','false'),';',
  OCTET_LENGTH(COALESCE(d.zh_body,'')),':',COALESCE(d.zh_body,''),';',
  OCTET_LENGTH(COALESCE(d.vi_body,'')),':',COALESCE(d.vi_body,''),';',
  OCTET_LENGTH(COALESCE(d.en_body,'')),':',COALESCE(d.en_body,''),';',
  c.chapter_canonical
),256)),d.updated_at=NOW()
WHERE d.jurisdiction_code='SBV' AND d.version_label='v1' AND d.is_deleted=0;
