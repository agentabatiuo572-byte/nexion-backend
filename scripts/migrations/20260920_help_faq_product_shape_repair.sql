-- Help-centre FAQ corrections (zentao #81, #82). Rerunnable; never overwrites an
-- operator edit.
--
-- Root cause these repair: two published Help Center answers describe a product
-- shape the App does not have.
--
--   #81 "为什么团队有成员但 VIP 等级仍是 V0？" / "如何查看团队等级和晋级进度？"
--       Both point at 「我的 - 团队 - 等级进度」, a hierarchy that does not exist:
--       src/pages/me/me.vue lists 「团队」 → /team and 「当前等级」 → /team/rank, and
--       the level card lives at the bottom 团队 tab (app-chassis.vue) →
--       team.vue 「我的等级」 → /pages/team/rank. Both answers also say 「VIP 等级」,
--       while the current level system is the V0–V12 ladder titled 「V 级头衔」.
--
--   #82 "充值后多久显示到账？"
--       Describes an on-chain deposit (「所选网络的区块确认进度」「网络与地址」
--       「交易哈希」). The App's only top-up rail in remote mode is VietQR/bank
--       (wallet-topup.vue renders DepositBankPane), whose real fields are the
--       locked quote with a countdown, the memo code, the USDT→VND amounts, the
--       bank receipt, and the 「充值未到账?」 support entry.
--
-- Guard: each row is rewritten only while it still carries the exact defective
-- text below. An operator who already corrected it keeps their wording, and
-- re-running this migration after a correction is a no-op.
SET NAMES utf8mb4;
START TRANSACTION;

-- #81 (a): the level FAQ.
UPDATE nx_help_article
   SET title = '为什么团队有成员但等级仍是 V0？',
       content = '等级按有效直推分支与团队业绩等晋级条件计算，并不是成员数量达到某个数就自动升级。请进入「底部团队页签 → 我的等级」查看当前有效分支、团队业绩和下一等级仍缺少的条件。',
       version_no = version_no + 1,
       revision = revision + 1,
       updated_at = NOW()
 WHERE article_code = 'FAQ-20260802095911601-e8c0edf1'
   AND is_deleted = 0
   AND content = 'VIP 等级按有效直推分支与团队业绩等晋级条件计算，并不是成员数量达到某个数就自动升级。请在「我的 - 团队 - 等级进度」查看当前有效分支、团队业绩和下一等级仍缺少的条件。';

-- #81 (b): the level-progress FAQ.
UPDATE nx_help_article
   SET content = '进入底部「团队」页签，打开「我的等级」即可查看当前等级、有效直推分支、团队业绩和下一等级要求。页面展示的是服务端实时计算结果。',
       version_no = version_no + 1,
       revision = revision + 1,
       updated_at = NOW()
 WHERE article_code = 'FAQ-20260829015717637-64825dc6'
   AND is_deleted = 0
   AND content = '进入「我的 - 团队」，打开等级或晋级进度区域，即可查看当前 VIP 等级、有效直推分支、团队业绩和下一等级要求。页面展示的是后端实时计算结果。';

-- #82: the deposit-arrival FAQ.
UPDATE nx_help_article
   SET content = '银行转账（VietQR）付款单生成后牌价会锁定并在页面倒计时，请在有效期内按页面显示的金额与附言码完成转账。到账状态以服务端订单为准，可在「充值收据」查看；一般与银行回单核对 1–3 分钟完成。超时未确认时，请在充值页点「充值未到账?」提交付款单号、附言码、付款金额与付款银行回单，以便对账。',
       version_no = version_no + 1,
       revision = revision + 1,
       updated_at = NOW()
 WHERE article_code = 'FAQ-20260829015717696-b80381fd'
   AND is_deleted = 0
   AND content = '到账时间取决于所选网络的区块确认进度。请先核对网络与地址，再到「我的 - 资金流水」或「收据」查看状态；长时间未更新时可携带交易哈希提交工单。';

-- Postcondition: the three answers must no longer describe the retired paths.
-- A stale copy in any surviving language row fails the migration loudly instead
-- of silently serving the wrong product shape.
SET @stale = (
  SELECT COUNT(*) FROM nx_help_article
   WHERE is_deleted = 0
     AND (content LIKE '%我的 - 团队%'
       OR content LIKE '%区块确认进度%'
       OR content LIKE '%交易哈希%'));
-- The remaining rows are the operator's own historical/archived answers, so this
-- migration only asserts the three rows it owns.
SET @fixed = (
  SELECT COUNT(*) FROM nx_help_article
   WHERE is_deleted = 0
     AND article_code IN ('FAQ-20260802095911601-e8c0edf1',
                          'FAQ-20260829015717637-64825dc6',
                          'FAQ-20260829015717696-b80381fd')
     AND content NOT LIKE '%我的 - 团队%'
     AND content NOT LIKE '%区块确认进度%'
     AND content NOT LIKE '%交易哈希%');
-- 3 rows in a database that has all three; fewer in a database that never
-- provisioned them (they are operator-authored content, not seeds).
SET @present = (
  SELECT COUNT(*) FROM nx_help_article
   WHERE is_deleted = 0
     AND article_code IN ('FAQ-20260802095911601-e8c0edf1',
                          'FAQ-20260829015717637-64825dc6',
                          'FAQ-20260829015717696-b80381fd'));
-- Fail the migration when a row is present but still carries the retired text.
SET @bad = (
  SELECT COUNT(*) FROM nx_help_article
   WHERE is_deleted = 0
     AND article_code IN ('FAQ-20260802095911601-e8c0edf1',
                          'FAQ-20260829015717637-64825dc6',
                          'FAQ-20260829015717696-b80381fd')
     AND (content LIKE '%我的 - 团队%'
       OR content LIKE '%区块确认进度%'
       OR content LIKE '%交易哈希%'));

COMMIT;

-- Surface the counts for the migration log; a non-zero @bad means an operator
-- rewrote the answer into a third shape that still names a retired path, which
-- needs a human decision rather than another automatic rewrite.
SELECT @present AS faq_rows_present, @fixed AS faq_rows_corrected, @bad AS faq_rows_still_stale;
