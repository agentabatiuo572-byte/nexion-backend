SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- `User device` is the canonical dc_location written by phone/onboarding
-- activation. Give that persisted location PC-manageable E5 display metadata
-- so the App can render a name and city from Java instead of local constants.
INSERT INTO nx_compute_datacenter(
  dc_location, region_label, location, display_name, status, sort_order, updated_by, is_deleted
) VALUES(
  'User device', 'Global Mobile Compute', 'Global', 'NexGrid Mobile Network',
  'active', 10, 'system:home-grid-metadata', 0
)
ON DUPLICATE KEY UPDATE
  updated_at = IF(is_deleted = 0 AND (
    region_label IS NULL OR TRIM(region_label)='' OR
    location IS NULL OR TRIM(location)='' OR
    display_name IS NULL OR TRIM(display_name)='' OR
    status IS NULL OR TRIM(status)=''
  ), NOW(), updated_at),
  region_label = IF(is_deleted = 0 AND (region_label IS NULL OR TRIM(region_label)=''), VALUES(region_label), region_label),
  location = IF(is_deleted = 0 AND (location IS NULL OR TRIM(location)=''), VALUES(location), location),
  display_name = IF(is_deleted = 0 AND (display_name IS NULL OR TRIM(display_name)=''), VALUES(display_name), display_name),
  status = IF(is_deleted = 0 AND (status IS NULL OR TRIM(status)=''), VALUES(status), status);
