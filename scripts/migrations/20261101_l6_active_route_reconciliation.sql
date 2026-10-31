-- Add current business pages; retired catalog rows remain available for historical reports.
SET NAMES utf8mb4;
INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
('/pages/events/detail','活动详情',2,'/pages/events/events','/pages/events/detail',1,'pages-manifest-20261101',0),
('/pages/events/genesis-raffle','创世抽奖',2,'/pages/events/events','/pages/events/genesis-raffle',1,'pages-manifest-20261101',0),
('/pages/me/phone-binding','绑定手机号',2,'/pages/me/me','/pages/me/phone-binding',1,'pages-manifest-20261101',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0,title_zh=VALUES(title_zh),page_level=VALUES(page_level),
  parent_l1=VALUES(parent_l1),parent_l2=VALUES(parent_l2),source_revision=VALUES(source_revision);

UPDATE nx_behavior_page_catalog
SET tracked=0,is_deleted=0,source_revision='pages-manifest-20261101',updated_at=NOW()
WHERE route IN ('/pages/me/support-tickets','/pages/me/wallet','/pages/me/wallet-repurchase','/pages/me/wallet-repurchase-how');
