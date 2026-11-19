-- Launch reward defaults are independent of mission and binding repairs.
START TRANSACTION;

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('growth.quest.day_one.tri_reward','30 / 10 / 0 UVT','STRING','growth','ADMIN','H3 launch Day-One reward ladder',1,0),
  ('growth.quest.day_one.eligibility_hours','72','NUMBER','growth','ADMIN','H3 Day-One eligibility window',1,0)
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

COMMIT;
