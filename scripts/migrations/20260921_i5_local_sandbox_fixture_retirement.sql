-- I5 local-sandbox fixture retirement. Rerunnable; touches only fixture-authored rows.
--
-- Root cause it repairs (zentao #60): the retired RiskDisclosureLocalSandboxInitializer
-- (@Profile("local-sandbox"), guarded by isStrictLocalSandbox()) wrote a
-- LOCAL-SANDBOX jurisdiction into nx_disclosure_jurisdiction with
-- version_label='v-local-1', plus a matching draft and seven chapters. That bean is
-- only instantiated under the isolated local-sandbox profile — but if a database is
-- ever shared with, or later reused by, a normal environment, those rows persist.
-- The fixture version 'v-local-1' is not part of any canonical provisioning
-- migration (20260920_i5_published_disclosure_provisioning.sql covers CN/US/EU/SG/SBV
-- only), so the leftover row is a published matrix entry pointing at a version the
-- canonical set does not contain. PC I5 reports it as
-- 「引用异常，未找到矩阵引用的披露版本」 and the App risk-disclosure page fails with
-- RISK_DISCLOSURE_PUBLISHED_VERSION_NOT_FOUND.
--
-- Scope: retire the group only while all four I5 tables for this jurisdiction
-- still belong to the fixture. Partial operator takeover leaves the whole group
-- untouched for review. No content is deleted
-- (soft delete only, so the audit trail and any ack records keep their referent).
SET NAMES utf8mb4;

START TRANSACTION;

-- Hold this jurisdiction's rows stable while ownership is checked and retired.
SELECT id FROM nx_disclosure_jurisdiction_catalog WHERE jurisdiction_code = 'LOCAL-SANDBOX' FOR UPDATE;
SELECT id FROM nx_disclosure_jurisdiction WHERE jurisdiction_code = 'LOCAL-SANDBOX' FOR UPDATE;
SELECT id FROM nx_disclosure_draft WHERE jurisdiction_code = 'LOCAL-SANDBOX' FOR UPDATE;
SELECT id FROM nx_disclosure_chapter WHERE jurisdiction_code = 'LOCAL-SANDBOX' FOR UPDATE;

SET @i5_fixture_owned = (
  SELECT EXISTS (SELECT 1 FROM nx_disclosure_jurisdiction
                  WHERE jurisdiction_code = 'LOCAL-SANDBOX' AND version_label = 'v-local-1'
                    AND last_operator = 'local-sandbox:risk-disclosure-fixture' AND is_deleted = 0)
     AND EXISTS (SELECT 1 FROM nx_disclosure_jurisdiction_catalog
                  WHERE jurisdiction_code = 'LOCAL-SANDBOX'
                    AND last_operator = 'local-sandbox:risk-disclosure-fixture' AND is_deleted = 0)
     AND NOT EXISTS (SELECT 1 FROM nx_disclosure_jurisdiction
                      WHERE jurisdiction_code = 'LOCAL-SANDBOX'
                        AND (version_label <> 'v-local-1' OR COALESCE(last_operator, '') <> 'local-sandbox:risk-disclosure-fixture'))
     AND NOT EXISTS (SELECT 1 FROM nx_disclosure_jurisdiction_catalog
                      WHERE jurisdiction_code = 'LOCAL-SANDBOX'
                        AND COALESCE(last_operator, '') <> 'local-sandbox:risk-disclosure-fixture')
     AND NOT EXISTS (SELECT 1 FROM nx_disclosure_draft
                      WHERE jurisdiction_code = 'LOCAL-SANDBOX'
                        AND (version_label <> 'v-local-1' OR COALESCE(last_operator, '') <> 'local-sandbox:risk-disclosure-fixture'))
     AND NOT EXISTS (SELECT 1 FROM nx_disclosure_chapter
                      WHERE jurisdiction_code = 'LOCAL-SANDBOX'
                        AND (version_label <> 'v-local-1' OR COALESCE(last_operator, '') <> 'local-sandbox:risk-disclosure-fixture'))
);

-- 1. Chapters authored by the fixture.
UPDATE nx_disclosure_chapter
   SET is_deleted = 1, updated_at = NOW()
 WHERE jurisdiction_code = 'LOCAL-SANDBOX'
   AND version_label = 'v-local-1'
   AND last_operator = 'local-sandbox:risk-disclosure-fixture'
   AND @i5_fixture_owned = 1
   AND is_deleted = 0;

-- 2. The fixture draft.
UPDATE nx_disclosure_draft
   SET is_deleted = 1, updated_at = NOW()
 WHERE jurisdiction_code = 'LOCAL-SANDBOX'
   AND version_label = 'v-local-1'
   AND last_operator = 'local-sandbox:risk-disclosure-fixture'
   AND @i5_fixture_owned = 1
   AND is_deleted = 0;

-- 3. The published matrix row. This is the row that makes PC report a dangling
--    version reference, so it must leave the read path. Keyed on the fixture's own
--    version label as well, so a jurisdiction row an operator later repointed at a
--    real version is preserved.
UPDATE nx_disclosure_jurisdiction
   SET is_deleted = 1, updated_at = NOW()
 WHERE jurisdiction_code = 'LOCAL-SANDBOX'
   AND version_label = 'v-local-1'
   AND last_operator = 'local-sandbox:risk-disclosure-fixture'
   AND @i5_fixture_owned = 1
   AND is_deleted = 0;

-- 4. The jurisdiction catalog entry. Without this, resolveJurisdiction()'s
--    activeJurisdictions map would still admit a code that no longer has a usable
--    matrix row.
UPDATE nx_disclosure_jurisdiction_catalog
   SET status = 'ARCHIVED', updated_at = NOW()
 WHERE jurisdiction_code = 'LOCAL-SANDBOX'
   AND last_operator = 'local-sandbox:risk-disclosure-fixture'
   AND @i5_fixture_owned = 1
   AND is_deleted = 0;

COMMIT;
