-- L6 acceptance hardening: client idempotency and build-derived current page catalog.
SET NAMES utf8mb4;

SET @l6_add_client_event_id := IF(
  EXISTS(
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE()
       AND TABLE_NAME='nx_behavior_event_fact'
       AND COLUMN_NAME='client_event_id'
  ),
  'SELECT 1',
  'ALTER TABLE nx_behavior_event_fact ADD COLUMN client_event_id CHAR(32) NULL AFTER event_id'
);
PREPARE l6_stmt FROM @l6_add_client_event_id;
EXECUTE l6_stmt;
DEALLOCATE PREPARE l6_stmt;

UPDATE nx_behavior_event_fact
   SET client_event_id=LOWER(MD5(CONCAT('legacy-l6:',event_id)))
 WHERE client_event_id IS NULL;

ALTER TABLE nx_behavior_event_fact
  MODIFY client_event_id CHAR(32) NOT NULL;

SET @l6_add_client_event_id_key := IF(
  EXISTS(
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA=DATABASE()
       AND TABLE_NAME='nx_behavior_event_fact'
       AND INDEX_NAME='uk_behavior_client_event_id'
  ),
  'SELECT 1',
  'ALTER TABLE nx_behavior_event_fact ADD UNIQUE KEY uk_behavior_client_event_id(client_event_id)'
);
PREPARE l6_stmt FROM @l6_add_client_event_id_key;
EXECUTE l6_stmt;
DEALLOCATE PREPARE l6_stmt;

-- Existing routes still present in the signed 2026-07-27 App build share one
-- source revision. Routes removed from pages.json are retired below.
UPDATE nx_behavior_page_catalog
   SET source_revision='pages-i18n-20260727',is_deleted=0,updated_at=NOW();

UPDATE nx_behavior_page_catalog
   SET is_deleted=1,updated_at=NOW()
 WHERE route IN (
   '/pages/me/wrapped','/pages/learn/learn','/pages/learn/course',
   '/pages/me/replay-tour','/pages/me/wallet-nex-v2-lock'
 );

INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
('/pages/entry-surfaces/index','入口分流',1,'/pages/entry-surfaces/index','/pages/entry-surfaces/index',0,'pages-i18n-20260727',0),
('/pages/entry-surfaces/signed','签名入口',2,'/pages/entry-surfaces/index','/pages/entry-surfaces/signed',0,'pages-i18n-20260727',0),
('/pages/entry-surfaces/h5','H5 入口',2,'/pages/entry-surfaces/index','/pages/entry-surfaces/h5',0,'pages-i18n-20260727',0),
('/pages/entry-surfaces/white','白屏恢复',2,'/pages/entry-surfaces/index','/pages/entry-surfaces/white',0,'pages-i18n-20260727',0),
('/pages/register/success','注册成功',2,'/pages/register/register','/pages/register/success',0,'pages-i18n-20260727',0),
('/pages/session/kicked','会话失效',2,'/pages/session/kicked','/pages/session/kicked',0,'pages-i18n-20260727',0),
('/pages/earn/device-detail','设备详情',2,'/pages/earn/earn','/pages/earn/device-detail',1,'pages-i18n-20260727',0),
('/pages/compute-share/download','算力分享下载',2,'/pages/earn/earn','/pages/compute-share/download',1,'pages-i18n-20260727',0),
('/pages/me/wallet-address-rebind','钱包地址重绑',3,'/pages/me/me','/pages/me/wallet',1,'pages-i18n-20260727',0),
('/pages/me/usdt-guide','USDT 指南',3,'/pages/me/me','/pages/me/wallet',1,'pages-i18n-20260727',0),
('/pages/me/rewards','奖励',2,'/pages/me/me','/pages/me/rewards',1,'pages-i18n-20260727',0),
('/pages/me/rewards-list','奖励明细',3,'/pages/me/me','/pages/me/rewards',1,'pages-i18n-20260727',0),
('/pages/support/messages','客服消息',2,'/pages/me/me','/pages/me/support',1,'pages-i18n-20260727',0),
('/pages/support/chat','客服会话',3,'/pages/me/me','/pages/me/support',1,'pages-i18n-20260727',0)
ON DUPLICATE KEY UPDATE
 title_zh=VALUES(title_zh),page_level=VALUES(page_level),
 parent_l1=VALUES(parent_l1),parent_l2=VALUES(parent_l2),
 tracked=VALUES(tracked),source_revision=VALUES(source_revision),
 is_deleted=0,updated_at=NOW();
