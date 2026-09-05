INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted, created_at, updated_at)
SELECT 'store.bundle.discount.2.rate', '0.05', 'NUMBER', 'store', 'ADMIN',
       'E1 server-authoritative discount for two-item bundles', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key='store.bundle.discount.2.rate');

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted, created_at, updated_at)
SELECT 'store.bundle.discount.3.rate', '0.08', 'NUMBER', 'store', 'ADMIN',
       'E1 server-authoritative discount for three-item bundles', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key='store.bundle.discount.3.rate');

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted, created_at, updated_at)
SELECT 'store.bundle.discount.4plus.rate', '0.12', 'NUMBER', 'store', 'ADMIN',
       'E1 server-authoritative discount for four-or-more-item bundles', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key='store.bundle.discount.4plus.rate');

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted, created_at, updated_at)
SELECT 'store.bundle.discount.version', '1', 'NUMBER', 'store', 'ADMIN',
       'E1 bundle discount policy CAS version', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM nx_config_item WHERE config_key='store.bundle.discount.version');
