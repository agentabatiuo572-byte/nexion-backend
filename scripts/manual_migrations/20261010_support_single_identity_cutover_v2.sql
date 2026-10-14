-- MANUAL ONLY: never register this file in an automatic/startup installer.
-- Preserve the v1 migration and every existing qualification/member/binding interval.
-- Save full current HUMAN profile/binding inventory and adjudicate conflicts before applying.
-- Reviewer must populate this ledger from current account/profile/RBAC/binding evidence.
-- No historical SERVICE qualification or group membership is inferred from a legacy profile.
CREATE TABLE IF NOT EXISTS nx_support_single_identity_review_v2 (
 admin_id BIGINT PRIMARY KEY,
 decision VARCHAR(16) NOT NULL,
 reviewer_admin_id BIGINT NOT NULL,
 reason VARCHAR(200) NOT NULL,
 evidence_ref VARCHAR(240) NOT NULL,
 reviewed_at DATETIME(6) NOT NULL,
 CHECK (decision IN ('ALLOW','EXCLUDE'))
) ENGINE=InnoDB;
-- Current inventory: both human aliases, all enabled/disabled/removal/qualification evidence.
SELECT a.id,a.status,p.seat_type,p.service_types,p.enabled,p.transferable,p.busy,
 q.qualification_kind,q.state,q.starts_at,q.ends_at,
 (SELECT COUNT(*) FROM nx_support_agent_user_assignment b WHERE b.agent_admin_id=a.id AND b.status='ACTIVE' AND b.is_deleted=0) active_bindings
FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id AND p.is_deleted=0
LEFT JOIN nx_support_account_qualification_history q ON q.admin_id=a.id
WHERE a.is_deleted=0 AND (p.seat_type IN ('GENERAL','DEDICATED','MANAGER')
 OR FIND_IN_SET('support',REPLACE(LOWER(p.service_types),' ',''))>0
 OR FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))>0)
ORDER BY a.id,q.starts_at;
DELIMITER $$
DROP PROCEDURE IF EXISTS support_single_identity_cutover_v2$$
CREATE PROCEDURE support_single_identity_cutover_v2()
BEGIN
 DECLARE cutover_at DATETIME(6);
 DECLARE lock_at DATETIME(6);
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 START TRANSACTION;
 INSERT IGNORE INTO nx_support_migration(id,committed_at) VALUES('support-groups-cutover-lock',UTC_TIMESTAMP(6));
 SELECT committed_at INTO lock_at FROM nx_support_migration WHERE id='support-groups-cutover-lock' FOR UPDATE;
 IF NOT EXISTS(SELECT 1 FROM nx_support_migration WHERE id='support-groups-20261007')
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_IDENTITY_V1_REQUIRED'; END IF;
 IF NOT EXISTS(SELECT 1 FROM nx_support_migration WHERE id='support-single-identity-v2-20261010') THEN
  IF EXISTS(SELECT user_id FROM nx_support_agent_user_assignment WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1)
   OR EXISTS(SELECT 1 FROM nx_support_agent_user_assignment x LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
    LEFT JOIN nx_user u ON u.id=x.user_id WHERE x.is_deleted=0 AND (a.id IS NULL OR u.id IS NULL))
   OR EXISTS(SELECT 1 FROM nx_support_binding_pool p JOIN nx_support_agent_user_assignment x ON x.user_id=p.customer_id AND x.status='ACTIVE' AND x.is_deleted=0)
   OR EXISTS(SELECT 1 FROM nx_support_agent_user_assignment a JOIN nx_support_agent_user_assignment b
    ON a.user_id=b.user_id AND a.id<b.id AND a.is_deleted=0 AND b.is_deleted=0
    AND a.starts_at<COALESCE(b.ends_at,'9999-12-31') AND b.starts_at<COALESCE(a.ends_at,'9999-12-31'))
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_IDENTITY_BINDING_REVIEW_REQUIRED'; END IF;
  -- Qualification changes already require live SUPER authority plus platform_a1_write.
  -- Validate the exact ledger actor against those same durable facts, never a free-form id.
  IF EXISTS(SELECT 1 FROM nx_support_single_identity_review_v2 v
   WHERE v.reviewer_admin_id<=0 OR NOT EXISTS(
    SELECT 1 FROM nx_admin reviewer WHERE reviewer.id=v.reviewer_admin_id
     AND reviewer.status=1 AND reviewer.is_deleted=0
     AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
      AND r.status=1 AND r.is_deleted=0 AND r.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN')
      WHERE rr.admin_id=reviewer.id AND rr.is_deleted=0 FOR SHARE)
     AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
      AND r.status=1 AND r.is_deleted=0 JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
      JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0
       AND p.resource_type='API' AND p.permission_code='platform_a1_write'
      WHERE rr.admin_id=reviewer.id AND rr.is_deleted=0 FOR SHARE) FOR SHARE))
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_IDENTITY_REVIEWER_AUTHORITY_REQUIRED'; END IF;
  -- Every valid legacy GENERAL+support without a current SERVICE fact requires a decision.
  IF EXISTS(SELECT 1 FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id AND p.is_deleted=0
   LEFT JOIN nx_support_single_identity_review_v2 v ON v.admin_id=a.id
   WHERE a.is_deleted=0 AND a.status=1 AND p.enabled=1 AND p.seat_type='GENERAL'
    AND FIND_IN_SET('support',REPLACE(LOWER(p.service_types),' ',''))>0
    AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
      AND r.status=1 AND r.is_deleted=0 AND r.role_code='SUPPORT' WHERE rr.admin_id=a.id AND rr.is_deleted=0)
    AND NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history q WHERE q.admin_id=a.id
      AND q.qualification_kind='SERVICE' AND q.ends_at IS NULL)
    AND (v.admin_id IS NULL OR v.decision NOT IN ('ALLOW','EXCLUDE') OR CHAR_LENGTH(TRIM(v.reason))<8
      OR CHAR_LENGTH(TRIM(v.evidence_ref))=0 OR v.reviewer_admin_id IS NULL OR v.reviewed_at>UTC_TIMESTAMP(6)))
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_IDENTITY_APPROVED_REVIEW_REQUIRED'; END IF;
  SET cutover_at=UTC_TIMESTAMP(6);
  INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,actor_admin_id,reason,operation_id)
  SELECT a.id,'SERVICE','ENABLED',cutover_at,1,v.reviewer_admin_id,v.reason,'support-single-identity-v2-20261010'
  FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id AND p.is_deleted=0
  JOIN nx_support_single_identity_review_v2 v ON v.admin_id=a.id AND v.decision='ALLOW'
  WHERE a.is_deleted=0 AND a.status=1 AND p.enabled=1 AND p.seat_type='GENERAL'
   AND FIND_IN_SET('support',REPLACE(LOWER(p.service_types),' ',''))>0
   AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
     AND r.status=1 AND r.is_deleted=0 AND r.role_code='SUPPORT' WHERE rr.admin_id=a.id AND rr.is_deleted=0)
   AND NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history q WHERE q.admin_id=a.id
     AND q.qualification_kind='SERVICE' AND q.ends_at IS NULL);
  -- Group membership stays unknown until an explicit group operation creates a fact.
  -- This marker proves only reviewed current additions, never full prior-month coverage.
  INSERT INTO nx_support_migration(id,committed_at) VALUES('support-single-identity-v2-20261010',cutover_at);
 END IF;
 COMMIT;
END$$
CALL support_single_identity_cutover_v2()$$
DROP PROCEDURE support_single_identity_cutover_v2$$
DELIMITER ;