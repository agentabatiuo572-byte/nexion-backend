-- I2 Nova non-social producer closure.
-- One receipt fence is shared by all nine business-event adapters.

USE nexion;

CREATE TABLE IF NOT EXISTS nx_nova_business_event_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel_key VARCHAR(64) NOT NULL,
  source_event_id VARCHAR(64) NOT NULL,
  event_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NOT NULL DEFAULT '',
  notification_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_nova_business_event (channel_key, source_event_id),
  KEY idx_nova_business_event_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The canonical adapter contracts are intentionally documentary here. Source
-- domains must emit these exact server-authoritative A4 facts; I2 never creates
-- substitute eligibility from a nearby event.
--
-- welcome         <- auth.register_completed              (targeted)
-- market          <- market.curve_advanced                (broadcast)
-- upgrade         <- device.upgrade_recommended           (targeted)
-- dailySummary    <- earnings.credited                    (targeted)
-- tradein         <- tradein.eligible                      (targeted)
-- eventClaim      <- event.reward_claimable               (targeted)
-- wrapped         <- nova.wrapped_ready                    (targeted)
-- taskLockMonthly <- quest.monthly_lock_ready              (targeted)
-- quest           <- quest.grace_started / quest.expired /
--                    quest.weekly_refreshed                (targeted)
