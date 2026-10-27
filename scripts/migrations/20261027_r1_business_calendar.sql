-- R-1 全局业务时区：版本化业务日历。
-- 存储时间仍是北京时间（UTC+8）；本表只决定日/周/月按哪个时区切分。
-- LEGACY 轨 = 北京时间；GROWTH_TEAM 轨 = 北京时间，2026-10-19 起越南时间（与 VietnamBusinessPeriod 一致）。
-- 超管在 A 域设置的新版本写 GLOBAL 轨，对所有轨生效；已结算周期保持原口径。
-- effective_storage_at：生效瞬间的存储时间（各相关时区「生效日 0 点」中最早的那个）。
-- 幂等：建表 IF NOT EXISTS，种子按 track + effective_date 去重。

CREATE TABLE IF NOT EXISTS nx_business_calendar (
  id BIGINT NOT NULL AUTO_INCREMENT,
  track VARCHAR(32) NOT NULL,
  zone_id VARCHAR(64) NOT NULL,
  country_code VARCHAR(8) NULL,
  offset_minutes INT NOT NULL,
  effective_date DATE NOT NULL,
  effective_storage_at DATETIME NOT NULL,
  operator VARCHAR(64) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_business_calendar_storage (track, is_deleted, effective_storage_at),
  KEY idx_business_calendar_date (track, is_deleted, effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO nx_business_calendar (track, zone_id, country_code, offset_minutes, effective_date, effective_storage_at, operator, reason, created_at, updated_at, is_deleted)
SELECT 'LEGACY', 'Asia/Shanghai', 'CN', 480, '1970-01-01', '1970-01-01 00:00:00', 'migration:r1', '历史口径：北京时间', NOW(), NOW(), 0
 WHERE NOT EXISTS (SELECT 1 FROM nx_business_calendar WHERE track='LEGACY' AND effective_date='1970-01-01' AND is_deleted=0);

INSERT INTO nx_business_calendar (track, zone_id, country_code, offset_minutes, effective_date, effective_storage_at, operator, reason, created_at, updated_at, is_deleted)
SELECT 'GROWTH_TEAM', 'Asia/Shanghai', 'CN', 480, '1970-01-01', '1970-01-01 00:00:00', 'migration:r1', '历史口径：北京时间', NOW(), NOW(), 0
 WHERE NOT EXISTS (SELECT 1 FROM nx_business_calendar WHERE track='GROWTH_TEAM' AND effective_date='1970-01-01' AND is_deleted=0);

INSERT INTO nx_business_calendar (track, zone_id, country_code, offset_minutes, effective_date, effective_storage_at, operator, reason, created_at, updated_at, is_deleted)
SELECT 'GROWTH_TEAM', 'Asia/Ho_Chi_Minh', 'VN', 420, '2026-10-19', '2026-10-19 00:00:00', 'migration:r1', '增长与团队自 2026-10-19 起按越南时间切分', NOW(), NOW(), 0
 WHERE NOT EXISTS (SELECT 1 FROM nx_business_calendar WHERE track='GROWTH_TEAM' AND effective_date='2026-10-19' AND is_deleted=0);
