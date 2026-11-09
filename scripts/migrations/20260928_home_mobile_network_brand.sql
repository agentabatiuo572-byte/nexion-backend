-- The App home grid reads this PC-managed E5 display name from the backend.
-- Update only the original seed value; preserve operator-edited names.
UPDATE nx_compute_datacenter
   SET display_name = 'UVEL Mobile Network',
       updated_by = 'migration:home-mobile-network-brand'
 WHERE dc_location = 'User device'
   AND is_deleted = 0
   AND updated_by = 'system:home-grid-metadata'
   AND BINARY display_name = BINARY 'NexGrid Mobile Network';
