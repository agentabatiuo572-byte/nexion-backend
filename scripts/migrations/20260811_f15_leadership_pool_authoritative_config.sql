-- F15 leadership-pool settlement authority bootstrap.
--
-- The four money/schedule values are deliberately NOT given executable defaults. On the first
-- authoritative bootstrap, all unversioned legacy values (including apparently valid ones) are
-- replaced by __UNCONFIGURED__: an old value has neither the new percentage contract nor the new
-- audit/version provenance and therefore must never silently enable settlement. Operators must
-- explicitly save all four values through the audited F4 command. Once a positive version exists,
-- rerunning this idempotent startup migration preserves nonblank operator values; blank/deleted
-- rows are repaired to the fail-closed sentinel. Runtime validation remains authoritative and
-- rejects any later direct-DB illegal value.

START TRANSACTION;

SET @f15_has_authoritative_version := (
  SELECT EXISTS(
    SELECT 1
    FROM nx_config_item
    WHERE config_key = 'team.ui.F.pool.configVersion'
      AND status = 1
      AND is_deleted = 0
      AND TRIM(config_value) REGEXP '^[1-9][0-9]*$'
  )
);

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark,
   status, created_at, updated_at, is_deleted)
VALUES
  ('team.ui.F.pool.configVersion', '1', 'NUMBER', 'team', 'ADMIN',
   'F15 authoritative leadership-pool configuration version; bootstrap revision',
   1, NOW(), NOW(), 0),
  ('team.ui.F.pool.ratio', '__UNCONFIGURED__', 'STRING', 'team', 'ADMIN',
   'F15 fail-closed bootstrap sentinel; explicit audited operator value required',
   1, NOW(), NOW(), 0),
  ('team.ui.F.pool.unlockVRank', '__UNCONFIGURED__', 'STRING', 'team', 'ADMIN',
   'F15 fail-closed bootstrap sentinel; explicit audited operator value required',
   1, NOW(), NOW(), 0),
  ('team.ui.F.pool.monthlyCap', '__UNCONFIGURED__', 'STRING', 'team', 'ADMIN',
   'F15 fail-closed bootstrap sentinel; explicit audited operator value required',
   1, NOW(), NOW(), 0),
  ('team.ui.F.pool.settleCron', '__UNCONFIGURED__', 'STRING', 'team', 'ADMIN',
   'F15 fail-closed bootstrap sentinel; explicit audited operator value required',
   1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  config_value = IF(@f15_has_authoritative_version = 1
                    AND status = 1 AND is_deleted = 0
                    AND NULLIF(TRIM(config_value), '') IS NOT NULL,
                    config_value, VALUES(config_value)),
  value_type = IF(@f15_has_authoritative_version = 1
                  AND status = 1 AND is_deleted = 0, value_type, VALUES(value_type)),
  config_group = IF(@f15_has_authoritative_version = 1
                    AND status = 1 AND is_deleted = 0, config_group, VALUES(config_group)),
  visibility = IF(@f15_has_authoritative_version = 1
                  AND status = 1 AND is_deleted = 0, visibility, VALUES(visibility)),
  remark = IF(@f15_has_authoritative_version = 1
              AND status = 1 AND is_deleted = 0, remark, VALUES(remark)),
  status = 1,
  updated_at = NOW(),
  is_deleted = 0;

COMMIT;
