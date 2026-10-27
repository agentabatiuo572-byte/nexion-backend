-- App runtime DDL (copied verbatim from src/main/java mappers) for tables absent after startup.

-- nx_admin_device_order: src/main/java/ffdd/opsconsole/device/mapper/DeviceCatalogMapper.java
CREATE TABLE IF NOT EXISTS nx_admin_device_order (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              order_no VARCHAR(64) NOT NULL,
              user_no VARCHAR(64) NOT NULL,
              sku_id VARCHAR(64) DEFAULT NULL,
              sku_name VARCHAR(128) NOT NULL,
              amount DECIMAL(18,4) NOT NULL DEFAULT 0,
              state VARCHAR(32) NOT NULL DEFAULT 'created',
              dc_location VARCHAR(128) DEFAULT NULL,
              age_text VARCHAR(32) NOT NULL DEFAULT '',
              ordered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_device_order (order_no),
              KEY idx_admin_device_order_state (state,is_deleted),
              KEY idx_admin_device_order_user (user_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
