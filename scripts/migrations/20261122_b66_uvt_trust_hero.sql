-- B66 / BUG-615 item 6: the July seed wrote the retired brand into the UVT explainer hero.
-- The App and Lite render the published nexNarrative fields verbatim, so this forward step
-- publishes the next version with only the hero fields that still equal the exact July seed
-- value moved from NEX to UVT. Operator-edited values, every other field, the archived version
-- content and unpublished drafts keep their current contract. Re-runs are no-ops once nothing
-- matches. See docs/PRD/UVT-全库品牌与门槛规范-20261028.md (B66).
SET NAMES utf8mb4;

START TRANSACTION;

SET @b66_operator = 'migration:b66-uvt-hero';
SET @b66_section = 'nexNarrative';
SET @b66_en_old = 'NEX connects AI with digital economic infrastructure.';
SET @b66_en_new = 'UVT connects AI with digital economic infrastructure.';
SET @b66_zh_old = 'NEX 连接 AI 与数字经济基础设施。';
SET @b66_zh_new = 'UVT 连接 AI 与数字经济基础设施。';
SET @b66_vi_old = 'NEX kết nối AI với hạ tầng kinh tế số.';
SET @b66_vi_new = 'UVT kết nối AI với hạ tầng kinh tế số.';

-- Effective projection: the section pointer and the version it names must both be published.
-- A fresh install keeps its DRAFT (20261120_lc_i4_trust_sections.sql) on the regular I4 flow.
SET @b66_published_label = (
  SELECT section_row.version_label
    FROM nx_trust_section section_row
   WHERE BINARY section_row.section_key = BINARY @b66_section
     AND section_row.is_deleted = 0
     AND BINARY section_row.status = BINARY 'PUBLISHED'
   LIMIT 1);
SET @b66_fields = (
  SELECT version_row.fields_json
    FROM nx_trust_section_version version_row
   WHERE BINARY version_row.section_key = BINARY @b66_section
     AND BINARY version_row.version_label = BINARY @b66_published_label
     AND version_row.is_deleted = 0
     AND BINARY version_row.status = BINARY 'PUBLISHED'
   LIMIT 1);

-- Exact per-field match: locate each seeded key, then compare the complete current value.
SET @b66_en_key = JSON_UNQUOTE(JSON_SEARCH(@b66_fields, 'one', 'hero.en', NULL, '$[*].key'));
SET @b66_zh_key = JSON_UNQUOTE(JSON_SEARCH(@b66_fields, 'one', 'hero.zh', NULL, '$[*].key'));
SET @b66_vi_key = JSON_UNQUOTE(JSON_SEARCH(@b66_fields, 'one', 'hero.vi', NULL, '$[*].key'));
SET @b66_en_path = IF(@b66_en_key IS NULL, NULL, REPLACE(@b66_en_key, '.key', '.value'));
SET @b66_zh_path = IF(@b66_zh_key IS NULL, NULL, REPLACE(@b66_zh_key, '.key', '.value'));
SET @b66_vi_path = IF(@b66_vi_key IS NULL, NULL, REPLACE(@b66_vi_key, '.key', '.value'));
-- The '$' fallback keeps every argument valid: IF() evaluates both branches, and the value
-- computed for a non-hit field is discarded because the IF returns the unchanged document.
SET @b66_en_hit = IF(@b66_en_path IS NOT NULL
      AND BINARY JSON_UNQUOTE(JSON_EXTRACT(@b66_fields, COALESCE(@b66_en_path, '$'))) = BINARY @b66_en_old, 1, 0);
SET @b66_zh_hit = IF(@b66_zh_path IS NOT NULL
      AND BINARY JSON_UNQUOTE(JSON_EXTRACT(@b66_fields, COALESCE(@b66_zh_path, '$'))) = BINARY @b66_zh_old, 1, 0);
SET @b66_vi_hit = IF(@b66_vi_path IS NOT NULL
      AND BINARY JSON_UNQUOTE(JSON_EXTRACT(@b66_fields, COALESCE(@b66_vi_path, '$'))) = BINARY @b66_vi_old, 1, 0);

-- The array keeps its order and other field values; only hit values move.
SET @b66_new_fields = @b66_fields;
SET @b66_new_fields = IF(@b66_en_hit = 1,
      JSON_SET(@b66_new_fields, COALESCE(@b66_en_path, '$'), @b66_en_new), @b66_new_fields);
SET @b66_new_fields = IF(@b66_zh_hit = 1,
      JSON_SET(@b66_new_fields, COALESCE(@b66_zh_path, '$'), @b66_zh_new), @b66_new_fields);
SET @b66_new_fields = IF(@b66_vi_hit = 1,
      JSON_SET(@b66_new_fields, COALESCE(@b66_vi_path, '$'), @b66_vi_new), @b66_new_fields);

-- Next numeric label above every existing one, so operator drafts keep their own labels.
-- REGEXP_LIKE takes character input on both sides with match type 'c' (case sensitive);
-- a binary operand pair raises ER_CHARACTER_SET_MISMATCH on MySQL 8.4.
SET @b66_next_no = (
  SELECT COALESCE(MAX(CAST(SUBSTRING(version_label, 2) AS UNSIGNED)), 0) + 1
    FROM nx_trust_section_version
   WHERE BINARY section_key = BINARY @b66_section
     AND is_deleted = 0
     AND REGEXP_LIKE(CONVERT(version_label USING utf8mb4), '^v[0-9]+$', 'c'));
SET @b66_next_label = CONCAT('v', @b66_next_no);
-- A2 boundary: while an approval ticket holds the same object lock the publish endpoint
-- refuses with 409 OBJECT_LOCKED_BY_A2, this step keeps the current published version and
-- every draft untouched, so the ticket's bound expectedVersion stays valid. An entry point that
-- runs this file again will recheck the lock. Managed migrations record the file once.
SET @b66_a2_locked = (
  SELECT COUNT(*) FROM nx_audit_object_lock active_lock
   WHERE active_lock.target_domain = 'I'
     AND active_lock.target_type = 'trust_section'
     AND active_lock.target_id = @b66_section
     AND active_lock.is_deleted = 0);
SET @b66_apply = IF(@b66_fields IS NOT NULL AND @b66_published_label IS NOT NULL
      AND @b66_en_hit + @b66_zh_hit + @b66_vi_hit > 0
      AND @b66_a2_locked = 0
      AND NOT EXISTS (
        SELECT 1 FROM nx_trust_section_version taken
         WHERE BINARY taken.section_key = BINARY @b66_section
           AND BINARY taken.version_label = BINARY @b66_next_label
           AND taken.is_deleted = 0), 1, 0);

-- Publish the next version with the same descriptor as the version it replaces.
INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, created_at, updated_at, is_deleted)
SELECT version_row.section_key, @b66_next_label, version_row.description, version_row.struct_text,
       @b66_new_fields, 'PUBLISHED', 1, @b66_operator, NOW(), NOW(), 0
  FROM nx_trust_section_version version_row
 WHERE @b66_apply = 1
   AND BINARY version_row.section_key = BINARY @b66_section
   AND BINARY version_row.version_label = BINARY @b66_published_label
   AND version_row.is_deleted = 0
   AND BINARY version_row.status = BINARY 'PUBLISHED'
 LIMIT 1;

-- Mirror publishTrustSectionVersion: every other published row becomes SUPERSEDED, keeping
-- its own fields_json untouched so the archived version stays readable as history.
UPDATE nx_trust_section_version
   SET status = 'SUPERSEDED', updated_at = NOW()
 WHERE @b66_apply = 1
   AND BINARY section_key = BINARY @b66_section
   AND is_deleted = 0
   AND BINARY status = BINARY 'PUBLISHED'
   AND BINARY version_label <> BINARY @b66_next_label;

-- The pointer moves to the new label. description/struct_text stay untouched: BUG-426 already
-- moved the visible descriptor to UVT and the version rows still carry the archived wording.
UPDATE nx_trust_section
   SET version_label = @b66_next_label,
       status = 'PUBLISHED',
       last_change = DATE_FORMAT(NOW(), '%m-%d'),
       last_operator = @b66_operator,
       updated_at = NOW()
 WHERE @b66_apply = 1
   AND BINARY section_key = BINARY @b66_section
   AND BINARY version_label = BINARY @b66_published_label
   AND is_deleted = 0;

-- Keep the I4 field table aligned for exactly the keys that changed (values only).
UPDATE nx_trust_section_field
   SET field_value = @b66_en_new, last_operator = @b66_operator, updated_at = NOW()
 WHERE @b66_apply = 1 AND @b66_en_hit = 1
   AND BINARY section_key = BINARY @b66_section
   AND BINARY field_key = BINARY 'hero.en'
   AND is_deleted = 0
   AND BINARY field_value = BINARY @b66_en_old;
UPDATE nx_trust_section_field
   SET field_value = @b66_zh_new, last_operator = @b66_operator, updated_at = NOW()
 WHERE @b66_apply = 1 AND @b66_zh_hit = 1
   AND BINARY section_key = BINARY @b66_section
   AND BINARY field_key = BINARY 'hero.zh'
   AND is_deleted = 0
   AND BINARY field_value = BINARY @b66_zh_old;
UPDATE nx_trust_section_field
   SET field_value = @b66_vi_new, last_operator = @b66_operator, updated_at = NOW()
 WHERE @b66_apply = 1 AND @b66_vi_hit = 1
   AND BINARY section_key = BINARY @b66_section
   AND BINARY field_key = BINARY 'hero.vi'
   AND is_deleted = 0
   AND BINARY field_value = BINARY @b66_vi_old;

COMMIT;
