-- Repair only untouched I1 catalog seed rows. Hex UTF-8 literals keep this migration client-encoding independent.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

UPDATE nx_content_copy_version_option
SET name = CASE version_key
      WHEN 'v1' THEN CONVERT(0xE78988E69CAC207631 USING utf8mb4)
      WHEN 'v2' THEN CONVERT(0xE78988E69CAC207632 USING utf8mb4)
      WHEN 'v3' THEN CONVERT(0xE78988E69CAC207633 USING utf8mb4)
      WHEN 'v4' THEN CONVERT(0xE78988E69CAC207634 USING utf8mb4)
      WHEN 'v5' THEN CONVERT(0xE78988E69CAC207635 USING utf8mb4)
    END,
    description = CASE version_key
      WHEN 'v1' THEN CONVERT(0xE5889DE5A78BE69687E6A188E78988E69CAC USING utf8mb4)
      WHEN 'v2' THEN CONVERT(0xE7ACACE4BA8CE78988E69687E6A188 USING utf8mb4)
      WHEN 'v3' THEN CONVERT(0xE7ACACE4B889E78988E69687E6A188 USING utf8mb4)
      WHEN 'v4' THEN CONVERT(0xE7ACACE59B9BE78988E69687E6A188 USING utf8mb4)
      WHEN 'v5' THEN CONVERT(0xE7ACACE4BA94E78988E69687E6A188 USING utf8mb4)
    END
WHERE version_key IN ('v1', 'v2', 'v3', 'v4', 'v5')
  AND is_deleted = 0
  AND revision = 1
  AND last_operator IN ('migration', 'schema');
