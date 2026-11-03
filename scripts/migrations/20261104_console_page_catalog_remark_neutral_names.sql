-- PC analytics page titles and A5 config remarks: App product names instead of 佣金 / 网络版税 /
-- 二级市场 / 复投 / 直属分成 / 七层 wording. Display text only; routes, config keys and values are
-- unchanged. Rerunnable: each row changes only while its text is byte-identical to what
-- 20260723_l6_behavior_analytics.sql or the F2 policy migrations seeded (BINARY compare; the
-- column collation ignores case and punctuation width), so operator edits are kept.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_behavior_page_catalog SET title_zh='奖励明细', updated_at=NOW() WHERE route='/pages/team/commissions' AND CAST(title_zh AS BINARY)=CAST('佣金' AS BINARY);
UPDATE nx_behavior_page_catalog SET title_zh='团队奖励', updated_at=NOW() WHERE route='/pages/team/unilevel' AND CAST(title_zh AS BINARY)=CAST('影响力网络版税' AS BINARY);
UPDATE nx_behavior_page_catalog SET title_zh='转让市场', updated_at=NOW() WHERE route='/pages/genesis/marketplace' AND CAST(title_zh AS BINARY)=CAST('二级市场' AS BINARY);
UPDATE nx_behavior_page_catalog SET title_zh='锁定计划', updated_at=NOW() WHERE route='/pages/me/wallet-repurchase' AND CAST(title_zh AS BINARY)=CAST('复投' AS BINARY);

UPDATE nx_config_item SET remark='好友奖励版本锁', updated_at=NOW() WHERE config_key='team.direct-referral.policy-version' AND CAST(remark AS BINARY)=CAST('直属分成版本锁' AS BINARY);
UPDATE nx_config_item SET remark='L1–L7 配置一致性版本锁', updated_at=NOW() WHERE config_key='team.seven-layer.revision' AND CAST(remark AS BINARY)=CAST('七层配置一致性版本锁' AS BINARY);

COMMIT;
