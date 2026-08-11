-- The authenticated H5 landing page is a real user surface. It must be
-- catalogued as tracked before its automatic page-view event reaches L6.
SET NAMES utf8mb4;

INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
  ('/pages/index/index','index',1,'/pages/index/index','/pages/index/index',1,'l6-h5-runtime-20260811',0)
ON DUPLICATE KEY UPDATE
  tracked=1,
  is_deleted=0,
  source_revision='l6-h5-runtime-20260811',
  updated_at=NOW();
