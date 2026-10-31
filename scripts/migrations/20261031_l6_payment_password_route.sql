SET NAMES utf8mb4;
INSERT INTO nx_behavior_page_catalog(route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES('/pages/me/payment-password','支付密码',2,'/pages/me/me','/pages/me/payment-password',1,'pages-manifest-20261031',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0,page_level=VALUES(page_level),parent_l1=VALUES(parent_l1),parent_l2=VALUES(parent_l2);
