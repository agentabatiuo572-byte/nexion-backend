-- The App records three distinct visible product details per user and ISO week.
-- Bind that server fact to the matching weekly task without overwriting an
-- operator-owned or intentionally disabled binding.
START TRANSACTION;
INSERT IGNORE INTO nx_admin_operation_mutex(lock_key,updated_at) VALUES('H3_CONFIG',NOW());
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='H3_CONFIG' FOR UPDATE;
INSERT INTO nx_growth_quest_event_binding
  (binding_code,producer,event_type,quest_code,user_id_field,status,created_at,updated_at,is_deleted)
SELECT 'WEEKLY_STORE_THREE_PRODUCTS','SYSTEM','H3_STOREFRONT_THREE_PRODUCTS_VIEWED',
       m.mission_code,'user_id',1,NOW(),NOW(),0
  FROM nx_mission m
 WHERE m.mission_code='weekly_t2_browse_store' AND m.mission_type='WEEKLY_T2'
   AND m.status IN (0,1) AND m.is_deleted=0
   AND NOT EXISTS (
     SELECT 1 FROM nx_growth_quest_event_binding b
      WHERE b.binding_code='WEEKLY_STORE_THREE_PRODUCTS'
         OR (b.quest_code=m.mission_code AND b.is_deleted=0)
         OR (b.producer='SYSTEM' AND b.event_type='H3_STOREFRONT_THREE_PRODUCTS_VIEWED'
             AND b.user_id_field='user_id' AND b.status=1 AND b.is_deleted=0)
   );
COMMIT;
