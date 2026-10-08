-- Add promotion reward pages without changing published route migrations.
SET NAMES utf8mb4;

INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
('/pages/events/promotion-rewards','活动奖励',1,'/pages/events/promotion-rewards','/pages/events/promotion-rewards',1,'pages-manifest-20261008',0),
('/pages/events/promotion-reward-detail','奖励详情',1,'/pages/events/promotion-reward-detail','/pages/events/promotion-reward-detail',1,'pages-manifest-20261008',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0;
