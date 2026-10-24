-- H4 executable rules. Existing presentation data is retained; new rules are opt-in.
CREATE TABLE IF NOT EXISTS nx_growth_event_rule (
  event_code VARCHAR(64) PRIMARY KEY, kind VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1, enabled TINYINT NOT NULL DEFAULT 0,
  starts_at DATETIME(6) NULL, ends_at DATETIME(6) NULL, activated_at DATETIME(6) NULL,disabled_at DATETIME(6) NULL,
  rules_json JSON NOT NULL,
  spent_nex DECIMAL(18,6) NOT NULL DEFAULT 0, spent_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  spent_devices INT NOT NULL DEFAULT 0, spent_tickets INT NOT NULL DEFAULT 0,
  settled_at DATETIME(6) NULL,settlement_hash VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_event_claim (
  event_code VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,claim_key VARCHAR(96) NOT NULL,
  status VARCHAR(24) NOT NULL,reward_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(event_code,user_id,claim_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_event_holding (
  event_code VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,eligible_since DATETIME(6) NULL,
  qualified_at DATETIME(6) NULL,updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(event_code,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_event_pool_result (
  event_code VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,region_name VARCHAR(96) NOT NULL DEFAULT '',
  volume_usdt DECIMAL(24,6) NOT NULL DEFAULT 0,reward_usdt DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'READY',created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(event_code,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_event_region (
  event_code VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,region_name VARCHAR(96) NOT NULL,
  country_iso VARCHAR(2) NOT NULL,joined_at DATETIME(6) NOT NULL,
  PRIMARY KEY(event_code,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_growth_event_reminder (
  event_code VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,notified_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(event_code,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_growth_event_rule(event_code,kind,rules_json) VALUES
 ('evt-pro-upgrade-7d','discount',JSON_OBJECT()),
 ('evt-refer-5-get-pro','referral',JSON_OBJECT()),
 ('evt-weekend-double-nex','boost',JSON_OBJECT()),
 ('evt-regional-pk','regional',JSON_OBJECT()),
 ('evt-spring-spin','wheel',JSON_OBJECT()),
 ('evt-reinvest-bonus','boost',JSON_OBJECT()),
 ('evt-onboarding-7d','onboarding',JSON_OBJECT()),
 ('evt-black-friday','seasonal',JSON_OBJECT()),
 ('evt-nex-holders-share','holding',JSON_OBJECT()),
 ('evt-anniversary-spin','wheel',JSON_OBJECT());
INSERT IGNORE INTO nx_event_quest(quest_code,quest_name,description,target_type,target_value,reward_type,reward_amount,reward_name,cta_href,status,sort_order) VALUES
 ('evt-pro-upgrade-7d','Pro Flash Upgrade','Upgrade offer and Genesis raffle tickets.','discount',0,'COMPOSITE',0,'Upgrade offer','/pages/store/store',0,10),
 ('evt-refer-5-get-pro','Refer 5 · Win a Pro','Invite direct friends within the activity window for a Pro device.','referral',5,'DEVICE',0,'Pro device','',0,20),
 ('evt-weekend-double-nex','Weekend Double NEX','Weekend check-ins receive the configured reward multiplier.','boost',0,'NEX',0,'Check-in bonus','/pages/daily/daily',0,30),
 ('evt-regional-pk','Regional Race','Join your region and compete by valid paid order volume.','regional',0,'USDT',0,'Regional prize pool','',0,40),
 ('evt-spring-spin','Daily Lucky Spin','One free spin per UTC day.','wheel',0,'NEX',0,'Wheel prize pool','',0,50),
 ('evt-reinvest-bonus','Re-invest Bonus Week','Qualifying reinvestments receive additional Genesis raffle tickets.','boost',1,'GENESIS_TICKET',0,'Genesis raffle tickets','/pages/me/wallet-repurchase',0,60),
 ('evt-onboarding-7d','New Pilot · 7-Day Boost','Reach the configured rank in your first week for NEX and a streak saver.','onboarding',2,'COMPOSITE',0,'NEX and streak saver','',0,70),
 ('evt-black-friday','Black Friday','Seasonal offer with published purchase conditions.','seasonal',0,'COMPOSITE',0,'Seasonal offer','/pages/store/store',0,80),
 ('evt-nex-holders-share','NEX Holders Share','Maintain the required NEX balance continuously to share the USDT pool.','holding',0,'USDT',0,'USDT prize pool','',0,90),
 ('evt-anniversary-spin','Anniversary Mega Spin','View your recorded awards from this activity.','wheel',0,'NEX',0,'Recorded wheel awards','',0,100);
-- Initial observations begin when an operator enables a campaign. Never backfill a holding period.
-- This trigger observes every wallet NEX change, including debit/credit in the same second.
DELIMITER $$
CREATE TRIGGER IF NOT EXISTS nx_wallet_h4_holding_observation
AFTER UPDATE ON nx_user_wallet FOR EACH ROW
BEGIN
  DECLARE event_enabled TINYINT DEFAULT 0;
  DECLARE event_activated DATETIME(6) DEFAULT NULL;
  DECLARE event_start DATETIME(6) DEFAULT NULL;
  DECLARE event_end DATETIME(6) DEFAULT NULL;
  DECLARE minimum_nex DECIMAL(18,6) DEFAULT NULL;
  DECLARE observed TINYINT DEFAULT 0;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET observed=0;
  IF NOT (NEW.nex_available <=> OLD.nex_available)
     OR NOT (NEW.is_deleted <=> OLD.is_deleted) OR NOT (NEW.sandbox <=> OLD.sandbox) THEN
    -- Current read of one immutable policy, never a scan/lock of unrelated activity rules.
    -- Wallet row is already locked by UPDATE; all H4 policy writers acquire wallets first.
    SELECT enabled,activated_at,starts_at,ends_at,
           CAST(JSON_UNQUOTE(JSON_EXTRACT(rules_json,'$.minNex')) AS DECIMAL(18,6))
      INTO event_enabled,event_activated,event_start,event_end,minimum_nex
      FROM nx_growth_event_rule WHERE event_code='evt-nex-holders-share' FOR SHARE;
    IF event_activated IS NOT NULL AND event_end>UTC_TIMESTAMP(6) THEN
      IF NEW.is_deleted=0 AND NEW.sandbox=0 THEN
        SELECT 1 INTO observed FROM nx_growth_event_holding
          WHERE event_code='evt-nex-holders-share' AND user_id=NEW.user_id FOR UPDATE;
        IF event_enabled=1 OR observed=1 THEN
          INSERT INTO nx_growth_event_holding(event_code,user_id,eligible_since,updated_at)
          VALUES('evt-nex-holders-share',NEW.user_id,
            CASE WHEN NEW.nex_available>=minimum_nex THEN GREATEST(event_start,event_activated,UTC_TIMESTAMP(6)) ELSE NULL END,
            UTC_TIMESTAMP(6))
          ON DUPLICATE KEY UPDATE eligible_since=
            CASE WHEN VALUES(eligible_since) IS NULL THEN NULL
              ELSE COALESCE(eligible_since,VALUES(eligible_since)) END,updated_at=UTC_TIMESTAMP(6);
        END IF;
      ELSE
        UPDATE nx_growth_event_holding SET eligible_since=NULL,updated_at=UTC_TIMESTAMP(6)
          WHERE event_code='evt-nex-holders-share' AND user_id=NEW.user_id;
      END IF;
    END IF;
  END IF;
END$$
DELIMITER ;
