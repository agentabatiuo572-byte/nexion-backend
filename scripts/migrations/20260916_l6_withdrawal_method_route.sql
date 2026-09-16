-- Register the withdrawal-method entry without resetting existing routes or business data.
INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES ('/pages/me/wallet-withdraw-method','wallet-withdraw-method',1,'/pages/me/wallet-withdraw-method','/pages/me/wallet-withdraw-method',1,'pages-manifest-20260916',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0;
