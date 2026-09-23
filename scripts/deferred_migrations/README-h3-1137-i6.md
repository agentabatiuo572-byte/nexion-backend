# I6 migrations deferred during H3 1137 recovery

These two SQL files were **not registered** when backend build #150 failed. They were moved out of `scripts/migrations` without editing their SQL so the H3 recovery build has exactly one pending automatic migration:

- `20260923_i6_invalid_published_key_retirement.sql`
- `20260924_i6_legacy_course_retirement.sql`

Their original Git blob SHA256 values are `69473dd48edf1bea73343f4899e0317fd10de089d1a5fe5997181d88430b6f27` and `de928f9ab261666a5b0a0a6e379114bf3e5e8a4f734e15abefc85827260641eb`, respectively. The contract test checks the deferred bytes against these values.

Restore both original files to `scripts/migrations` and the corresponding entries in `scripts/apply_startup_schema_migrations.ps1` in a separate #152 candidate only after the H3-only release is healthy and `20260923_h3_weekly_event_gate.sql` is registered. Before that release, replay both I6 files against an isolated clone of the current test database and verify affected row sets, `I6_INVALID_KEY_STILL_PUBLIC` and `I6_LEGACY_COURSE_STILL_PUBLIC` postconditions, and a second-run no-op. Review autocommit UPDATE boundaries: a later failure can leave earlier I6 changes committed while its file is still unregistered. Do not use the H3 1137 recovery tool for I6.

For #151, the migration catalog delta from the 277 registered entries must be exactly the corrected H3 SQL. For #152, verify the two I6 SQL blobs match their pre-deferral Git blobs before publishing them; do not rewrite either during H3 recovery.
