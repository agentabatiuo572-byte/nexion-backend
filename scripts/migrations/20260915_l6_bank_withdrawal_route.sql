-- Register the new real App route without resetting any existing route or business data.
INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES ('/pages/me/wallet-withdraw-bank','wallet-withdraw-bank',1,'/pages/me/wallet-withdraw-bank','/pages/me/wallet-withdraw-bank',1,'pages-manifest-20260915',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0;
