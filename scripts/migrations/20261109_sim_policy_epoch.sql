-- Append-only histories invalidate all instances atomically with the publication transaction.
CREATE TABLE IF NOT EXISTS nx_sim_policy_epoch (
  id TINYINT NOT NULL PRIMARY KEY,
  epoch BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO nx_sim_policy_epoch(id,epoch) VALUES(1,0);
DROP TRIGGER IF EXISTS nx_sim_policy_type_epoch_ai;
DROP TRIGGER IF EXISTS nx_sim_policy_product_epoch_ai;
DROP TRIGGER IF EXISTS nx_sim_policy_e3_epoch_ai;
DELIMITER $$
CREATE TRIGGER nx_sim_policy_type_epoch_ai AFTER INSERT ON nx_sim_task_type_version FOR EACH ROW
BEGIN
  UPDATE nx_sim_policy_epoch SET epoch=epoch+1 WHERE id=1;
END$$
CREATE TRIGGER nx_sim_policy_product_epoch_ai AFTER INSERT ON nx_sim_product_task_policy_version FOR EACH ROW
BEGIN
  UPDATE nx_sim_policy_epoch SET epoch=epoch+1 WHERE id=1;
END$$
CREATE TRIGGER nx_sim_policy_e3_epoch_ai AFTER INSERT ON nx_sim_e3_version FOR EACH ROW
BEGIN
  UPDATE nx_sim_policy_epoch SET epoch=epoch+1 WHERE id=1;
END$$
DELIMITER ;
