SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_NAME='nx_v_rank_config' AND COLUMN_NAME='purchase_l1_rate')=0,
  'ALTER TABLE nx_v_rank_config ADD purchase_l1_rate DECIMAL(5,4) NOT NULL DEFAULT 0, ADD purchase_l2_rate DECIMAL(5,4) NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

START TRANSACTION;
SELECT lock_key FROM nx_admin_operation_mutex WHERE lock_key='F1_VRANK_BOOTSTRAP' FOR UPDATE;
INSERT IGNORE INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
SELECT 'team.purchase-rank.cutover-at','PENDING','STRING','team','ADMIN','B83 好友购买奖励按等级生效时刻',1,0
FROM nx_v_rank_config WHERE rank_code IN ('V0','V1','V2','V3','V4','V5','V6','V7','V8') AND is_deleted=0
HAVING COUNT(*)=9;
SET @b83_seed = ROW_COUNT();
UPDATE nx_v_rank_config
   SET purchase_l1_rate = ELT(CAST(SUBSTRING(rank_code,2) AS UNSIGNED)+1,.040,.040,.042,.044,.047,.052,.058,.066,.080),
       purchase_l2_rate = ELT(CAST(SUBSTRING(rank_code,2) AS UNSIGNED)+1,.020,.020,.021,.022,.025,.029,.035,.042,.055)
 WHERE @b83_seed=1 AND rank_code IN ('V0','V1','V2','V3','V4','V5','V6','V7','V8') AND is_deleted=0;
INSERT INTO nx_audit_log(service_name,action,resource_type,resource_id,actor_type,actor_username,risk_level,detail_json)
SELECT 'nexion-backend','F_PURCHASE_RANK_SEEDED','TEAM_POLICY','team.purchase-rank.cutover-at','SYSTEM','SYSTEM','HIGH',
       JSON_OBJECT('rankCount',9,'purchaseL1Rate',JSON_ARRAY(.040,.040,.042,.044,.047,.052,.058,.066,.080),
                   'purchaseL2Rate',JSON_ARRAY(.020,.020,.021,.022,.025,.029,.035,.042,.055))
 WHERE @b83_seed=1;
COMMIT;
