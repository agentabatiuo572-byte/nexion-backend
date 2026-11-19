SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

SET @lc_g3_curve =
  '[{"dayIndex":0,"targetPrice":0.12000000,"pumpProbability":0.0500,"volatilityPct":2.0000},
    {"dayIndex":1,"targetPrice":0.12200000,"pumpProbability":0.0600,"volatilityPct":2.0000},
    {"dayIndex":2,"targetPrice":0.12400000,"pumpProbability":0.0700,"volatilityPct":2.2000},
    {"dayIndex":3,"targetPrice":0.12600000,"pumpProbability":0.0800,"volatilityPct":2.2000},
    {"dayIndex":4,"targetPrice":0.12800000,"pumpProbability":0.0900,"volatilityPct":2.4000},
    {"dayIndex":5,"targetPrice":0.12600000,"pumpProbability":0.0600,"volatilityPct":2.3000},
    {"dayIndex":6,"targetPrice":0.12400000,"pumpProbability":0.0500,"volatilityPct":2.1000}]';
SET @lc_g3_opening_price = JSON_UNQUOTE(JSON_EXTRACT(@lc_g3_curve, '$[0].targetPrice'));
SET @lc_g3_opening = NOT EXISTS (
  SELECT 1 FROM nx_config_item
   WHERE config_key IN ('wallet.nex_market.weekly_curve', 'wallet.nex_market.control.active_day_index',
                        'wallet.exchange.nex_usdt_price')
) AND NOT EXISTS (
  SELECT 1 FROM nx_price_index WHERE metric_code IN ('UVT','UVT_USDT')
);

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('wallet.nex_market.weekly_curve', @lc_g3_curve,
   'JSON','wallet','ADMIN','G3 launch weekly curve',1,0),
  ('wallet.nex_market.cost_basis','0.085','NUMBER','wallet','ADMIN','G3 launch cost basis',1,0),
  ('wallet.nex_market.control.schedule','每日 00:00 Asia/Ho_Chi_Minh 自动推进','STRING','wallet','ADMIN','G3 launch daily schedule',1,0),
  ('wallet.nex_market.control.loop','循环','STRING','wallet','ADMIN','G3 launch seven-day loop',1,0),
  ('team.ui.F.binary.threshold','120','DECIMAL','team','ADMIN','F3 minimum paid volume per track',1,0),
  ('team.ui.F.binary.matchRate','5%','STRING','team','ADMIN','F3 smaller-track balance match rate',1,0),
  ('team.ui.F.binary.spillover','已启用','ENUM','team','ADMIN','F3 automatic placement',1,0),
  ('team.ui.F.binary.settlePeriod','每月','ENUM','team','ADMIN','F3 settlement cadence',1,0),
  ('team.ui.F.binary.residualPolicy','每月清零','ENUM','team','ADMIN','F3 unmatched-volume policy',1,0),
  ('team.ui.F.binary.gvResetCron','每月 1 日 00:00 UTC','STRING','team','ADMIN','F3 natural-month GV reset boundary',1,0),
  ('team.ui.F.binary.paused','false','BOOLEAN','team','ADMIN','F3 settlement pause',1,0)
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

-- The first planned frame supplies the opening valuation and the next advance starts at D2.
INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT 'wallet.nex_market.control.active_day_index', '0',
       'NUMBER','wallet','ADMIN','G3 opening curve day',1,0
WHERE @lc_g3_opening
UNION ALL
SELECT 'wallet.exchange.nex_usdt_price', @lc_g3_opening_price,
       'NUMBER','wallet','ADMIN','G3 opening UVT price',1,0
WHERE @lc_g3_opening;

INSERT INTO nx_price_index
  (metric_code, metric_label, unit_label, price_usdt, delta_percent, sparkline, status, sampled_at, is_deleted)
SELECT 'UVT_USDT','UVT / USDT','per UVT',@lc_g3_opening_price,0,
       JSON_EXTRACT(@lc_g3_curve, '$[*].targetPrice'),'ACTIVE',NOW(),0
WHERE @lc_g3_opening;

-- HDPAY reads the shared singleton. The retired manual VietQR channel is independent.
INSERT INTO nx_vietqr_config
  (id, tolerance_vnd, grace_minutes, per_tx_limit_usd,
   trc20_confirmations, erc20_confirmations, bep20_confirmations, rotation_strategy, version, is_deleted)
VALUES (1,1000,10,5000,20,12,15,'ROUND_ROBIN',0,0)
ON DUPLICATE KEY UPDATE id = nx_vietqr_config.id;

-- H5 needs an authoritative DAILY mission before the App can check in.
INSERT INTO nx_mission
  (mission_code, mission_name, mission_type, reward_points, status, is_deleted)
VALUES ('DAILY_CHECK_IN','Daily Check-in','DAILY',0,1,0)
ON DUPLICATE KEY UPDATE mission_code = nx_mission.mission_code;

COMMIT;
