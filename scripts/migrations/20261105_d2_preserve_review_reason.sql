SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME='d2_pre_freeze_reason')=0,
 'ALTER TABLE nx_withdrawal_order ADD COLUMN d2_pre_freeze_reason VARCHAR(512) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Recover only the original strong-review classification proved by submission audit.
-- A non-NULL snapshot (including '') belongs to the new freeze lifecycle.
UPDATE nx_withdrawal_order w
   SET w.d2_pre_freeze_reason = IF(w.status = 'FROZEN', 'A3_STRONG_REVIEW_THRESHOLD', w.d2_pre_freeze_reason),
       w.failure_reason = IF(w.status = 'FROZEN', w.failure_reason, 'A3_STRONG_REVIEW_THRESHOLD'),
       w.updated_at = w.updated_at
 WHERE w.is_deleted = 0
   AND w.c2_frozen_by_user_status = 0
   AND ((w.status = 'FROZEN' AND w.d2_previous_status IN ('REVIEW_PENDING', 'REVIEWING')
         AND w.d2_pre_freeze_reason IS NULL)
        OR (w.status IN ('REVIEW_PENDING', 'REVIEWING') AND w.failure_reason IS NULL AND EXISTS (
            SELECT 1 FROM nx_audit_log review
             WHERE review.resource_type = 'WITHDRAWAL' AND review.resource_id = w.withdrawal_no
               AND review.action = 'D2_WITHDRAWAL_REVIEW_UNFREEZE' AND review.result = 'SUCCESS'
               AND review.is_deleted = 0)))
   AND EXISTS (
       SELECT 1 FROM nx_audit_log submitted
        WHERE submitted.resource_type = 'WITHDRAWAL' AND submitted.resource_id = w.withdrawal_no
          AND submitted.user_id = w.user_id AND submitted.actor_type = 'USER'
          AND submitted.action = 'D2_WITHDRAWAL_SUBMITTED' AND submitted.result = 'SUCCESS'
          AND submitted.is_deleted = 0
          AND JSON_EXTRACT(submitted.detail_json, '$.strong_review') = CAST('true' AS JSON));
