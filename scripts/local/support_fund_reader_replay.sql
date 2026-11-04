-- The normal install/replay still runs the immutable reader migration directly.
-- An interrupted 3dc5196e rebuild needs its private receipt even if the reader is absent.
SELECT IF(EXISTS(SELECT 1 FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()
 AND (ROUTINE_NAME='nx_local_h4_reader_rebuild' OR (ROUTINE_NAME='nx_support_fund_schema_ready_v1'
 AND (BINARY DEFINER<>BINARY CURRENT_USER() OR SHA2(ROUTINE_DEFINITION,256) IN
 ('5deb448951d7aef59b30ec43c7ea8916a359de0e0192cd77755cb15429764661',
  '676127a05edc62c9657c50727de61fccff75a69ab7daa4da2a7cdc77c81584b0'))))), 'UPGRADE', 'ARCHIVE');
