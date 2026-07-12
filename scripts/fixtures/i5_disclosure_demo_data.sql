-- Local/demo only. Provides complete I5 snapshots for the seeded jurisdiction matrix.
-- Do not use these example words as production legal advice.
USE nexion;

INSERT INTO nx_disclosure_draft
  (jurisdiction_code, version_label, language_scope, effective_date, requires_reack,
   zh_body, vi_body, en_body, status, revision, content_hash, last_operator,
   created_at, updated_at, is_deleted)
SELECT j.jurisdiction_code, j.version_label, 'zh+vi+en',
       DATE_FORMAT(CURRENT_DATE, '%Y-%m-%d'), 1,
       '【本地演示数据】请在继续使用资金功能前阅读并确认本版本全部七章风险披露。',
       '【Dữ liệu demo cục bộ】Vui lòng đọc và xác nhận đủ bảy chương công bố rủi ro trước khi tiếp tục sử dụng các tính năng tài chính.',
       '[Local demo data] Read and acknowledge all seven risk-disclosure chapters before continuing to use financial features.',
       IF(UPPER(j.status) = 'PUBLISHED', 'PUBLISHED', 'DRAFT'), 1, '',
       'fixture:i5-demo', NOW(), NOW(), 0
FROM nx_disclosure_jurisdiction j
WHERE j.is_deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM nx_disclosure_draft d
    WHERE d.jurisdiction_code = j.jurisdiction_code
      AND d.version_label = j.version_label
  );

-- Repair only the legacy local placeholder row that had no chapters.
UPDATE nx_disclosure_draft d
SET d.language_scope = 'zh+vi+en',
    d.requires_reack = 1,
    d.zh_body = '【本地演示数据】请在继续使用资金功能前阅读并确认本版本全部七章风险披露。',
    d.vi_body = '【Dữ liệu demo cục bộ】Vui lòng đọc và xác nhận đủ bảy chương công bố rủi ro trước khi tiếp tục sử dụng các tính năng tài chính.',
    d.en_body = '[Local demo data] Read and acknowledge all seven risk-disclosure chapters before continuing to use financial features.',
    d.last_operator = 'fixture:i5-demo',
    d.updated_at = NOW(),
    d.content_hash = ''
WHERE d.is_deleted = 0
  AND d.zh_body LIKE '%ack body%'
  AND NOT EXISTS (
    SELECT 1 FROM nx_disclosure_chapter c
    WHERE c.jurisdiction_code = d.jurisdiction_code
      AND c.version_label = d.version_label
      AND c.is_deleted = 0
  );

INSERT INTO nx_disclosure_chapter
  (jurisdiction_code, version_label, chapter_no,
   zh_title, vi_title, en_title, zh_body, vi_body, en_body,
   sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT j.jurisdiction_code, j.version_label, chapter.chapter_no,
       chapter.zh_title, chapter.vi_title, chapter.en_title,
       chapter.zh_body, chapter.vi_body, chapter.en_body,
       chapter.sort_order, 'fixture:i5-demo', NOW(), NOW(), 0
FROM nx_disclosure_jurisdiction j
JOIN (
  SELECT '01' chapter_no, '收益预估不构成承诺' zh_title,
         'Ước tính thu nhập không phải là cam kết' vi_title,
         'Earnings estimates are not guarantees' en_title,
         '展示的收益仅为估算，实际结果可能因市场、设备和运营条件而变化。' zh_body,
         'Thu nhập hiển thị chỉ là ước tính; kết quả thực tế có thể thay đổi theo thị trường, thiết bị và điều kiện vận hành.' vi_body,
         'Displayed earnings are estimates; actual results may vary with market, device, and operating conditions.' en_body, 1 sort_order
  UNION ALL SELECT '02', '硬件衰减与产量波动', 'Suy giảm phần cứng và biến động sản lượng', 'Hardware decay and output variance',
         '设备性能会随时间衰减，维护、网络和任务供给也会造成产量波动。',
         'Hiệu năng thiết bị suy giảm theo thời gian; bảo trì, mạng và nguồn nhiệm vụ cũng có thể làm sản lượng biến động.',
         'Device performance decays over time; maintenance, network, and task supply can also change output.', 2
  UNION ALL SELECT '03', 'NEX 市场风险', 'Rủi ro thị trường NEX', 'NEX market risk',
         'NEX 的价格和流动性可能剧烈变化，历史表现不代表未来结果。',
         'Giá và thanh khoản của NEX có thể biến động mạnh; kết quả trong quá khứ không đảm bảo cho tương lai.',
         'NEX price and liquidity can change materially; past performance does not guarantee future results.', 3
  UNION ALL SELECT '04', '提现窗口与合规审查', 'Khung thời gian rút tiền và kiểm tra tuân thủ', 'Withdrawal windows and compliance review',
         '提现可能受处理窗口、额度、身份核验及合规审查影响。',
         'Việc rút tiền có thể chịu ảnh hưởng của khung xử lý, hạn mức, xác minh danh tính và kiểm tra tuân thủ.',
         'Withdrawals may be affected by processing windows, limits, identity verification, and compliance review.', 4
  UNION ALL SELECT '05', '质押不可撤销', 'Staking không thể hủy ngang', 'Staking is irrevocable',
         '锁定期内的质押通常不能提前撤销，请在提交前确认期限和资金安排。',
         'Staking trong thời gian khóa thường không thể hủy sớm; hãy xác nhận kỳ hạn và kế hoạch vốn trước khi gửi.',
         'Staking is generally irreversible during the lock period; confirm the term and liquidity plan before submitting.', 5
  UNION ALL SELECT '06', '网络经济与推荐激励', 'Kinh tế mạng lưới và phần thưởng giới thiệu', 'Network economy and referral incentives',
         '推荐激励取决于真实有效的活动和当期规则，不保证固定或持续收益。',
         'Phần thưởng giới thiệu phụ thuộc vào hoạt động hợp lệ và quy tắc hiện hành, không bảo đảm thu nhập cố định hoặc liên tục.',
         'Referral incentives depend on valid activity and current rules and do not guarantee fixed or continuing income.', 6
  UNION ALL SELECT '07', '托管、KYC 与监管管辖', 'Lưu ký, KYC và thẩm quyền quản lý', 'Custody, KYC, and regulatory jurisdiction',
         '账户可能适用托管、KYC、制裁筛查及所在地监管要求。',
         'Tài khoản có thể chịu yêu cầu về lưu ký, KYC, sàng lọc cấm vận và quy định tại khu vực áp dụng.',
         'Accounts may be subject to custody, KYC, sanctions screening, and applicable local regulation.', 7
) chapter
WHERE j.is_deleted = 0
  AND EXISTS (
    SELECT 1 FROM nx_disclosure_draft d
    WHERE d.jurisdiction_code = j.jurisdiction_code
      AND d.version_label = j.version_label
      AND d.is_deleted = 0
  )
  AND NOT EXISTS (
    SELECT 1 FROM nx_disclosure_chapter existing
    WHERE existing.jurisdiction_code = j.jurisdiction_code
      AND existing.version_label = j.version_label
      AND existing.chapter_no = chapter.chapter_no
  );

-- Recompute the same canonical SHA-256 used by DisclosureContentHash.
SET SESSION group_concat_max_len = 16777216;
UPDATE nx_disclosure_draft d
LEFT JOIN (
  SELECT jurisdiction_code, version_label,
    GROUP_CONCAT(CONCAT(
      OCTET_LENGTH(COALESCE(chapter_no, '')), ':', COALESCE(chapter_no, ''), ';',
      OCTET_LENGTH(COALESCE(zh_title, '')), ':', COALESCE(zh_title, ''), ';',
      OCTET_LENGTH(COALESCE(vi_title, '')), ':', COALESCE(vi_title, ''), ';',
      OCTET_LENGTH(COALESCE(en_title, '')), ':', COALESCE(en_title, ''), ';',
      OCTET_LENGTH(COALESCE(zh_body, '')), ':', COALESCE(zh_body, ''), ';',
      OCTET_LENGTH(COALESCE(vi_body, '')), ':', COALESCE(vi_body, ''), ';',
      OCTET_LENGTH(COALESCE(en_body, '')), ':', COALESCE(en_body, ''), ';'
    ) ORDER BY sort_order, id SEPARATOR '') AS chapter_canonical
  FROM nx_disclosure_chapter
  WHERE is_deleted = 0
  GROUP BY jurisdiction_code, version_label
) c ON c.jurisdiction_code = d.jurisdiction_code AND c.version_label = d.version_label
SET d.content_hash = LOWER(SHA2(CONCAT(
  OCTET_LENGTH(COALESCE(d.version_label, '')), ':', COALESCE(d.version_label, ''), ';',
  OCTET_LENGTH(COALESCE(d.jurisdiction_code, '')), ':', COALESCE(d.jurisdiction_code, ''), ';',
  OCTET_LENGTH(COALESCE(d.language_scope, '')), ':', COALESCE(d.language_scope, ''), ';',
  OCTET_LENGTH(COALESCE(d.effective_date, '')), ':', COALESCE(d.effective_date, ''), ';',
  OCTET_LENGTH(IF(d.requires_reack = 1, 'true', 'false')), ':', IF(d.requires_reack = 1, 'true', 'false'), ';',
  OCTET_LENGTH(COALESCE(d.zh_body, '')), ':', COALESCE(d.zh_body, ''), ';',
  OCTET_LENGTH(COALESCE(d.vi_body, '')), ':', COALESCE(d.vi_body, ''), ';',
  OCTET_LENGTH(COALESCE(d.en_body, '')), ':', COALESCE(d.en_body, ''), ';',
  COALESCE(c.chapter_canonical, '')
), 256))
WHERE d.is_deleted = 0 AND (d.content_hash IS NULL OR d.content_hash = '');
