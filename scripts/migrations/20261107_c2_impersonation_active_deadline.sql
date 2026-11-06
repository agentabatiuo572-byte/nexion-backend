-- The only session creator writes created_at in the DB storage timezone.
-- TTL is fixed at creation; there is no extension/renewal command.
-- Correct only overlong ACTIVE deadlines, preserving every terminal audit row.
UPDATE nx_user_impersonation_session
   SET expires_at = DATE_ADD(created_at, INTERVAL ttl_minutes MINUTE),
       updated_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
 WHERE is_deleted = 0
   AND status = 'ACTIVE'
   AND ttl_minutes IN (5, 10, 15, 30)
   AND expires_at > DATE_ADD(created_at, INTERVAL ttl_minutes MINUTE);
