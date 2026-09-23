-- I5 published-disclosure provisioning. Rerunnable; never overwrites operator content.
--
-- Root cause it repairs: a published matrix row in nx_disclosure_jurisdiction may
-- reference a version_label that has no nx_disclosure_draft row at all. Read-time
-- seeding was deliberately retired (commit 98b57f1), and the only canonical
-- provisioning migration (20260722_g7_i5_vietnam_jurisdiction.sql) is not part of
-- the startup chain, so an upgraded/rebuilt database keeps the mappings while the
-- versions they point at are gone. AppRiskDisclosureService.loadCurrent then fails
-- with RISK_DISCLOSURE_PUBLISHED_VERSION_NOT_FOUND (404) and the App risk-disclosure
-- page can never render a body no matter how often it retries.
--
-- Scope: the canonical jurisdictions the App must resolve. The seven-chapter text
-- is the platform-standard risk disclosure already reviewed in this repository
-- (same chapters as 20260722_g7_i5_vietnam_jurisdiction.sql); jurisdiction-specific
-- legal wording remains an operator/compliance authoring task in PC I5.
SET NAMES utf8mb4;

-- 1. Jurisdiction catalog. Insert-only: archived/renamed rows stay untouched.
INSERT INTO nx_disclosure_jurisdiction_catalog
  (jurisdiction_code, jurisdiction_name, status, revision, last_operator, created_at, updated_at, is_deleted)
SELECT seed.code, seed.name, 'ACTIVE', 1, 'migration:i5-published-provisioning', NOW(), NOW(), 0
FROM (
            SELECT 'CN'  code, '中国大陆'     name
  UNION ALL SELECT 'US',       '美国'
  UNION ALL SELECT 'EU',       '欧盟'
  UNION ALL SELECT 'SG',       '新加坡'
  UNION ALL SELECT 'SBV',      '越南国家银行'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM nx_disclosure_jurisdiction_catalog existing
  WHERE existing.jurisdiction_code = seed.code
);

-- 2. Published disclosure version. Insert-only: a jurisdiction that already owns a
--    non-deleted draft (published or not) is left entirely to its operator.
INSERT INTO nx_disclosure_draft
  (jurisdiction_code, version_label, language_scope, effective_date, requires_reack,
   zh_body, vi_body, en_body, status, revision, content_hash, last_operator, created_at, updated_at, is_deleted)
SELECT seed.code, 'v1', 'zh+vi+en', DATE_FORMAT(CURRENT_DATE, '%Y-%m-%d'), 1,
       '在继续使用锁仓及其他资金功能前，请阅读并确认本版本全部七章风险披露。',
       'Trước khi tiếp tục sử dụng tính năng staking và các tính năng tài chính khác, vui lòng đọc và xác nhận đầy đủ bảy chương công bố rủi ro của phiên bản này.',
       'Before using staking or other financial features, read and acknowledge all seven chapters of this risk disclosure.',
       'PUBLISHED', 1, '', 'migration:i5-published-provisioning', NOW(), NOW(), 0
FROM (
            SELECT 'CN'  code
  UNION ALL SELECT 'US'
  UNION ALL SELECT 'EU'
  UNION ALL SELECT 'SG'
  UNION ALL SELECT 'SBV'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM nx_disclosure_draft existing
  WHERE existing.jurisdiction_code = seed.code AND existing.is_deleted = 0
);

-- 3. The seven mandatory chapters for every provisioned v1 version.
INSERT INTO nx_disclosure_chapter
  (jurisdiction_code, version_label, chapter_no, zh_title, vi_title, en_title,
   zh_body, vi_body, en_body, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT seed.code, 'v1', chapter.chapter_no,
       chapter.zh_title, chapter.vi_title, chapter.en_title,
       chapter.zh_body, chapter.vi_body, chapter.en_body,
       chapter.sort_order, 'migration:i5-published-provisioning', NOW(), NOW(), 0
FROM (
            SELECT 'CN'  code
  UNION ALL SELECT 'US'
  UNION ALL SELECT 'EU'
  UNION ALL SELECT 'SG'
  UNION ALL SELECT 'SBV'
) seed
JOIN (
            SELECT '01' chapter_no, '收益预估不构成承诺' zh_title,
                   'Ước tính thu nhập không phải là cam kết' vi_title,
                   'Earnings estimates are not guarantees' en_title,
                   '展示的收益仅为估算，实际结果可能因市场、设备和运营条件而变化。' zh_body,
                   'Thu nhập hiển thị chỉ là ước tính; kết quả thực tế có thể thay đổi theo thị trường, thiết bị và điều kiện vận hành.' vi_body,
                   'Displayed earnings are estimates; actual results may vary with market, device, and operating conditions.' en_body,
                   1 sort_order
  UNION ALL SELECT '02', '硬件衰减与产量波动', 'Suy giảm phần cứng và biến động sản lượng', 'Hardware decay and output variance',
                   '设备性能会随时间衰减，维护、网络和任务供给也会造成产量波动。',
                   'Hiệu năng thiết bị suy giảm theo thời gian; bảo trì, mạng và nguồn nhiệm vụ cũng có thể làm sản lượng biến động.',
                   'Device performance decays over time; maintenance, network, and task supply can also change output.',
                   2
  UNION ALL SELECT '03', 'NEX 市场风险', 'Rủi ro thị trường NEX', 'NEX market risk',
                   'NEX 的价格和流动性可能剧烈变化，历史表现不代表未来结果。',
                   'Giá và thanh khoản của NEX có thể biến động mạnh; kết quả trong quá khứ không đảm bảo cho tương lai.',
                   'NEX price and liquidity can change materially; past performance does not guarantee future results.',
                   3
  UNION ALL SELECT '04', '提现窗口与合规审查', 'Khung thời gian rút tiền và kiểm tra tuân thủ', 'Withdrawal windows and compliance review',
                   '提现可能受处理窗口、额度、身份核验及合规审查影响。',
                   'Việc rút tiền có thể chịu ảnh hưởng của khung xử lý, hạn mức, xác minh danh tính và kiểm tra tuân thủ.',
                   'Withdrawals may be affected by processing windows, limits, identity verification, and compliance review.',
                   4
  UNION ALL SELECT '05', '锁仓不可撤销', 'Staking không thể hủy ngang', 'Staking is irrevocable',
                   '锁定期内的资金通常不能提前撤销；如产品允许提前赎回，可能产生罚金并放弃相应权益。',
                   'Khoản tiền trong thời gian khóa thường không thể hủy sớm; nếu sản phẩm cho phép rút sớm, có thể phát sinh phí phạt và mất các quyền lợi liên quan.',
                   'Funds are generally irreversible during the lock period; where early withdrawal is allowed, penalties and forfeited benefits may apply.',
                   5
  UNION ALL SELECT '06', '网络经济与推荐激励', 'Kinh tế mạng lưới và phần thưởng giới thiệu', 'Network economy and referral incentives',
                   '推荐激励取决于真实有效的活动和当期规则，不保证固定或持续收益。',
                   'Phần thưởng giới thiệu phụ thuộc vào hoạt động hợp lệ và quy tắc hiện hành, không bảo đảm thu nhập cố định hoặc liên tục.',
                   'Referral incentives depend on valid activity and current rules and do not guarantee fixed or continuing income.',
                   6
  UNION ALL SELECT '07', '托管、KYC 与监管管辖', 'Lưu ký, KYC và thẩm quyền quản lý', 'Custody, KYC, and regulatory jurisdiction',
                   '账户可能适用托管、KYC、制裁筛查及所在地监管要求。',
                   'Tài khoản có thể chịu yêu cầu về lưu ký, KYC, sàng lọc cấm vận và quy định tại khu vực áp dụng.',
                   'Accounts may be subject to custody, KYC, sanctions screening, and applicable local regulation.',
                   7
) chapter
WHERE EXISTS (
  SELECT 1 FROM nx_disclosure_draft provisioned
  WHERE provisioned.jurisdiction_code = seed.code
    AND provisioned.version_label = 'v1'
    AND provisioned.is_deleted = 0
    AND provisioned.last_operator = 'migration:i5-published-provisioning'
)
AND NOT EXISTS (
  SELECT 1 FROM nx_disclosure_chapter existing
  WHERE existing.jurisdiction_code = seed.code
    AND existing.version_label = 'v1'
    AND existing.chapter_no = chapter.chapter_no
    AND existing.is_deleted = 0
);

-- 4. Repair broken mappings: a published row whose referenced version has no
--    non-deleted draft cannot be served. Only such rows are rewritten; rows that
--    already point at a readable version keep their operator-chosen version.
UPDATE nx_disclosure_jurisdiction mapping
SET mapping.jurisdiction_name = COALESCE(NULLIF(mapping.jurisdiction_name, ''), (
      SELECT provisioned_name.name FROM (
                SELECT 'CN' code, '中国大陆' name
        UNION ALL SELECT 'US', '美国'
        UNION ALL SELECT 'EU', '欧盟'
        UNION ALL SELECT 'SG', '新加坡'
        UNION ALL SELECT 'SBV', '越南国家银行'
      ) provisioned_name WHERE provisioned_name.code = mapping.jurisdiction_code
    )),
    mapping.version_label = 'v1',
    mapping.status = 'PUBLISHED',
    mapping.published_at_label = DATE_FORMAT(CURRENT_DATE, '%m-%d'),
    mapping.last_operator = 'migration:i5-published-provisioning',
    mapping.updated_at = NOW()
WHERE mapping.is_deleted = 0
  AND mapping.jurisdiction_code IN ('CN', 'US', 'EU', 'SG', 'SBV')
  AND NOT EXISTS (
    SELECT 1 FROM nx_disclosure_draft readable
    WHERE readable.jurisdiction_code = mapping.jurisdiction_code
      AND readable.version_label = mapping.version_label
      AND readable.is_deleted = 0
      AND readable.status IN ('PUBLISHED', 'SUPERSEDED')
  );

-- 5. Country routing. Only fills a mapping the App could otherwise never resolve.
UPDATE nx_disclosure_jurisdiction mapping
SET mapping.country_codes = CASE mapping.jurisdiction_code
      WHEN 'CN' THEN 'CN'
      WHEN 'US' THEN 'US'
      WHEN 'EU' THEN 'EU'
      WHEN 'SG' THEN 'SG'
      WHEN 'SBV' THEN 'VN'
      ELSE mapping.country_codes
    END,
    mapping.updated_at = NOW()
WHERE mapping.is_deleted = 0
  AND mapping.jurisdiction_code IN ('CN', 'US', 'EU', 'SG', 'SBV')
  AND TRIM(COALESCE(mapping.country_codes, '')) = '';

-- 6. Publish the mapping rows themselves when the database has none.
INSERT INTO nx_disclosure_jurisdiction
  (jurisdiction_code, jurisdiction_name, country_codes, version_label, status, published_at_label,
   affected_count, ack_progress_pct, blocked_count, last_operator, created_at, updated_at, is_deleted)
SELECT seed.code, seed.name, seed.country, 'v1', 'PUBLISHED', DATE_FORMAT(CURRENT_DATE, '%m-%d'),
       0, 0, 0, 'migration:i5-published-provisioning', NOW(), NOW(), 0
FROM (
            SELECT 'CN'  code, '中国大陆'     name, 'CN' country
  UNION ALL SELECT 'US',       '美国',          'US'
  UNION ALL SELECT 'EU',       '欧盟',          'EU'
  UNION ALL SELECT 'SG',       '新加坡',        'SG'
  UNION ALL SELECT 'SBV',      '越南国家银行',  'VN'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM nx_disclosure_jurisdiction existing
  WHERE existing.jurisdiction_code = seed.code
);

-- 7. content_hash must equal DisclosureContentHash's length-prefixed canonical SHA-256,
--    otherwise publishing/immutability guards reject the provisioned snapshot.
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
  WHERE chapter.is_deleted = 0
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
WHERE provisioned.is_deleted = 0
  AND provisioned.last_operator = 'migration:i5-published-provisioning'
  AND CHAR_LENGTH(COALESCE(provisioned.content_hash, '')) <> 64;
