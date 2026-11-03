-- Help-centre level FAQs: replace the wording 20260920_help_faq_product_shape_repair.sql
-- wrote (有效直推分支 / 团队业绩 / 服务端实时计算) with the rank guide's terms
-- (自购算力点 / 达标好友 / 团队算力点). Rerunnable; never overwrites an operator edit.
--
-- Guard: each row is rewritten only while it still carries the byte-exact text the
-- 20260920 repair wrote (BINARY compare: the column collation ignores case and
-- treats ， and , as equal). An operator who changed the answer since keeps their
-- wording, and re-running this migration is a no-op.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_help_article
   SET content = '等级由你的自购算力点、达标好友和团队算力点共同决定，不是团队人数达到某个数就自动升级。请进入「底部团队页签 → 我的等级」查看当前进度和下一等级还差的条件。',
       version_no = version_no + 1,
       revision = revision + 1,
       updated_at = NOW()
 WHERE article_code = 'FAQ-20260802095911601-e8c0edf1'
   AND is_deleted = 0
   AND CAST(content AS BINARY) = CAST('等级按有效直推分支与团队业绩等晋级条件计算，并不是成员数量达到某个数就自动升级。请进入「底部团队页签 → 我的等级」查看当前有效分支、团队业绩和下一等级仍缺少的条件。' AS BINARY);

UPDATE nx_help_article
   SET content = '进入底部「团队」页签，打开「我的等级」即可查看当前等级、自购算力点、达标好友、团队算力点和下一等级要求。',
       version_no = version_no + 1,
       revision = revision + 1,
       updated_at = NOW()
 WHERE article_code = 'FAQ-20260829015717637-64825dc6'
   AND is_deleted = 0
   AND CAST(content AS BINARY) = CAST('进入底部「团队」页签，打开「我的等级」即可查看当前等级、有效直推分支、团队业绩和下一等级要求。页面展示的是服务端实时计算结果。' AS BINARY);

COMMIT;
