CREATE TABLE IF NOT EXISTS nx_device_slot_replacement (
    old_device_id BIGINT NOT NULL,
    new_device_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (old_device_id),
    UNIQUE KEY uk_slot_replacement_new_device (new_device_id),
    KEY ix_slot_replacement_user (user_id)
) ENGINE=InnoDB;
