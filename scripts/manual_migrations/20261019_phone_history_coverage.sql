-- Manual deployment evidence workflow only. Never register this file in the startup runner.
-- SOURCE installs the procedure; only an explicit CALL changes an account's coverage.
CREATE TABLE IF NOT EXISTS nx_phone_history_coverage_revision (
 revision_key VARCHAR(96) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL, source_environment VARCHAR(16) NOT NULL, run_id VARCHAR(96) NOT NULL,
 action VARCHAR(16) NOT NULL, reliable_from BIGINT NULL, binding_version BIGINT NOT NULL,
 verify_binding_time TINYINT NOT NULL, evidence_ref VARCHAR(200) NOT NULL,
 previous_state JSON NOT NULL, resulting_state JSON NOT NULL, recorded_at DATETIME(3) NOT NULL,
 KEY idx_phone_coverage_account(user_id,source_environment,run_id,recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS apply_phone_history_coverage$$
CREATE PROCEDURE apply_phone_history_coverage(
 IN p_revision VARCHAR(96), IN p_user BIGINT, IN p_environment VARCHAR(16), IN p_run VARCHAR(96),
 IN p_action VARCHAR(16), IN p_from BIGINT, IN p_binding_version BIGINT,
 IN p_verify_binding_time BOOLEAN, IN p_evidence VARCHAR(200)
)
SQL SECURITY INVOKER
main: BEGIN
 DECLARE v_user BIGINT;
 DECLARE v_version BIGINT;
 DECLARE v_from BIGINT;
 DECLARE v_all BOOLEAN;
 DECLARE v_verified BOOLEAN;
 DECLARE v_evidence VARCHAR(200);
 DECLARE v_before JSON;
 DECLARE v_after JSON;
 DECLARE v_prior JSON;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

 IF p_revision IS NULL OR CHAR_LENGTH(p_revision)=0 OR p_user IS NULL OR p_user<=0
    OR p_environment IS NULL OR p_run IS NULL OR p_action IS NULL
    OR p_action NOT IN ('ESTABLISH','INVALIDATE') OR p_binding_version IS NULL
    OR p_verify_binding_time IS NULL OR p_verify_binding_time NOT IN (0,1)
    OR p_evidence IS NULL OR CHAR_LENGTH(TRIM(p_evidence))=0
    OR (p_action='ESTABLISH' AND (p_from IS NULL OR p_from<0 OR p_from>UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000))
    OR (p_action='INVALIDATE' AND (p_from IS NOT NULL OR p_verify_binding_time<>0)) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PHONE_HISTORY_INPUT_INVALID';
 END IF;
 START TRANSACTION;
 SELECT config_key,config_value FROM nx_config_item
  WHERE config_key IN ('E.compute.phoneBinding.allowReplacement','E.compute.phoneBinding.minReplacementIntervalDays',
   'E.compute.phoneBinding.replacementWindowDays','E.compute.phoneBinding.maxReplacementsInWindow','E.compute.phoneBinding.policyVersion')
  ORDER BY config_key FOR SHARE;
 SELECT id INTO v_user FROM nx_user WHERE id=p_user FOR UPDATE;
 IF v_user IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PHONE_HISTORY_ACCOUNT_MISSING'; END IF;

 SELECT JSON_OBJECT('user',user_id,'environment',source_environment,'run',run_id,'action',action,
  'from',reliable_from,'version',binding_version,'verify',verify_binding_time,'evidence',evidence_ref)
  INTO v_prior FROM nx_phone_history_coverage_revision WHERE revision_key=p_revision FOR UPDATE;
 IF v_prior IS NOT NULL THEN
  IF v_prior<>JSON_OBJECT('user',p_user,'environment',p_environment,'run',p_run,'action',p_action,
    'from',p_from,'version',p_binding_version,'verify',p_verify_binding_time,'evidence',p_evidence) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PHONE_HISTORY_REVISION_INPUT_CHANGED';
  END IF;
  COMMIT;
  SELECT * FROM nx_phone_history_coverage_revision WHERE revision_key=p_revision;
  LEAVE main;
 END IF;
 SELECT version INTO v_version FROM nx_phone_binding
  WHERE user_id=p_user AND source_environment=p_environment AND run_id=p_run FOR UPDATE;
 IF v_version IS NULL OR v_version<>p_binding_version THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PHONE_HISTORY_BINDING_CHANGED';
 END IF;
 INSERT IGNORE INTO nx_phone_replacement_history_state(user_id,source_environment,run_id)
  VALUES(p_user,p_environment,p_run);
 SELECT history_complete_from,all_history_known,changed_at_verified,evidence_ref
  INTO v_from,v_all,v_verified,v_evidence FROM nx_phone_replacement_history_state
  WHERE user_id=p_user AND source_environment=p_environment AND run_id=p_run FOR UPDATE;
 SET v_before=JSON_OBJECT('completeFrom',v_from,'allHistoryKnown',v_all,'changedAtVerified',v_verified,'evidenceRef',v_evidence);
 IF p_action='INVALIDATE' THEN
  -- A proven capture gap invalidates completeness, never deletes successful replacement facts.
  SET v_from=NULL; SET v_all=0; SET v_verified=0;
 ELSE
  -- Existing earlier continuous evidence is preserved. A later T1 requires explicit INVALIDATE first.
  IF v_from IS NOT NULL AND p_from>v_from THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PHONE_HISTORY_INVALIDATE_GAP_FIRST';
  END IF;
  SET v_from=p_from;
  IF p_verify_binding_time THEN SET v_verified=1; END IF;
 END IF;
 UPDATE nx_phone_replacement_history_state SET history_complete_from=v_from,all_history_known=v_all,
  changed_at_verified=v_verified,evidence_ref=p_evidence
  WHERE user_id=p_user AND source_environment=p_environment AND run_id=p_run;
 SET v_after=JSON_OBJECT('completeFrom',v_from,'allHistoryKnown',v_all,'changedAtVerified',v_verified,'evidenceRef',p_evidence);
 INSERT INTO nx_phone_history_coverage_revision(revision_key,user_id,source_environment,run_id,action,reliable_from,
  binding_version,verify_binding_time,evidence_ref,previous_state,resulting_state,recorded_at)
 VALUES(p_revision,p_user,p_environment,p_run,p_action,p_from,p_binding_version,p_verify_binding_time,
  p_evidence,v_before,v_after,UTC_TIMESTAMP(3));
 COMMIT;
 SELECT * FROM nx_phone_history_coverage_revision WHERE revision_key=p_revision;
 SELECT * FROM nx_phone_replacement_history_state
  WHERE user_id=p_user AND source_environment=p_environment AND run_id=p_run;
END$$
DELIMITER ;
