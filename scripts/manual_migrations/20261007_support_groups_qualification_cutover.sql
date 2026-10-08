-- Explicit cutover only, after saved preflight and adjudication of conflicts.
-- Never register this data decision in the automatic startup installer.
CREATE TABLE IF NOT EXISTS nx_support_migration(id VARCHAR(64) PRIMARY KEY,committed_at DATETIME(6) NOT NULL) ENGINE=InnoDB;
DELIMITER $$
DROP PROCEDURE IF EXISTS support_groups_qualification_cutover$$
CREATE PROCEDURE support_groups_qualification_cutover()
BEGIN
 DECLARE cutover_at DATETIME(6);
 DECLARE already_applied INT;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 START TRANSACTION;
 -- A single durable migration key serializes simultaneous cutovers.
 INSERT IGNORE INTO nx_support_migration(id,committed_at) VALUES('support-groups-cutover-lock',UTC_TIMESTAMP(6));
 SELECT committed_at INTO cutover_at FROM nx_support_migration WHERE id='support-groups-cutover-lock' FOR UPDATE;
 SELECT COUNT(*) INTO already_applied FROM nx_support_migration WHERE id='support-groups-20261007';
 IF already_applied=0 THEN
  IF EXISTS(SELECT user_id FROM nx_support_agent_user_assignment WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1)
   OR EXISTS(SELECT 1 FROM nx_support_agent_user_assignment x LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
    LEFT JOIN nx_user u ON u.id=x.user_id WHERE x.is_deleted=0 AND (a.id IS NULL OR u.id IS NULL))
   OR EXISTS(SELECT 1 FROM nx_support_binding_pool p JOIN nx_support_agent_user_assignment x ON x.user_id=p.customer_id AND x.status='ACTIVE' AND x.is_deleted=0)
   OR EXISTS(SELECT 1 FROM nx_support_agent_user_assignment a JOIN nx_support_agent_user_assignment b
    ON a.user_id=b.user_id AND a.id<b.id AND a.is_deleted=0 AND b.is_deleted=0
    AND a.starts_at<COALESCE(b.ends_at,'9999-12-31') AND b.starts_at<COALESCE(a.ends_at,'9999-12-31'))
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_GROUP_CUTOVER_REVIEW_REQUIRED'; END IF;
  SET cutover_at=UTC_TIMESTAMP(6);
  INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,actor_admin_id,reason,operation_id)
  SELECT a.id,CASE p.seat_type WHEN 'MANAGER' THEN 'SUPERVISOR' ELSE 'SERVICE' END,
   CASE WHEN a.status=1 AND p.enabled=1 THEN 'ENABLED' ELSE 'DISABLED' END,cutover_at,1,NULL,
   'Explicit legacy SUPPORT/profile evidence; no earlier history inferred','support-groups-20261007'
  FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id AND p.is_deleted=0
  WHERE a.is_deleted=0 AND (p.seat_type='MANAGER' OR (p.seat_type='DEDICATED' AND FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))>0))
   AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0 AND r.role_code='SUPPORT' WHERE rr.admin_id=a.id AND rr.is_deleted=0)
   AND NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history q WHERE q.admin_id=a.id AND q.ends_at IS NULL AND q.qualification_kind=CASE p.seat_type WHEN 'MANAGER' THEN 'SUPERVISOR' ELSE 'SERVICE' END);
  INSERT INTO nx_support_group_member_history(agent_admin_id,group_id,starts_at,version,reason,operation_id)
  SELECT q.admin_id,NULL,cutover_at,1,'Explicitly ungrouped at cutover; earlier group unknown','support-groups-20261007'
  FROM nx_support_account_qualification_history q WHERE q.qualification_kind='SERVICE' AND q.ends_at IS NULL AND q.state<>'REMOVED'
   AND NOT EXISTS(SELECT 1 FROM nx_support_group_member_history m WHERE m.agent_admin_id=q.admin_id AND m.ends_at IS NULL);
  INSERT INTO nx_support_customer_route_history(customer_id,group_id,starts_at,version,reason,operation_id)
  SELECT p.customer_id,NULL,cutover_at,1,'No proven legacy group route','support-groups-20261007' FROM nx_support_binding_pool p
   WHERE NOT EXISTS(SELECT 1 FROM nx_support_customer_route_history r WHERE r.customer_id=p.customer_id AND r.ends_at IS NULL);
  INSERT INTO nx_support_migration(id,committed_at) VALUES('support-groups-20261007',cutover_at);
 END IF;
 COMMIT;
END$$
CALL support_groups_qualification_cutover()$$
DROP PROCEDURE support_groups_qualification_cutover$$
DELIMITER ;
