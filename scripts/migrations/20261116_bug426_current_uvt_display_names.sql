-- BUG-426: forward-only cleanup of known, current token display copy.
-- Exact business keys and old values preserve operator edits. No historical
-- versions, protocol identifiers, monetary settings or prize-pool rows are created.
SET NAMES utf8mb4;
START TRANSACTION;

-- Retained wheel tiers only: an empty pool stays empty. The kind alias is owned
-- by 20261116_h4_wheel_reward_kind_uvt.sql; every payout setting stays unchanged.
UPDATE nx_growth_wheel_tier
   SET reward_name=CASE BINARY tier_name
         WHEN BINARY 'comfort-nex-5' THEN '+5 UVT'
         WHEN BINARY 'nex-30' THEN '+30 UVT'
         WHEN BINARY 'nex-150' THEN '+150 UVT'
       END,
       updated_at=updated_at
 WHERE is_deleted=0
   AND ((BINARY tier_name=BINARY 'comfort-nex-5' AND BINARY reward_name=BINARY '+5 NEX')
     OR (BINARY tier_name=BINARY 'nex-30' AND BINARY reward_name=BINARY '+30 NEX')
     OR (BINARY tier_name=BINARY 'nex-150' AND BINARY reward_name=BINARY '+150 NEX'));

UPDATE nx_streak_milestone
   SET reward_name=CASE milestone_day
         WHEN 3 THEN '+5 UVT' WHEN 7 THEN '+15 UVT' WHEN 21 THEN '+100 UVT'
       END,
       updated_at=updated_at
 WHERE is_deleted=0
   AND ((milestone_day=3 AND BINARY reward_name=BINARY '+5 NEX')
     OR (milestone_day=7 AND BINARY reward_name=BINARY '+15 NEX')
     OR (milestone_day=21 AND BINARY reward_name=BINARY '+100 NEX'));

UPDATE nx_admin_permission
   SET permission_name=CASE BINARY permission_code
         WHEN BINARY 'finprod_g3_curve_target_price_write' THEN 'G3 周曲线目标价(升价放大UVT负债·过B1)'
         WHEN BINARY 'growth_h5_read' THEN 'H5 签到&UVT页面读'
         WHEN BINARY 'growth_h5_rule_write' THEN 'H5 签到规则Lucky概率(升概率放大UVT派发)'
       END,
       updated_at=updated_at
 WHERE is_deleted=0
   AND ((BINARY permission_code=BINARY 'finprod_g3_curve_target_price_write'
         AND BINARY permission_name=BINARY 'G3 周曲线目标价(升价放大NEX负债·过B1)')
     OR (BINARY permission_code=BINARY 'growth_h5_read' AND BINARY permission_name=BINARY 'H5 签到&NEX页面读')
     OR (BINARY permission_code=BINARY 'growth_h5_rule_write'
         AND BINARY permission_name=BINARY 'H5 签到规则Lucky概率(升概率放大NEX派发)'));

UPDATE nx_behavior_page_catalog
   SET title_zh=CASE BINARY route
         WHEN BINARY '/pages/me/wallet-nex' THEN 'UVT 钱包'
         WHEN BINARY '/pages/trust/nex' THEN 'UVT 信任'
       END,
       updated_at=updated_at
 WHERE is_deleted=0
   AND ((BINARY route=BINARY '/pages/me/wallet-nex' AND BINARY title_zh=BINARY 'NEX 钱包')
     OR (BINARY route=BINARY '/pages/trust/nex' AND BINARY title_zh=BINARY 'NEX 信任'));

-- These two current activity definitions are also visible while ongoing.
-- Keep their lifecycle status, rules, amounts and dates unchanged.
UPDATE nx_event_quest
   SET quest_name='Weekend Double UVT',updated_at=updated_at
 WHERE BINARY quest_code=BINARY 'evt-weekend-double-nex' AND is_deleted=0
   AND BINARY quest_name=BINARY 'Weekend Double NEX';
UPDATE nx_event_quest
   SET description='Reach the configured rank in your first week for UVT and a streak saver.',
       updated_at=updated_at
 WHERE BINARY quest_code=BINARY 'evt-onboarding-7d' AND is_deleted=0
   AND BINARY description=BINARY 'Reach the configured rank in your first week for NEX and a streak saver.';
UPDATE nx_event_quest
   SET reward_name='UVT and streak saver',updated_at=updated_at
 WHERE BINARY quest_code=BINARY 'evt-onboarding-7d' AND is_deleted=0
   AND BINARY reward_name=BINARY 'NEX and streak saver';

UPDATE nx_nova_channel
   SET trigger_rule='全网算力或 UVT 行情满足播报条件',updated_at=updated_at
 WHERE BINARY channel_key=BINARY 'market' AND is_deleted=0
   AND BINARY trigger_rule=BINARY '全网算力或 NEX 行情满足播报条件';

-- This is the current section descriptor only. Published fields and all version
-- rows must be changed through the existing I4 draft/A2 publication workflow.
UPDATE nx_trust_section
   SET description='UVT 叙事与机制说明',updated_at=updated_at
 WHERE BINARY section_key=BINARY 'nexNarrative' AND is_deleted=0
   AND BINARY description=BINARY 'NEX 叙事与机制说明';

-- FAQ stores the current answer in one row, with optimistic version counters;
-- it has no separate content-version table. Match the approved Chinese answer
-- exactly, without inventing a fee-offset promise or changing other languages.
UPDATE nx_help_article
   SET content='提现手续费以提交前确认页展示为准。',
       version_no=COALESCE(version_no,1)+1,revision=revision+1,updated_at=NOW()
 WHERE BINARY article_code=BINARY 'FAQ-20261026192537533-9fd5d814'
   AND BINARY level=BINARY 'zh-CN' AND BINARY format=BINARY 'faq'
   AND BINARY surface=BINARY 'Help Center' AND status=1 AND is_deleted=0
   AND BINARY content=BINARY '提现手续费按网络费率实时计算，并在提交前的确认页展示，持有 NEX 可抵扣部分手续费。';

COMMIT;
