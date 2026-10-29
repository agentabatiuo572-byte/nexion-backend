-- Track the white paper page and the trust-document viewer added to the App trust centre.
SET NAMES utf8mb4;

INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
('/pages/trust/whitepaper','白皮书',1,'/pages/trust/whitepaper','/pages/trust/whitepaper',1,'pages-manifest-20261029',0),
('/pages/trust/document','资料原件',1,'/pages/trust/document','/pages/trust/document',1,'pages-manifest-20261029',0)
ON DUPLICATE KEY UPDATE tracked=1,is_deleted=0;
