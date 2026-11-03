SET NAMES utf8mb4;
INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
('/pages/events/promotion-detail','购机活动详情',2,'/pages/events/events','/pages/events/promotion-detail',1,'pages-manifest-20261103',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0,title_zh=VALUES(title_zh),page_level=VALUES(page_level),
  parent_l1=VALUES(parent_l1),parent_l2=VALUES(parent_l2),source_revision=VALUES(source_revision);
