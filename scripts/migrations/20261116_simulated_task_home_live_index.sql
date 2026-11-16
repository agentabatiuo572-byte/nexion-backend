-- R22 current-task home lookup. Check the existing index shape before applying.
-- Keep this index VISIBLE while the application uses FORCE INDEX by this name.
ALTER TABLE nx_simulated_task
  ADD INDEX idx_sim_task_home_live_cov
    (status, due_at, task_no, user_id, user_device_id, generation, instance_no, started_at) VISIBLE,
  ALGORITHM=INPLACE,
  LOCK=NONE;
