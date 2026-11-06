SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_daily_active (
    biz_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    first_seen_at DATETIME NOT NULL,
    PRIMARY KEY (biz_date, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
