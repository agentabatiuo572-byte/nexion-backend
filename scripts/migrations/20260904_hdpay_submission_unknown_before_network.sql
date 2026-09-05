-- A legacy PENDING row cannot prove whether an older process reached HDPay.
-- Recover it by provider query under the same merchant order id; never resubmit.
UPDATE nx_hdpay_payin_order
   SET submission_status='SUBMIT_UNKNOWN',
       last_error_code='HDPAY_LEGACY_PENDING_RECOVERY',
       version=version+1,
       updated_at=NOW()
 WHERE submission_status='PENDING';
