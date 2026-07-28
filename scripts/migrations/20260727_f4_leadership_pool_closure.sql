-- F4 closure: durable weekly CAS mutex for leadership-pool settlement.
USE nexion;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_team_f4_settlement_mutex (
  id BIGINT NOT NULL AUTO_INCREMENT,
  week_code INT NOT NULL COMMENT 'ISO YEARWEEK, for example 202631',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_f4_settlement_week (week_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F4 weekly leadership-pool settlement CAS mutex';
