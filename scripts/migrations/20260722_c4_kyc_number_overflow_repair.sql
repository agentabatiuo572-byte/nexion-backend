-- C4 KYC number overflow repair.
-- Preserve the legacy zero-padded format for existing-size user ids while
-- retaining every digit for ids above eight digits. Existing profile rows and
-- KYC numbers are deliberately untouched; this migration only repairs future
-- account creation and is safe to run repeatedly.

DROP TRIGGER IF EXISTS trg_nx_user_kyc_profile;
CREATE TRIGGER trg_nx_user_kyc_profile
AFTER INSERT ON nx_user
FOR EACH ROW
INSERT INTO nx_kyc_profile
  (user_id,kyc_no,status,country,trigger_source,version,is_deleted)
VALUES
  (NEW.id,CONCAT('KYC-',IF(NEW.id < 100000000,LPAD(NEW.id,8,'0'),CAST(NEW.id AS CHAR))),UPPER(COALESCE(NULLIF(NEW.kyc_status,''),'NONE')),
   NEW.country_code,'REGISTRATION',0,0);
