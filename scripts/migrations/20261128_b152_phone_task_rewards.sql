-- B152: keep the existing reward snapshots readable and allow PHONE durations in seconds.
SET @b152_duration_check = (
  SELECT tc.CONSTRAINT_NAME
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
   WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='nx_sim_task_type_version'
     AND LOWER(cc.CHECK_CLAUSE) LIKE '%duration_seconds%'
     AND (LOCATE('%',cc.CHECK_CLAUSE)>0 OR LOWER(cc.CHECK_CLAUSE) LIKE '%mod(%')
     AND LOWER(cc.CHECK_CLAUSE) NOT LIKE '%pool_code%'
);
SET @b152_ddl = IF(@b152_duration_check IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE nx_sim_task_type_version DROP CHECK `', @b152_duration_check,
         '`, ADD CONSTRAINT chk_sim_type_duration_seconds CHECK (duration_seconds BETWEEN 60 AND 86400',
         ' AND (COALESCE(pool_code, '''') = ''PHONE'' OR MOD(duration_seconds,60)=0))'));
PREPARE b152_statement FROM @b152_ddl;
EXECUTE b152_statement;
DEALLOCATE PREPARE b152_statement;

SET @b152_ddl = IF(EXISTS (
  SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_simulated_task' AND COLUMN_NAME='phone_reward_nex'
), 'SELECT 1', 'ALTER TABLE nx_simulated_task
  ADD COLUMN phone_reward_nex DECIMAL(18,6) NULL,
  ADD COLUMN phone_daily_usdt DECIMAL(18,6) NULL,
  ADD COLUMN phone_daily_nex DECIMAL(18,6) NULL');
PREPARE b152_statement FROM @b152_ddl;
EXECUTE b152_statement;
DEALLOCATE PREPARE b152_statement;

-- Keep B86's 11 task IDs, durations and names. Fill the gaps with 189 distinct durations.
CREATE TEMPORARY TABLE b152_phone_seed (
  serial INT NOT NULL PRIMARY KEY,
  duration_seconds INT NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL UNIQUE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO b152_phone_seed VALUES
  (51, 603, 'Embed billing help articles for semantic search'),
  (52, 606, 'Embed account recovery guides for semantic search'),
  (53, 609, 'Embed shipping questions for semantic search'),
  (54, 612, 'Embed return policy excerpts for semantic search'),
  (55, 615, 'Embed warranty explanations for semantic search'),
  (56, 618, 'Embed product specifications for semantic search'),
  (57, 621, 'Embed catalog descriptions for semantic search'),
  (58, 624, 'Embed store category labels for semantic search'),
  (59, 627, 'Embed customer review excerpts for semantic search'),
  (60, 630, 'Embed support ticket summaries for semantic search'),
  (61, 633, 'Embed troubleshooting notes for semantic search'),
  (62, 636, 'Embed device setup manuals for semantic search'),
  (63, 639, 'Embed release notes for semantic search'),
  (64, 642, 'Embed knowledge base headings for semantic search'),
  (65, 645, 'Embed product photo captions for semantic search'),
  (66, 648, 'Embed accessory image labels for semantic search'),
  (67, 651, 'Embed inventory item names for semantic search'),
  (68, 654, 'Embed training lesson transcripts for semantic search'),
  (69, 657, 'Embed technical glossary entries for semantic search'),
  (70, 663, 'Embed installation checklists for semantic search'),
  (71, 666, 'Embed service status updates for semantic search'),
  (72, 669, 'Embed billing help articles for duplicate detection'),
  (73, 672, 'Embed account recovery guides for duplicate detection'),
  (74, 675, 'Embed shipping questions for duplicate detection'),
  (75, 678, 'Embed return policy excerpts for duplicate detection'),
  (76, 681, 'Embed warranty explanations for duplicate detection'),
  (77, 684, 'Embed product specifications for duplicate detection'),
  (78, 687, 'Embed catalog descriptions for duplicate detection'),
  (79, 690, 'Embed store category labels for duplicate detection'),
  (80, 693, 'Embed customer review excerpts for duplicate detection'),
  (81, 696, 'Embed support ticket summaries for duplicate detection'),
  (82, 699, 'Embed troubleshooting notes for duplicate detection'),
  (83, 702, 'Embed device setup manuals for duplicate detection'),
  (84, 705, 'Embed release notes for duplicate detection'),
  (85, 708, 'Embed knowledge base headings for duplicate detection'),
  (86, 711, 'Embed product photo captions for duplicate detection'),
  (87, 714, 'Embed accessory image labels for duplicate detection'),
  (88, 717, 'Embed inventory item names for duplicate detection'),
  (89, 723, 'Embed training lesson transcripts for duplicate detection'),
  (90, 726, 'Embed technical glossary entries for duplicate detection'),
  (91, 729, 'Embed installation checklists for duplicate detection'),
  (92, 732, 'Embed service status updates for duplicate detection'),
  (93, 735, 'Embed billing help articles for topic clustering'),
  (94, 738, 'Embed account recovery guides for topic clustering'),
  (95, 741, 'Embed shipping questions for topic clustering'),
  (96, 744, 'Embed return policy excerpts for topic clustering'),
  (97, 747, 'Embed warranty explanations for topic clustering'),
  (98, 750, 'Embed product specifications for topic clustering'),
  (99, 753, 'Embed catalog descriptions for topic clustering'),
  (100, 756, 'Embed store category labels for topic clustering'),
  (101, 759, 'Embed customer review excerpts for topic clustering'),
  (102, 762, 'Embed support ticket summaries for topic clustering'),
  (103, 765, 'Embed troubleshooting notes for topic clustering'),
  (104, 768, 'Embed device setup manuals for topic clustering'),
  (105, 771, 'Embed release notes for topic clustering'),
  (106, 774, 'Embed knowledge base headings for topic clustering'),
  (107, 777, 'Embed product photo captions for topic clustering'),
  (108, 783, 'Embed accessory image labels for topic clustering'),
  (109, 786, 'Embed inventory item names for topic clustering'),
  (110, 789, 'Embed training lesson transcripts for topic clustering'),
  (111, 792, 'Embed technical glossary entries for topic clustering'),
  (112, 795, 'Embed installation checklists for topic clustering'),
  (113, 798, 'Embed service status updates for topic clustering'),
  (114, 801, 'Embed billing help articles for relevance ranking'),
  (115, 804, 'Embed account recovery guides for relevance ranking'),
  (116, 807, 'Embed shipping questions for relevance ranking'),
  (117, 810, 'Embed return policy excerpts for relevance ranking'),
  (118, 813, 'Embed warranty explanations for relevance ranking'),
  (119, 816, 'Embed product specifications for relevance ranking'),
  (120, 819, 'Embed catalog descriptions for relevance ranking'),
  (121, 822, 'Embed store category labels for relevance ranking'),
  (122, 825, 'Embed customer review excerpts for relevance ranking'),
  (123, 828, 'Embed support ticket summaries for relevance ranking'),
  (124, 831, 'Embed troubleshooting notes for relevance ranking'),
  (125, 834, 'Embed device setup manuals for relevance ranking'),
  (126, 837, 'Embed release notes for relevance ranking'),
  (127, 843, 'Embed knowledge base headings for relevance ranking'),
  (128, 846, 'Embed product photo captions for relevance ranking'),
  (129, 849, 'Embed accessory image labels for relevance ranking'),
  (130, 852, 'Embed inventory item names for relevance ranking'),
  (131, 855, 'Embed training lesson transcripts for relevance ranking'),
  (132, 858, 'Embed technical glossary entries for relevance ranking'),
  (133, 861, 'Embed installation checklists for relevance ranking'),
  (134, 864, 'Embed service status updates for relevance ranking'),
  (135, 867, 'Embed billing help articles for cross-language retrieval'),
  (136, 870, 'Embed account recovery guides for cross-language retrieval'),
  (137, 873, 'Embed shipping questions for cross-language retrieval'),
  (138, 876, 'Embed return policy excerpts for cross-language retrieval'),
  (139, 879, 'Embed warranty explanations for cross-language retrieval'),
  (140, 882, 'Embed product specifications for cross-language retrieval'),
  (141, 885, 'Embed catalog descriptions for cross-language retrieval'),
  (142, 888, 'Embed store category labels for cross-language retrieval'),
  (143, 891, 'Embed customer review excerpts for cross-language retrieval'),
  (144, 894, 'Embed support ticket summaries for cross-language retrieval'),
  (145, 897, 'Embed troubleshooting notes for cross-language retrieval'),
  (146, 903, 'Embed device setup manuals for cross-language retrieval'),
  (147, 906, 'Embed release notes for cross-language retrieval'),
  (148, 909, 'Embed knowledge base headings for cross-language retrieval'),
  (149, 912, 'Embed product photo captions for cross-language retrieval'),
  (150, 915, 'Embed accessory image labels for cross-language retrieval'),
  (151, 918, 'Embed inventory item names for cross-language retrieval'),
  (152, 921, 'Embed training lesson transcripts for cross-language retrieval'),
  (153, 924, 'Embed technical glossary entries for cross-language retrieval'),
  (154, 927, 'Embed installation checklists for cross-language retrieval'),
  (155, 930, 'Embed service status updates for cross-language retrieval'),
  (156, 933, 'Embed billing help articles for related content recommendations'),
  (157, 936, 'Embed account recovery guides for related content recommendations'),
  (158, 939, 'Embed shipping questions for related content recommendations'),
  (159, 942, 'Embed return policy excerpts for related content recommendations'),
  (160, 945, 'Embed warranty explanations for related content recommendations'),
  (161, 948, 'Embed product specifications for related content recommendations'),
  (162, 951, 'Embed catalog descriptions for related content recommendations'),
  (163, 954, 'Embed store category labels for related content recommendations'),
  (164, 957, 'Embed customer review excerpts for related content recommendations'),
  (165, 963, 'Embed support ticket summaries for related content recommendations'),
  (166, 966, 'Embed troubleshooting notes for related content recommendations'),
  (167, 969, 'Embed device setup manuals for related content recommendations'),
  (168, 972, 'Embed release notes for related content recommendations'),
  (169, 975, 'Embed knowledge base headings for related content recommendations'),
  (170, 978, 'Embed product photo captions for related content recommendations'),
  (171, 981, 'Embed accessory image labels for related content recommendations'),
  (172, 984, 'Embed inventory item names for related content recommendations'),
  (173, 987, 'Embed training lesson transcripts for related content recommendations'),
  (174, 990, 'Embed technical glossary entries for related content recommendations'),
  (175, 993, 'Embed installation checklists for related content recommendations'),
  (176, 996, 'Embed service status updates for related content recommendations'),
  (177, 999, 'Embed billing help articles for query expansion'),
  (178, 1002, 'Embed account recovery guides for query expansion'),
  (179, 1005, 'Embed shipping questions for query expansion'),
  (180, 1008, 'Embed return policy excerpts for query expansion'),
  (181, 1011, 'Embed warranty explanations for query expansion'),
  (182, 1014, 'Embed product specifications for query expansion'),
  (183, 1017, 'Embed catalog descriptions for query expansion'),
  (184, 1023, 'Embed store category labels for query expansion'),
  (185, 1026, 'Embed customer review excerpts for query expansion'),
  (186, 1029, 'Embed support ticket summaries for query expansion'),
  (187, 1032, 'Embed troubleshooting notes for query expansion'),
  (188, 1035, 'Embed device setup manuals for query expansion'),
  (189, 1038, 'Embed release notes for query expansion'),
  (190, 1041, 'Embed knowledge base headings for query expansion'),
  (191, 1044, 'Embed product photo captions for query expansion'),
  (192, 1047, 'Embed accessory image labels for query expansion'),
  (193, 1050, 'Embed inventory item names for query expansion'),
  (194, 1053, 'Embed training lesson transcripts for query expansion'),
  (195, 1056, 'Embed technical glossary entries for query expansion'),
  (196, 1059, 'Embed installation checklists for query expansion'),
  (197, 1062, 'Embed service status updates for query expansion'),
  (198, 1065, 'Embed billing help articles for taxonomy matching'),
  (199, 1068, 'Embed account recovery guides for taxonomy matching'),
  (200, 1071, 'Embed shipping questions for taxonomy matching'),
  (201, 1074, 'Embed return policy excerpts for taxonomy matching'),
  (202, 1077, 'Embed warranty explanations for taxonomy matching'),
  (203, 1083, 'Embed product specifications for taxonomy matching'),
  (204, 1086, 'Embed catalog descriptions for taxonomy matching'),
  (205, 1089, 'Embed store category labels for taxonomy matching'),
  (206, 1092, 'Embed customer review excerpts for taxonomy matching'),
  (207, 1095, 'Embed support ticket summaries for taxonomy matching'),
  (208, 1098, 'Embed troubleshooting notes for taxonomy matching'),
  (209, 1101, 'Embed device setup manuals for taxonomy matching'),
  (210, 1104, 'Embed release notes for taxonomy matching'),
  (211, 1107, 'Embed knowledge base headings for taxonomy matching'),
  (212, 1110, 'Embed product photo captions for taxonomy matching'),
  (213, 1113, 'Embed accessory image labels for taxonomy matching'),
  (214, 1116, 'Embed inventory item names for taxonomy matching'),
  (215, 1119, 'Embed training lesson transcripts for taxonomy matching'),
  (216, 1122, 'Embed technical glossary entries for taxonomy matching'),
  (217, 1125, 'Embed installation checklists for taxonomy matching'),
  (218, 1128, 'Embed service status updates for taxonomy matching'),
  (219, 1131, 'Embed billing help articles for support knowledge retrieval'),
  (220, 1134, 'Embed account recovery guides for support knowledge retrieval'),
  (221, 1137, 'Embed shipping questions for support knowledge retrieval'),
  (222, 1143, 'Embed return policy excerpts for support knowledge retrieval'),
  (223, 1146, 'Embed warranty explanations for support knowledge retrieval'),
  (224, 1149, 'Embed product specifications for support knowledge retrieval'),
  (225, 1152, 'Embed catalog descriptions for support knowledge retrieval'),
  (226, 1155, 'Embed store category labels for support knowledge retrieval'),
  (227, 1158, 'Embed customer review excerpts for support knowledge retrieval'),
  (228, 1161, 'Embed support ticket summaries for support knowledge retrieval'),
  (229, 1164, 'Embed troubleshooting notes for support knowledge retrieval'),
  (230, 1167, 'Embed device setup manuals for support knowledge retrieval'),
  (231, 1170, 'Embed release notes for support knowledge retrieval'),
  (232, 1173, 'Embed knowledge base headings for support knowledge retrieval'),
  (233, 1176, 'Embed product photo captions for support knowledge retrieval'),
  (234, 1179, 'Embed accessory image labels for support knowledge retrieval'),
  (235, 1182, 'Embed inventory item names for support knowledge retrieval'),
  (236, 1185, 'Embed training lesson transcripts for support knowledge retrieval'),
  (237, 1188, 'Embed technical glossary entries for support knowledge retrieval'),
  (238, 1191, 'Embed installation checklists for support knowledge retrieval'),
  (239, 1194, 'Embed service status updates for support knowledge retrieval');

START TRANSACTION;
-- Serialize with E2 publication and use the same storage time zone as B85/B86.
SELECT COUNT(*) INTO @b152_config_rows FROM nx_compute_e3_config WHERE is_deleted=0 FOR UPDATE;
SET @b152_at = NOW(6);
SET @b152_unchanged = (
  SELECT COUNT(*)=110 FROM nx_sim_task_type_version
   WHERE pool_code='PHONE' OR task_type_id LIKE 'PHONE-LAUNCH-%'
) AND (
  SELECT COUNT(*)=50 AND COUNT(DISTINCT launch.effective_at)=1 AND COUNT(DISTINCT b85.effective_at)=1
    FROM nx_sim_task_type_version launch
    JOIN nx_sim_task_type_version b85 ON b85.task_type_id=launch.task_type_id AND b85.version=2
   WHERE launch.version=1 AND launch.pool_code='PHONE' AND b85.pool_code='PHONE'
     AND REGEXP_LIKE(launch.task_type_id, '^PHONE-LAUNCH-(0[1-9]|[1-4][0-9]|50)$', 'c')
     AND launch.effective_at<=b85.effective_at AND b85.effective_at<=@b152_at
     AND launch.duration_seconds=3600 AND launch.base_reward=0.002500 AND launch.asset='USDT' AND launch.enabled=1
     AND launch.task_class=CASE
       WHEN launch.task_type_id<='PHONE-LAUNCH-09' THEN 'SP'
       WHEN launch.task_type_id<='PHONE-LAUNCH-18' THEN 'LL'
       WHEN launch.task_type_id<='PHONE-LAUNCH-26' THEN 'IG'
       WHEN launch.task_type_id<='PHONE-LAUNCH-32' THEN 'VG'
       WHEN launch.task_type_id<='PHONE-LAUNCH-39' THEN 'FT'
       ELSE 'EM' END
     AND CAST(b85.name AS BINARY)=CAST(launch.name AS BINARY)
     AND b85.task_class=launch.task_class AND b85.asset=launch.asset
     AND b85.duration_seconds=IF(launch.task_class='EM',600,3600)
     AND b85.base_reward=IF(launch.task_class='EM',0.000417,0.002500)
     AND b85.enabled=(launch.task_class='EM')
) AND (
  SELECT COUNT(*)=10 AND COUNT(DISTINCT b86.effective_at)=1
    FROM nx_sim_task_type_version b86
    JOIN nx_sim_task_type_version b85 ON b85.task_type_id=b86.task_type_id AND b85.version=2
   WHERE b86.version=3 AND b86.pool_code='PHONE'
     AND REGEXP_LIKE(b86.task_type_id, '^PHONE-LAUNCH-(4[1-9]|50)$', 'c')
     AND b85.effective_at<=b86.effective_at AND b86.effective_at<=@b152_at
     AND CAST(b86.name AS BINARY)=CAST(b85.name AS BINARY)
     AND b86.task_class='EM' AND b86.asset='USDT' AND b86.enabled=1
     AND b86.duration_seconds=(CAST(RIGHT(b86.task_type_id,2) AS UNSIGNED)-30)*60
     AND b86.base_reward=ROUND(b86.duration_seconds*0.002500/3600,6)
);
SET @b152_catalog_ids_free = NOT EXISTS (
  SELECT 1 FROM nx_admin_device_task task
    JOIN b152_phone_seed seed ON task.task_id=CONCAT('PHONE-LAUNCH-',seed.serial)
);
SET @b152_policy_ids_free = NOT EXISTS (
  SELECT 1 FROM nx_compute_task_policy_v2 policy
    JOIN b152_phone_seed seed ON policy.task_id=CONCAT('PHONE-LAUNCH-',seed.serial)
);
SET @b152_unchanged = @b152_unchanged AND @b152_catalog_ids_free AND @b152_policy_ids_free;

INSERT INTO nx_admin_device_task (
  task_id,name,price,unit_text,requirement,saturation,status,task_class,model_name,
  min_reward,max_reward,min_vram,kill_init,created_at,updated_at,is_deleted
)
SELECT CONCAT('PHONE-LAUNCH-',serial),name,0,'/job','手机+',0,'active','EM','',
       0,0,'0GB','派发中',@b152_at,@b152_at,0
  FROM b152_phone_seed WHERE @b152_unchanged ORDER BY serial;

INSERT INTO nx_compute_task_policy_v2 (
  task_id,selection_weight,expected_duration_min_sec,expected_duration_max_sec,
  execution_timeout_sec,runtime_key,validator_key,workload_config_json,revision
)
SELECT CONCAT('PHONE-LAUNCH-',serial),1,duration_seconds,duration_seconds,duration_seconds,'','',JSON_OBJECT(),1
  FROM b152_phone_seed WHERE @b152_unchanged ORDER BY serial;

-- Legacy catalog price/asset columns are retained for readers. Dispatch uses the calibrated rates.
INSERT INTO nx_sim_task_type_version (
  task_type_id,version,effective_at,name,task_class,duration_seconds,base_reward,asset,enabled,pool_code
)
SELECT CONCAT('PHONE-LAUNCH-',serial),1,@b152_at,name,'EM',duration_seconds,0,'USDT',1,'PHONE'
  FROM b152_phone_seed WHERE @b152_unchanged ORDER BY serial;

COMMIT;
DROP TEMPORARY TABLE b152_phone_seed;

SELECT IF(@b152_unchanged, 'B152_PHONE_POOL_EXPANDED', 'B152_PHONE_POOL_SKIPPED_NON_DEFAULT_OR_ALREADY_APPLIED')
       AS migration_result;
