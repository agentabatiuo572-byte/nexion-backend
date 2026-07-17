package ffdd.opsconsole.emergency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EmergencyControlMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_geo_country_policy (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              country_code VARCHAR(2) NOT NULL,
              country_name VARCHAR(128) NULL,
              policy_status VARCHAR(32) NOT NULL,
              reason VARCHAR(500) NULL,
              operator VARCHAR(64) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_geo_country_policy (country_code),
              KEY idx_geo_country_policy_status (policy_status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGeoCountryPolicyTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_geo_endpoint_catalog (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              endpoint_key VARCHAR(64) NOT NULL,
              endpoint_path VARCHAR(255) NOT NULL,
              label VARCHAR(128) NOT NULL,
              biz VARCHAR(128) NOT NULL,
              domain_code VARCHAR(16) NOT NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
              sort_order INT NOT NULL DEFAULT 100,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_geo_endpoint_catalog_key (endpoint_key),
              KEY idx_geo_endpoint_catalog_status (status, sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGeoEndpointCatalogTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_geo_endpoint_policy (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              endpoint_key VARCHAR(64) NOT NULL,
              endpoint_path VARCHAR(255) NOT NULL,
              label VARCHAR(128) NOT NULL,
              biz VARCHAR(128) NOT NULL,
              domain_code VARCHAR(16) NOT NULL,
              country_code VARCHAR(2) NOT NULL,
              policy_source VARCHAR(32) NOT NULL,
              reason VARCHAR(500) NULL,
              operator VARCHAR(64) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_geo_endpoint_country (endpoint_key, country_code),
              KEY idx_geo_endpoint_policy_endpoint (endpoint_key, is_deleted),
              KEY idx_geo_endpoint_policy_country (country_code, is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGeoEndpointPolicyTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_geo_block_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              country_code VARCHAR(2) NOT NULL,
              country_name VARCHAR(128) NULL,
              endpoint_key VARCHAR(64) NULL,
              source VARCHAR(64) NULL,
              event_count INT NOT NULL DEFAULT 1,
              recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_geo_event_country_time (country_code, recorded_at),
              KEY idx_geo_event_endpoint_time (endpoint_key, recorded_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createGeoBlockEventTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_tamper_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              event_no VARCHAR(96) NOT NULL,
              user_id BIGINT NULL,
              user_no VARCHAR(32) NULL,
              path_key VARCHAR(64) NOT NULL,
              path_name VARCHAR(128) NOT NULL,
              description VARCHAR(500) NULL,
              cluster_code VARCHAR(64) NULL,
              k4_accepted TINYINT NOT NULL DEFAULT 0,
              k4_delta INT NOT NULL DEFAULT 0,
              b5_accepted TINYINT NOT NULL DEFAULT 0,
              event_count INT NOT NULL DEFAULT 1,
              occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_tamper_event_path_time (path_key, occurred_at),
              KEY idx_tamper_event_user_time (user_id, user_no, occurred_at),
              KEY idx_tamper_event_cluster (cluster_code, occurred_at),
              UNIQUE KEY uk_emergency_tamper_event_no (event_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTamperEventTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_tamper_report (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              report_id VARCHAR(96) NOT NULL,
              window_label VARCHAR(32) NOT NULL,
              masked TINYINT NOT NULL DEFAULT 1,
              status VARCHAR(32) NOT NULL DEFAULT 'READY',
              payload_json JSON NULL,
              operator VARCHAR(64) NULL,
              reason VARCHAR(500) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_emergency_tamper_report_id (report_id),
              KEY idx_emergency_tamper_report_time (created_at, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTamperReportTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_control_setting (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              setting_key VARCHAR(128) NOT NULL,
              setting_value VARCHAR(512) NOT NULL,
              value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
              group_code VARCHAR(64) NOT NULL,
              remark VARCHAR(500) NULL,
              operator VARCHAR(64) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_emergency_control_setting_key (setting_key),
              KEY idx_emergency_control_setting_group (group_code, is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createControlSettingTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_sop_playbook (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              code VARCHAR(64) NOT NULL,
              name VARCHAR(128) NOT NULL,
              scene VARCHAR(64) NOT NULL,
              emergency_track TINYINT NOT NULL DEFAULT 0,
              sla VARCHAR(64) NOT NULL,
              state VARCHAR(32) NOT NULL DEFAULT 'todo',
              owner VARCHAR(64) NOT NULL,
              last_drill_at DATETIME NULL,
              notify_campaign_no VARCHAR(96) NULL,
              notify_template VARCHAR(255) NULL,
              rollback_plan VARCHAR(500) NULL,
              drill_required TINYINT NOT NULL DEFAULT 1,
              draft TINYINT NOT NULL DEFAULT 1,
              summary VARCHAR(500) NULL,
              created_by VARCHAR(64) NULL,
              updated_by VARCHAR(64) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_emergency_sop_playbook_code (code),
              KEY idx_emergency_sop_playbook_state (state, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSopPlaybookTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_sop_action (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              playbook_code VARCHAR(64) NOT NULL,
              step_order INT NOT NULL,
              domain_code VARCHAR(16) NOT NULL,
              action_text VARCHAR(500) NOT NULL,
              approve_required TINYINT NOT NULL DEFAULT 0,
              ref_code VARCHAR(96) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_emergency_sop_action_step (playbook_code, step_order),
              KEY idx_emergency_sop_action_code (playbook_code, is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSopActionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_emergency_sop_execution (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              execution_id VARCHAR(96) NOT NULL,
              playbook_code VARCHAR(64) NOT NULL,
              playbook_name VARCHAR(128) NOT NULL,
              trigger_reason VARCHAR(500) NOT NULL,
              execution_mode VARCHAR(32) NOT NULL,
              step_status_json JSON NULL,
              operator VARCHAR(64) NULL,
              role_gate VARCHAR(64) NULL,
              idempotency_key VARCHAR(128) NULL,
              notification_json JSON NULL,
              domain_action_json JSON NULL,
              rollback_plan VARCHAR(500) NULL,
              rollback_status VARCHAR(32) NULL,
              rollback_at DATETIME NULL,
              rollback_reason VARCHAR(500) NULL,
              rollback_action_json JSON NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_emergency_sop_execution_id (execution_id),
              UNIQUE KEY uk_emergency_sop_execution_idem (playbook_code, idempotency_key),
              KEY idx_emergency_sop_execution_code_time (playbook_code, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSopExecutionTable();

    @Select("""
            SELECT country_code AS cc,
                   COALESCE(country_name, country_code) AS name,
                   policy_status AS status,
                   COALESCE(reason, '') AS reason,
                   COALESCE(operator, '') AS operator,
                   updated_at AS updatedAt
              FROM nx_emergency_geo_country_policy
             WHERE is_deleted = 0
             ORDER BY FIELD(policy_status, 'blocked', 'limited', 'allowed'), country_code
            """)
    List<Map<String, Object>> geoCountryPolicies();

    @Select("""
            SELECT UPPER(u.country_code) AS cc,
                   SUM(CASE WHEN u.status = 'ACTIVE' THEN 1 ELSE 0 END) AS activeUsers,
                   COALESCE(SUM(COALESCE(w.usdt_available, 0) + COALESCE(w.pending_withdraw, 0)), 0) AS walletUsdt
              FROM nx_user u
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
             WHERE u.is_deleted = 0
               AND LENGTH(TRIM(u.country_code)) = 2
             GROUP BY UPPER(u.country_code)
            """)
    List<Map<String, Object>> geoCountryImpacts();

    @Select("""
            SELECT action,
                   resource_type AS resourceType,
                   resource_id AS resourceId,
                   COALESCE(actor_username, '') AS operator,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.reason')), '') AS reason,
                   JSON_EXTRACT(detail_json, '$.before') AS beforeValue,
                   JSON_EXTRACT(detail_json, '$.after') AS afterValue,
                   created_at AS createdAt
              FROM nx_audit_log
             WHERE is_deleted = 0
               AND action LIKE 'J2_GEO_%'
               AND result = 'SUCCESS'
             ORDER BY id DESC
             LIMIT 8
            """)
    List<Map<String, Object>> geoRecentChanges();

    @Insert("""
            INSERT INTO nx_emergency_geo_country_policy (
                country_code, country_name, policy_status, reason, operator,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{countryCode}, #{countryName}, #{status}, #{reason}, #{operator},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                country_name = VALUES(country_name),
                policy_status = VALUES(policy_status),
                reason = VALUES(reason),
                operator = VALUES(operator),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertGeoCountryPolicy(@Param("countryCode") String countryCode,
                               @Param("countryName") String countryName,
                               @Param("status") String status,
                               @Param("reason") String reason,
                               @Param("operator") String operator);

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
                setting_key, setting_value, value_type, group_code, remark, operator,
                created_at, updated_at, is_deleted
            ) VALUES (
                'emergency.geo.countryMutationLock', '1', 'INTEGER', 'geo-block',
                'J2 country mutation serialization lock', 'system', NOW(), NOW(), 0
            )
            """)
    int ensureGeoCountryMutationLock();

    @Select("""
            SELECT setting_key
              FROM nx_emergency_control_setting
             WHERE setting_key = 'emergency.geo.countryMutationLock'
             FOR UPDATE
            """)
    String lockGeoCountryMutations();

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
                setting_key, setting_value, value_type, group_code, remark, operator,
                created_at, updated_at, is_deleted
            ) VALUES (
                CONCAT('emergency.geo.endpointMutationLock.', #{endpointKey}), '1', 'INTEGER', 'geo-block',
                'J2 endpoint mutation serialization lock', 'system', NOW(), NOW(), 0
            )
            """)
    int ensureGeoEndpointMutationLock(@Param("endpointKey") String endpointKey);

    @Select("""
            SELECT setting_key
              FROM nx_emergency_control_setting
             WHERE setting_key = CONCAT('emergency.geo.endpointMutationLock.', #{endpointKey})
             FOR UPDATE
            """)
    String lockGeoEndpointMutation(@Param("endpointKey") String endpointKey);

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
                setting_key, setting_value, value_type, group_code, remark, operator,
                created_at, updated_at, is_deleted
            ) VALUES (
                'emergency.geo.edgeMutationLock', '1', 'INTEGER', 'geo-block',
                'J2 edge source mutation serialization lock', 'system', NOW(), NOW(), 0
            )
            """)
    int ensureGeoEdgeMutationLock();

    @Select("""
            SELECT setting_key
              FROM nx_emergency_control_setting
             WHERE setting_key = 'emergency.geo.edgeMutationLock'
             FOR UPDATE
            """)
    String lockGeoEdgeMutation();

    @Insert("""
            INSERT INTO nx_emergency_control_setting (
                setting_key, setting_value, value_type, group_code, remark, operator,
                created_at, updated_at, is_deleted
            ) VALUES (
                'emergency.tamper.configMutationLock', '1', 'INTEGER', 'tamper',
                'J3 alert config mutation serialization lock', 'system', NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key)
            """)
    int ensureTamperConfigMutationLock();

    @Select("""
            SELECT setting_key
              FROM nx_emergency_control_setting
             WHERE setting_key = 'emergency.tamper.configMutationLock'
             FOR UPDATE
            """)
    String lockTamperConfigMutation();

    @Select("""
            SELECT setting_key AS settingKey,
                   setting_value AS settingValue
              FROM nx_emergency_control_setting
             WHERE setting_key IN (
                       'emergency.tamper.alert.threshold',
                       'emergency.tamper.alert.feedK4'
                   )
               AND is_deleted = 0
             FOR UPDATE
            """)
    List<Map<String, Object>> tamperConfigForUpdate();

    @Select("""
            SELECT endpoint_key AS endpointKey,
                   endpoint_path AS endpointPath,
                   label,
                   biz,
                   domain_code AS domain,
                   status,
                   sort_order AS sortOrder
              FROM nx_emergency_geo_endpoint_catalog
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, endpoint_key ASC
            """)
    List<Map<String, Object>> geoEndpointCatalogs();

    @Select("""
            SELECT endpoint_key AS endpointKey,
                   endpoint_path AS endpointPath,
                   label,
                   biz,
                   domain_code AS domain,
                   status,
                   sort_order AS sortOrder
              FROM nx_emergency_geo_endpoint_catalog
             WHERE is_deleted = 0
               AND endpoint_key = #{endpointKey}
             LIMIT 1
            """)
    Map<String, Object> geoEndpointCatalog(@Param("endpointKey") String endpointKey);

    @Select("""
            SELECT endpoint_key AS endpointKey,
                   endpoint_path AS endpointPath,
                   label,
                   biz,
                   domain_code AS domain,
                   country_code AS countryCode,
                   policy_source AS source,
                   COALESCE(reason, '') AS reason
              FROM nx_emergency_geo_endpoint_policy
             WHERE is_deleted = 0
             ORDER BY endpoint_key, country_code
            """)
    List<Map<String, Object>> geoEndpointPolicies();

    @Update("UPDATE nx_emergency_geo_endpoint_policy SET is_deleted = 1, updated_at = NOW() WHERE endpoint_key = #{endpointKey}")
    int softDeleteGeoEndpointPolicies(@Param("endpointKey") String endpointKey);

    @Insert("""
            INSERT INTO nx_emergency_geo_endpoint_policy (
                endpoint_key, endpoint_path, label, biz, domain_code, country_code,
                policy_source, reason, operator, created_at, updated_at, is_deleted
            ) VALUES (
                #{endpointKey}, #{endpointPath}, #{label}, #{biz}, #{domain}, #{countryCode},
                #{source}, #{reason}, #{operator}, NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                endpoint_path = VALUES(endpoint_path),
                label = VALUES(label),
                biz = VALUES(biz),
                domain_code = VALUES(domain_code),
                policy_source = VALUES(policy_source),
                reason = VALUES(reason),
                operator = VALUES(operator),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int insertGeoEndpointPolicy(@Param("endpointKey") String endpointKey,
                                @Param("endpointPath") String endpointPath,
                                @Param("label") String label,
                                @Param("biz") String biz,
                                @Param("domain") String domain,
                                @Param("countryCode") String countryCode,
                                @Param("source") String source,
                                @Param("reason") String reason,
                                @Param("operator") String operator);

    @Select("""
            SELECT country_code AS cc,
                   COALESCE(MAX(country_name), country_code) AS name,
                   SUM(event_count) AS count
              FROM nx_emergency_geo_block_event
             WHERE is_deleted = 0
               AND recorded_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
             GROUP BY country_code
             ORDER BY count DESC, cc ASC
             LIMIT 20
            """)
    List<Map<String, Object>> geoHits();

    @Select("""
            SELECT endpoint_key AS endpointKey,
                   SUM(event_count) AS count
              FROM nx_emergency_geo_block_event
             WHERE is_deleted = 0
               AND recorded_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
               AND endpoint_key IS NOT NULL
               AND endpoint_key <> ''
             GROUP BY endpoint_key
            """)
    List<Map<String, Object>> geoEndpointHits();

    @Select("""
            SELECT CONCAT('边缘源 ', edge.sourceKey) AS `key`,
                   CONCAT(edge.totalCount, ' 次 / 24h') AS value,
                   'ok' AS tone
              FROM (
                    SELECT COALESCE(source, 'unknown') AS sourceKey,
                           SUM(event_count) AS totalCount
                      FROM nx_emergency_geo_block_event
                     WHERE is_deleted = 0
                       AND recorded_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
                     GROUP BY COALESCE(source, 'unknown')
                   ) edge
             ORDER BY edge.totalCount DESC
             LIMIT 6
            """)
    List<Map<String, Object>> geoEdgeMetrics();

    @Insert("""
            INSERT INTO nx_emergency_geo_block_event (
                country_code, country_name, endpoint_key, source, event_count,
                recorded_at, created_at, updated_at, is_deleted
            ) VALUES (
                #{countryCode}, #{countryName}, #{endpointKey}, #{source}, 1,
                NOW(), NOW(), NOW(), 0
            )
            """)
    int insertGeoBlockEvent(@Param("countryCode") String countryCode,
                            @Param("countryName") String countryName,
                            @Param("endpointKey") String endpointKey,
                            @Param("source") String source);

    @Select("""
            SELECT setting_value
              FROM nx_emergency_control_setting
             WHERE setting_key = #{settingKey}
               AND is_deleted = 0
             LIMIT 1
            """)
    String settingValue(@Param("settingKey") String settingKey);

    @Update("""
            UPDATE nx_emergency_control_setting
               SET setting_value = CONCAT(
                     DATE_FORMAT(updated_at, '%m-%d %H:%i'),
                     ' · ',
                     COALESCE(NULLIF(operator, ''), 'system'),
                     ' · 历史状态切换'),
                    operator = #{operator},
                    updated_at = NOW()
             WHERE setting_key = #{lastChangeSettingKey}
               AND is_deleted = 0
               AND setting_value LIKE '刚刚%'
            """)
    int repairLegacyLastChange(@Param("lastChangeSettingKey") String lastChangeSettingKey,
                               @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_emergency_control_setting (
              setting_key, setting_value, value_type, group_code, remark, operator, is_deleted
            ) VALUES (
              #{settingKey}, #{settingValue}, #{valueType}, #{groupCode}, #{remark}, #{operator}, 0
            )
            ON DUPLICATE KEY UPDATE
              setting_value = VALUES(setting_value),
              value_type = VALUES(value_type),
              group_code = VALUES(group_code),
              remark = VALUES(remark),
              operator = VALUES(operator),
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertSetting(@Param("settingKey") String settingKey,
                      @Param("settingValue") String settingValue,
                      @Param("valueType") String valueType,
                      @Param("groupCode") String groupCode,
                      @Param("remark") String remark,
                      @Param("operator") String operator);

    @Update("""
            UPDATE nx_emergency_control_setting
               SET setting_value = #{newValue},
                   operator = #{operator},
                   updated_at = NOW()
             WHERE setting_key = #{settingKey}
               AND setting_value = #{expectedValue}
               AND is_deleted = 0
            """)
    int compareAndSetSetting(@Param("settingKey") String settingKey,
                             @Param("expectedValue") String expectedValue,
                             @Param("newValue") String newValue,
                             @Param("operator") String operator);

    @Update("""
            UPDATE nx_emergency_control_setting primary_gate
            LEFT JOIN nx_emergency_control_setting legacy_gate
              ON legacy_gate.setting_key = #{legacySettingKey}
             AND legacy_gate.is_deleted = 0
               SET primary_gate.setting_value = 'disabled',
                   primary_gate.value_type = 'STRING',
                   primary_gate.group_code = 'admin_killswitch',
                   primary_gate.remark = 'J1 active kill switch state',
                   primary_gate.operator = #{operator},
                   primary_gate.updated_at = NOW(),
                   primary_gate.is_deleted = 0
             WHERE primary_gate.setting_key = #{settingKey}
               AND (
                     (primary_gate.is_deleted = 0 AND LOWER(TRIM(primary_gate.setting_value)) IN ('enabled', 'enable', 'on', 'true', '1'))
                     OR (
                       primary_gate.is_deleted = 1
                       AND (
                         legacy_gate.setting_key IS NULL
                         OR LOWER(TRIM(legacy_gate.setting_value)) IN ('enabled', 'enable', 'on', 'true', '1')
                       )
                     )
                   )
            """)
    int disableExistingSettingIfEnabled(@Param("settingKey") String settingKey,
                                        @Param("legacySettingKey") String legacySettingKey,
                                        @Param("operator") String operator);

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
              setting_key, setting_value, value_type, group_code, remark, operator, is_deleted
            )
            SELECT #{settingKey}, 'disabled', 'STRING', 'admin_killswitch',
                   'J1 active kill switch state', #{operator}, 0
             WHERE NOT EXISTS (
                       SELECT 1
                         FROM nx_emergency_control_setting primary_gate
                        WHERE primary_gate.setting_key = #{settingKey}
                     )
               AND (
                     NOT EXISTS (
                         SELECT 1
                           FROM nx_emergency_control_setting legacy_gate
                          WHERE legacy_gate.setting_key = #{legacySettingKey}
                            AND legacy_gate.is_deleted = 0
                     )
                     OR EXISTS (
                         SELECT 1
                           FROM nx_emergency_control_setting legacy_gate
                          WHERE legacy_gate.setting_key = #{legacySettingKey}
                            AND legacy_gate.is_deleted = 0
                            AND LOWER(TRIM(legacy_gate.setting_value)) IN ('enabled', 'enable', 'on', 'true', '1')
                     )
                   )
            """)
    int insertDisabledSettingIfEffectiveDefaultEnabled(@Param("settingKey") String settingKey,
                                                        @Param("legacySettingKey") String legacySettingKey,
                                                        @Param("operator") String operator);

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
              setting_key, setting_value, value_type, group_code, remark, operator, is_deleted
            )
            SELECT #{pendingSettingKey}, 'true', 'BOOLEAN', 'admin_emergency',
                   'J1 repaired automatic kill switch confirmation', #{operator}, 0
             WHERE NOT EXISTS (
                     SELECT 1 FROM nx_emergency_control_setting pending_confirmation
                      WHERE pending_confirmation.setting_key = #{pendingSettingKey}
                   )
               AND EXISTS (
                     SELECT 1 FROM nx_emergency_control_setting gate_state
                      WHERE gate_state.setting_key = #{gateSettingKey}
                        AND gate_state.is_deleted = 0
                        AND LOWER(TRIM(gate_state.setting_value)) NOT IN ('enabled', 'enable', 'on', 'true', '1')
                        AND gate_state.operator = 'system:j1-auto-trigger'
                   )
               AND EXISTS (
                     SELECT 1 FROM nx_emergency_control_setting emergency_state
                      WHERE emergency_state.setting_key = #{emergencySettingKey}
                        AND emergency_state.is_deleted = 0
                        AND LOWER(TRIM(emergency_state.setting_value)) IN ('true', '1', 'yes', 'on')
                   )
               AND EXISTS (
                     SELECT 1 FROM nx_emergency_control_setting last_change
                      WHERE last_change.setting_key = #{lastChangeSettingKey}
                        AND last_change.is_deleted = 0
                        AND last_change.setting_value LIKE '%system:j1-auto-trigger%'
                   )
            """)
    int claimMissingAutoConfirmation(@Param("pendingSettingKey") String pendingSettingKey,
                                     @Param("gateSettingKey") String gateSettingKey,
                                     @Param("emergencySettingKey") String emergencySettingKey,
                                     @Param("lastChangeSettingKey") String lastChangeSettingKey,
                                     @Param("operator") String operator);

    @Update("""
            UPDATE nx_emergency_control_setting pending_confirmation
            JOIN nx_emergency_control_setting gate_state
              ON gate_state.setting_key = #{gateSettingKey}
             AND gate_state.is_deleted = 0
             AND LOWER(TRIM(gate_state.setting_value)) NOT IN ('enabled', 'enable', 'on', 'true', '1')
             AND gate_state.operator = 'system:j1-auto-trigger'
            JOIN nx_emergency_control_setting emergency_state
              ON emergency_state.setting_key = #{emergencySettingKey}
             AND emergency_state.is_deleted = 0
             AND LOWER(TRIM(emergency_state.setting_value)) IN ('true', '1', 'yes', 'on')
            JOIN nx_emergency_control_setting last_change
              ON last_change.setting_key = #{lastChangeSettingKey}
             AND last_change.is_deleted = 0
             AND last_change.setting_value LIKE '%system:j1-auto-trigger%'
               SET pending_confirmation.setting_value = 'true',
                   pending_confirmation.value_type = 'BOOLEAN',
                   pending_confirmation.group_code = 'admin_emergency',
                   pending_confirmation.remark = 'J1 repaired automatic kill switch confirmation',
                   pending_confirmation.operator = #{operator},
                   pending_confirmation.is_deleted = 0,
                   pending_confirmation.updated_at = NOW()
             WHERE pending_confirmation.setting_key = #{pendingSettingKey}
               AND pending_confirmation.is_deleted = 1
            """)
    int restoreDeletedAutoConfirmation(@Param("pendingSettingKey") String pendingSettingKey,
                                       @Param("gateSettingKey") String gateSettingKey,
                                       @Param("emergencySettingKey") String emergencySettingKey,
                                       @Param("lastChangeSettingKey") String lastChangeSettingKey,
                                       @Param("operator") String operator);

    @Update("""
            UPDATE nx_emergency_control_setting pending_gate
            JOIN nx_emergency_control_setting incident
              ON incident.setting_key = #{incidentSettingKey}
             AND incident.setting_value = #{expectedIncidentId}
             AND incident.is_deleted = 0
               SET pending_gate.setting_value = 'false',
                   pending_gate.operator = #{operator},
                   pending_gate.updated_at = NOW()
             WHERE pending_gate.setting_key = #{pendingSettingKey}
               AND pending_gate.setting_value = 'true'
               AND pending_gate.is_deleted = 0
            """)
    int completeAutoConfirmation(@Param("pendingSettingKey") String pendingSettingKey,
                                 @Param("incidentSettingKey") String incidentSettingKey,
                                 @Param("expectedIncidentId") String expectedIncidentId,
                                 @Param("operator") String operator);

    @Update("""
            UPDATE nx_emergency_control_setting gate_state
            LEFT JOIN nx_emergency_control_setting pending_confirmation
              ON pending_confirmation.setting_key = #{pendingSettingKey}
             AND pending_confirmation.is_deleted = 0
               SET gate_state.setting_value = 'enabled',
                   gate_state.value_type = 'STRING',
                   gate_state.group_code = 'admin_killswitch',
                   gate_state.remark = 'J1 active kill switch state',
                   gate_state.operator = #{operator},
                   gate_state.updated_at = NOW(),
                   gate_state.is_deleted = 0
             WHERE gate_state.setting_key = #{settingKey}
               AND (gate_state.is_deleted = 1
                    OR LOWER(TRIM(gate_state.setting_value)) NOT IN ('enabled', 'enable', 'on', 'true', '1'))
               AND (pending_confirmation.setting_key IS NULL OR pending_confirmation.setting_value <> 'true')
            """)
    int restoreKillSwitchIfNoPending(@Param("settingKey") String settingKey,
                                     @Param("pendingSettingKey") String pendingSettingKey,
                                     @Param("operator") String operator);

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
              setting_key, setting_value, value_type, group_code, remark, operator, is_deleted
            )
            SELECT #{settingKey}, 'enabled', 'STRING', 'admin_killswitch',
                   'J1 active kill switch state', #{operator}, 0
             WHERE NOT EXISTS (
                     SELECT 1 FROM nx_emergency_control_setting existing_gate
                      WHERE existing_gate.setting_key = #{settingKey}
                   )
               AND NOT EXISTS (
                     SELECT 1 FROM nx_emergency_control_setting pending_confirmation
                      WHERE pending_confirmation.setting_key = #{pendingSettingKey}
                        AND pending_confirmation.setting_value = 'true'
                        AND pending_confirmation.is_deleted = 0
                   )
            """)
    int insertEnabledKillSwitchIfNoPending(@Param("settingKey") String settingKey,
                                           @Param("pendingSettingKey") String pendingSettingKey,
                                           @Param("operator") String operator);

    @Select("""
            SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:00') AS label,
                   SUM(event_count) AS count
              FROM nx_emergency_tamper_event
             WHERE is_deleted = 0
               AND occurred_at >= #{startAt}
               AND occurred_at < #{endAt}
             GROUP BY DATE_FORMAT(occurred_at, '%Y-%m-%d %H:00')
             ORDER BY label ASC
            """)
    List<Map<String, Object>> tamperTrend24h(@Param("startAt") LocalDateTime startAt,
                                             @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d') AS label,
                   SUM(event_count) AS count
              FROM nx_emergency_tamper_event
             WHERE is_deleted = 0
               AND occurred_at >= #{startAt}
               AND occurred_at < #{endAt}
             GROUP BY DATE_FORMAT(occurred_at, '%Y-%m-%d')
             ORDER BY label ASC
            """)
    List<Map<String, Object>> tamperTrendDaily(@Param("startAt") LocalDateTime startAt,
                                               @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT path_key AS id,
                   MAX(path_name) AS name,
                   COALESCE(MAX(description), '') AS description,
                   SUM(event_count) AS count,
                   COUNT(DISTINCT CASE
                     WHEN user_no IS NOT NULL AND user_no <> '' THEN user_no
                     WHEN user_id IS NOT NULL THEN CONCAT('U', LPAD(user_id, 8, '0'))
                     ELSE NULL
                   END) AS accounts,
                   '' AS color
             FROM nx_emergency_tamper_event
             WHERE is_deleted = 0
               AND occurred_at >= #{startAt}
               AND occurred_at < #{endAt}
             GROUP BY path_key
             ORDER BY count DESC, id ASC
             LIMIT 20
            """)
    List<Map<String, Object>> tamperPaths(@Param("startAt") LocalDateTime startAt,
                                          @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(NULLIF(e.user_no, ''), CONCAT('U', LPAD(COALESCE(e.user_id, 0), 8, '0'))) AS userCode,
                   SUM(e.event_count) AS count,
                   CASE WHEN MAX(e.k4_accepted) = 1 THEN CONCAT('+', SUM(e.k4_delta)) ELSE '未喂送' END AS k4,
                   DATE_FORMAT(MAX(e.occurred_at), '%H:%i:%s') AS last,
                   GROUP_CONCAT(DISTINCT e.path_key ORDER BY e.path_key SEPARATOR ',') AS pathCsv,
                   COALESCE(NULLIF(MAX(e.cluster_code), ''), MAX(k.cluster_code), '') AS cluster,
                   CASE WHEN MAX(e.k4_accepted) = 1 THEN TRUE ELSE FALSE END AS fedToK4,
                   CASE WHEN MAX(e.b5_accepted) = 1 THEN TRUE ELSE FALSE END AS b5Triggered,
                   CASE WHEN MAX(e.k4_accepted) = 1 AND MAX(e.b5_accepted) = 1 THEN 'escalated' ELSE 'flagged' END AS alertState
              FROM nx_emergency_tamper_event e
              LEFT JOIN (
                    SELECT ranked.user_code, ranked.cluster_id AS cluster_code
                      FROM (
                            SELECT member.user_code,
                                   c.cluster_id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY member.user_code
                                       ORDER BY CASE LOWER(c.status)
                                                    WHEN 'frozen' THEN 1
                                                    WHEN 'flagged' THEN 2
                                                    WHEN 'detected' THEN 3
                                                    ELSE 4
                                                END,
                                                c.updated_at DESC,
                                                c.id DESC
                                   ) AS row_num
                              FROM nx_admin_risk_multi_account_cluster c
                              JOIN JSON_TABLE(
                                    IF(JSON_VALID(c.nodes_json), c.nodes_json, JSON_ARRAY()),
                                    '$[*]' COLUMNS(user_code VARCHAR(64) PATH '$[0]')
                                   ) member
                             WHERE c.is_deleted = 0
                               AND LOWER(c.status) NOT IN ('cleared', 'released', 'closed')
                               AND member.user_code IS NOT NULL
                               AND member.user_code <> ''
                           ) ranked
                     WHERE ranked.row_num = 1
                   ) k
                ON k.user_code = COALESCE(NULLIF(e.user_no, ''), CONCAT('U', LPAD(COALESCE(e.user_id, 0), 8, '0')))
             WHERE e.is_deleted = 0
               AND e.occurred_at >= #{startAt}
               AND e.occurred_at < #{endAt}
             GROUP BY COALESCE(NULLIF(e.user_no, ''), CONCAT('U', LPAD(COALESCE(e.user_id, 0), 8, '0')))
            HAVING SUM(e.event_count) >= #{threshold}
             ORDER BY count DESC, MAX(e.occurred_at) DESC, userCode ASC
            """)
    List<Map<String, Object>> tamperAccounts(@Param("startAt") LocalDateTime startAt,
                                             @Param("endAt") LocalDateTime endAt,
                                             @Param("threshold") int threshold);

    @Select("""
            SELECT COUNT(*)
              FROM (
                    SELECT 1
                      FROM nx_emergency_tamper_event
                     WHERE is_deleted = 0
                       AND occurred_at >= #{startAt}
                       AND occurred_at < #{endAt}
                     GROUP BY COALESCE(NULLIF(user_no, ''), CONCAT('U', LPAD(COALESCE(user_id, 0), 8, '0')))
                    HAVING SUM(event_count) >= #{threshold}
                   ) matched_accounts
            """)
    long countTamperAccounts(@Param("startAt") LocalDateTime startAt,
                             @Param("endAt") LocalDateTime endAt,
                             @Param("threshold") int threshold);

    @Select("""
            SELECT COALESCE(NULLIF(e.user_no, ''), CONCAT('U', LPAD(COALESCE(e.user_id, 0), 8, '0'))) AS userCode,
                   SUM(e.event_count) AS count,
                   CASE WHEN MAX(e.k4_accepted) = 1 THEN CONCAT('+', SUM(e.k4_delta)) ELSE '未喂送' END AS k4,
                   DATE_FORMAT(MAX(e.occurred_at), '%H:%i:%s') AS last,
                   GROUP_CONCAT(DISTINCT e.path_key ORDER BY e.path_key SEPARATOR ',') AS pathCsv,
                   COALESCE(NULLIF(MAX(e.cluster_code), ''), MAX(k.cluster_code), '') AS cluster,
                   CASE WHEN MAX(e.k4_accepted) = 1 THEN TRUE ELSE FALSE END AS fedToK4,
                   CASE WHEN MAX(e.b5_accepted) = 1 THEN TRUE ELSE FALSE END AS b5Triggered,
                   CASE WHEN MAX(e.k4_accepted) = 1 AND MAX(e.b5_accepted) = 1 THEN 'escalated' ELSE 'flagged' END AS alertState
              FROM nx_emergency_tamper_event e
              LEFT JOIN (
                    SELECT ranked.user_code, ranked.cluster_id AS cluster_code
                      FROM (
                            SELECT member.user_code,
                                   c.cluster_id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY member.user_code
                                       ORDER BY CASE LOWER(c.status)
                                                    WHEN 'frozen' THEN 1
                                                    WHEN 'flagged' THEN 2
                                                    WHEN 'detected' THEN 3
                                                    ELSE 4
                                                END,
                                                c.updated_at DESC,
                                                c.id DESC
                                   ) AS row_num
                              FROM nx_admin_risk_multi_account_cluster c
                              JOIN JSON_TABLE(
                                    IF(JSON_VALID(c.nodes_json), c.nodes_json, JSON_ARRAY()),
                                    '$[*]' COLUMNS(user_code VARCHAR(64) PATH '$[0]')
                                   ) member
                             WHERE c.is_deleted = 0
                               AND LOWER(c.status) NOT IN ('cleared', 'released', 'closed')
                               AND member.user_code IS NOT NULL
                               AND member.user_code <> ''
                           ) ranked
                     WHERE ranked.row_num = 1
                   ) k
                ON k.user_code = COALESCE(NULLIF(e.user_no, ''), CONCAT('U', LPAD(COALESCE(e.user_id, 0), 8, '0')))
             WHERE e.is_deleted = 0
               AND e.occurred_at >= #{startAt}
               AND e.occurred_at < #{endAt}
             GROUP BY COALESCE(NULLIF(e.user_no, ''), CONCAT('U', LPAD(COALESCE(e.user_id, 0), 8, '0')))
            HAVING SUM(e.event_count) >= #{threshold}
             ORDER BY count DESC, MAX(e.occurred_at) DESC, userCode ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> pageTamperAccounts(@Param("startAt") LocalDateTime startAt,
                                                 @Param("endAt") LocalDateTime endAt,
                                                 @Param("threshold") int threshold,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    @Select("""
            SELECT frequency AS eventCount, COUNT(*) AS accountCount
              FROM (
                    SELECT SUM(event_count) AS frequency
                      FROM nx_emergency_tamper_event
                     WHERE is_deleted = 0
                       AND occurred_at >= #{startAt}
                       AND occurred_at < #{endAt}
                     GROUP BY COALESCE(NULLIF(user_no, ''), CONCAT('U', LPAD(COALESCE(user_id, 0), 8, '0')))
                   ) account_frequency
             GROUP BY frequency
             ORDER BY frequency ASC
            """)
    List<Map<String, Object>> tamperAccountFrequencyDistribution(
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    @Insert("""
            INSERT IGNORE INTO nx_emergency_tamper_event (
              event_no, user_id, user_no, path_key, path_name, description,
              k4_accepted, k4_delta, b5_accepted, event_count, occurred_at, created_at, updated_at, is_deleted
            )
            VALUES (#{event.eventNo}, #{event.userId}, #{event.userNo}, #{event.pathKey}, #{event.pathName},
                   CONCAT(#{event.attackEffect}, ' · 拦截点: ', #{event.blockedAtEndpoint}),
                   #{event.k4Accepted}, #{event.k4Delta}, #{event.b5Accepted},
                   #{event.eventCount}, #{event.occurredAt}, NOW(), NOW(), 0)
            """)
    int insertTamperEvent(@Param("event") ffdd.opsconsole.emergency.domain.TamperEventRecord event);

    @Insert("""
            INSERT INTO nx_emergency_tamper_report (
              report_id, window_label, masked, status, payload_json, operator, reason,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{reportId}, #{window}, #{masked}, #{status}, #{payloadJson}, #{operator}, #{reason},
              NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
              window_label = VALUES(window_label),
              masked = VALUES(masked),
              status = VALUES(status),
              payload_json = VALUES(payload_json),
              operator = VALUES(operator),
              reason = VALUES(reason),
              updated_at = NOW(),
              is_deleted = 0
            """)
    int insertTamperReport(@Param("reportId") String reportId,
                           @Param("window") String window,
                           @Param("masked") boolean masked,
                           @Param("status") String status,
                           @Param("payloadJson") String payloadJson,
                           @Param("operator") String operator,
                           @Param("reason") String reason);

    @Select("""
            SELECT code,
                   name,
                   scene,
                   emergency_track AS emergency,
                   sla,
                   state,
                   owner,
                   CASE WHEN last_drill_at IS NULL THEN '未演练' ELSE DATE_FORMAT(last_drill_at, '%Y-%m-%d %H:%i') END AS lastDrill,
                   notify_campaign_no AS notifyCampaignNo,
                   notify_template AS notifyTemplate,
                   rollback_plan AS rollback,
                   drill_required AS drillRequired,
                   draft,
                   summary AS customSummary,
                   DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS version,
                   (
                     SELECT execution_id
                       FROM nx_emergency_sop_execution e
                      WHERE e.is_deleted = 0 AND e.playbook_code = p.code
                      ORDER BY e.created_at DESC, e.id DESC
                      LIMIT 1
                   ) AS lastExecution
              FROM nx_emergency_sop_playbook p
             WHERE is_deleted = 0
             ORDER BY draft ASC, code ASC
            """)
    List<Map<String, Object>> playbooks();

    @Select("""
            SELECT playbook_code AS code,
                   step_order AS stepOrder,
                   domain_code AS domain,
                   action_text AS action,
                   approve_required AS approve,
                   ref_code AS ref
              FROM nx_emergency_sop_action
             WHERE is_deleted = 0
             ORDER BY playbook_code ASC, step_order ASC
            """)
    List<Map<String, Object>> playbookSteps();

    @Insert("""
            INSERT IGNORE INTO nx_emergency_control_setting (
                setting_key, setting_value, value_type, group_code, remark, operator,
                created_at, updated_at, is_deleted
            ) VALUES (
                'emergency.sop.catalogMutationLock', '1', 'INTEGER', 'admin_sop',
                'J4 playbook catalog mutation serialization lock', 'system', NOW(), NOW(), 0
            )
            """)
    int ensurePlaybookCatalogMutationLock();

    @Select("""
            SELECT setting_key
              FROM nx_emergency_control_setting
             WHERE setting_key = 'emergency.sop.catalogMutationLock'
             FOR UPDATE
            """)
    String lockPlaybookCatalogMutations();

    @Select("""
            SELECT code,
                   name,
                   scene,
                   emergency_track AS emergency,
                   sla,
                   state,
                   owner,
                   CASE WHEN last_drill_at IS NULL THEN '未演练' ELSE DATE_FORMAT(last_drill_at, '%Y-%m-%d %H:%i') END AS lastDrill,
                   notify_campaign_no AS notifyCampaignNo,
                   notify_template AS notifyTemplate,
                   rollback_plan AS rollback,
                   drill_required AS drillRequired,
                   draft,
                   summary AS customSummary,
                   DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS version
              FROM nx_emergency_sop_playbook
             WHERE code = #{code}
               AND is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    Map<String, Object> playbookForUpdate(@Param("code") String code);

    @Select("""
            SELECT playbook_code AS code,
                   step_order AS stepOrder,
                   domain_code AS domain,
                   action_text AS action,
                   approve_required AS approve,
                   ref_code AS ref
              FROM nx_emergency_sop_action
             WHERE playbook_code = #{code}
               AND is_deleted = 0
             ORDER BY step_order ASC
             FOR UPDATE
            """)
    List<Map<String, Object>> playbookStepsForUpdate(@Param("code") String code);

    @Select("SELECT code FROM nx_emergency_sop_playbook WHERE code = #{code} AND is_deleted = 0 FOR UPDATE")
    String lockPlaybook(@Param("code") String code);

    @Insert("""
            INSERT INTO nx_emergency_sop_playbook (
                code, name, scene, emergency_track, sla, state, owner,
                notify_campaign_no, notify_template, rollback_plan, drill_required, draft,
                created_by, updated_by, created_at, updated_at, is_deleted
            ) VALUES (
                #{code}, #{name}, #{scene}, #{emergency}, #{sla}, #{state}, #{owner},
                #{notifyCampaignNo}, #{notifyTemplate}, #{rollback}, #{drillRequired}, #{draft},
                #{operator}, #{operator}, NOW(), NOW(), 0
            )
            """)
    int insertPlaybook(@Param("code") String code,
                       @Param("name") String name,
                       @Param("scene") String scene,
                       @Param("emergency") boolean emergency,
                       @Param("sla") String sla,
                       @Param("state") String state,
                       @Param("owner") String owner,
                       @Param("notifyCampaignNo") String notifyCampaignNo,
                       @Param("notifyTemplate") String notifyTemplate,
                       @Param("rollback") String rollback,
                       @Param("drillRequired") boolean drillRequired,
                       @Param("draft") boolean draft,
                       @Param("operator") String operator);

    @Update("""
            <script>
            UPDATE nx_emergency_sop_playbook
               SET updated_by = #{operator},
                   updated_at = DATE_ADD(GREATEST(updated_at, NOW()), INTERVAL 1 SECOND)
             <if test='name != null'>, name = #{name}</if>
             <if test='scene != null'>, scene = #{scene}</if>
             <if test='emergency != null'>, emergency_track = #{emergency}</if>
             <if test='sla != null'>, sla = #{sla}</if>
             <if test='owner != null'>, owner = #{owner}</if>
             <if test='notifyCampaignNo != null'>, notify_campaign_no = #{notifyCampaignNo}</if>
             <if test='notifyTemplate != null'>, notify_template = #{notifyTemplate}</if>
             <if test='rollback != null'>, rollback_plan = #{rollback}</if>
             <if test='drillRequired != null'>, drill_required = #{drillRequired}</if>
              <if test='summary != null'>, summary = #{summary}</if>
             , state = #{state}
             , draft = #{draft}
             , last_drill_at = CASE WHEN #{draft} = 1 THEN NULL ELSE last_drill_at END
              WHERE code = #{code}
                AND is_deleted = 0
                AND DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') = #{expectedVersion}
            </script>
            """)
    int updatePlaybook(@Param("code") String code,
                       @Param("name") String name,
                       @Param("scene") String scene,
                       @Param("emergency") Boolean emergency,
                       @Param("sla") String sla,
                       @Param("state") String state,
                       @Param("owner") String owner,
                       @Param("notifyCampaignNo") String notifyCampaignNo,
                       @Param("notifyTemplate") String notifyTemplate,
                        @Param("rollback") String rollback,
                        @Param("drillRequired") Boolean drillRequired,
                        @Param("summary") String summary,
                        @Param("draft") boolean draft,
                        @Param("expectedVersion") String expectedVersion,
                        @Param("operator") String operator);

    @Update("UPDATE nx_emergency_sop_action SET is_deleted = 1, updated_at = NOW() WHERE playbook_code = #{code}")
    int softDeleteSteps(@Param("code") String code);

    @Insert("""
            INSERT INTO nx_emergency_sop_action (
                playbook_code, step_order, domain_code, action_text, approve_required, ref_code,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{code}, #{stepOrder}, #{domain}, #{action}, #{approve}, #{ref},
                NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                domain_code = VALUES(domain_code),
                action_text = VALUES(action_text),
                approve_required = VALUES(approve_required),
                ref_code = VALUES(ref_code),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int insertStep(@Param("code") String code,
                   @Param("stepOrder") int stepOrder,
                   @Param("domain") String domain,
                   @Param("action") String action,
                   @Param("approve") boolean approve,
                   @Param("ref") String ref);

    @Update("""
            UPDATE nx_emergency_sop_playbook
               SET last_drill_at = #{drillAt},
                   state = 'active',
                   draft = 0,
                   updated_by = #{operator},
                    updated_at = DATE_ADD(GREATEST(updated_at, NOW()), INTERVAL 1 SECOND)
             WHERE code = #{code}
               AND is_deleted = 0
            """)
    int markPlaybookDrilled(@Param("code") String code,
                            @Param("drillAt") LocalDateTime drillAt,
                            @Param("operator") String operator);

    @Select("""
            SELECT execution_id AS executionId,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS timestamp,
                   playbook_code AS code,
                   playbook_name AS name,
                   trigger_reason AS `trigger`,
                   execution_mode AS mode,
                   step_status_json AS stepsJson,
                   operator,
                   role_gate AS roleGate,
                   notification_json AS notificationJson,
                   domain_action_json AS domainActionsJson,
                   rollback_plan AS rollback,
                   rollback_status AS rollbackStatus,
                   DATE_FORMAT(rollback_at, '%Y-%m-%d %H:%i') AS rollbackAt,
                   rollback_reason AS rollbackReason,
                   rollback_action_json AS rollbackActionsJson,
                   updated_at AS updatedAt
              FROM nx_emergency_sop_execution
             WHERE is_deleted = 0
               AND execution_id = #{executionId}
             LIMIT 1
            """)
    Map<String, Object> execution(@Param("executionId") String executionId);

    @Select("""
            SELECT execution_id AS executionId,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS timestamp,
                   playbook_code AS code,
                   playbook_name AS name,
                   trigger_reason AS `trigger`,
                   execution_mode AS mode,
                   step_status_json AS stepsJson,
                   operator,
                   role_gate AS roleGate,
                   notification_json AS notificationJson,
                   domain_action_json AS domainActionsJson,
                   rollback_plan AS rollback,
                   rollback_status AS rollbackStatus,
                   DATE_FORMAT(rollback_at, '%Y-%m-%d %H:%i') AS rollbackAt,
                   rollback_reason AS rollbackReason,
                   rollback_action_json AS rollbackActionsJson,
                   updated_at AS updatedAt
              FROM nx_emergency_sop_execution
             WHERE is_deleted = 0
               AND playbook_code = #{code}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    Map<String, Object> executionByIdempotencyKey(@Param("code") String code,
                                                   @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT execution_id AS executionId,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS timestamp,
                   playbook_code AS code,
                   playbook_name AS name,
                   trigger_reason AS `trigger`,
                   execution_mode AS mode,
                   step_status_json AS stepsJson,
                   operator,
                   role_gate AS roleGate,
                   notification_json AS notificationJson,
                   domain_action_json AS domainActionsJson,
                   rollback_plan AS rollback,
                   rollback_status AS rollbackStatus,
                   DATE_FORMAT(rollback_at, '%Y-%m-%d %H:%i') AS rollbackAt,
                   rollback_reason AS rollbackReason,
                   rollback_action_json AS rollbackActionsJson,
                   updated_at AS updatedAt
              FROM nx_emergency_sop_execution
             WHERE is_deleted = 0
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> executions(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_emergency_sop_execution
             WHERE is_deleted = 0
               AND execution_mode = #{mode}
               AND created_at >= #{since}
            """)
    long countExecutionsSinceByMode(@Param("mode") String mode,
                                    @Param("since") LocalDateTime since);

    @Insert("""
            INSERT INTO nx_emergency_sop_execution (
                execution_id, playbook_code, playbook_name, trigger_reason, execution_mode,
                step_status_json, operator, role_gate, idempotency_key, notification_json,
                domain_action_json, rollback_plan, created_at, updated_at, is_deleted
            ) VALUES (
                #{executionId}, #{code}, #{name}, #{trigger}, #{mode},
                #{stepsJson}, #{operator}, #{roleGate}, #{idempotencyKey}, #{notificationJson},
                #{domainActionsJson}, #{rollback}, NOW(), NOW(), 0
            )
            """)
    int insertExecution(@Param("executionId") String executionId,
                        @Param("code") String code,
                        @Param("name") String name,
                        @Param("trigger") String trigger,
                        @Param("mode") String mode,
                        @Param("operator") String operator,
                        @Param("roleGate") String roleGate,
                        @Param("idempotencyKey") String idempotencyKey,
                        @Param("stepsJson") String stepsJson,
                        @Param("notificationJson") String notificationJson,
                        @Param("domainActionsJson") String domainActionsJson,
                         @Param("rollback") String rollback);

    @Update("""
            UPDATE nx_emergency_sop_execution
               SET step_status_json = #{stepsJson},
                   notification_json = #{notificationJson},
                   domain_action_json = #{domainActionsJson},
                   updated_at = NOW()
             WHERE execution_id = #{executionId}
               AND is_deleted = 0
               AND (rollback_status IS NULL OR rollback_status = '')
            """)
    int updateExecutionProgress(@Param("executionId") String executionId,
                                @Param("stepsJson") String stepsJson,
                                @Param("notificationJson") String notificationJson,
                                @Param("domainActionsJson") String domainActionsJson);

    @Update("""
            UPDATE nx_emergency_sop_execution
               SET updated_at = NOW()
             WHERE execution_id = #{executionId}
               AND is_deleted = 0
               AND updated_at < #{staleBefore}
               AND (JSON_CONTAINS(step_status_json, JSON_QUOTE('pending'))
                    OR JSON_CONTAINS(step_status_json, JSON_QUOTE('running')))
            """)
    int claimExecutionRecovery(@Param("executionId") String executionId,
                               @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE nx_emergency_sop_execution
               SET rollback_status = 'ROLLING_BACK',
                   updated_at = NOW()
             WHERE execution_id = #{executionId}
               AND is_deleted = 0
               AND (rollback_status IS NULL OR rollback_status = '')
               AND JSON_UNQUOTE(JSON_EXTRACT(notification_json, '$.auditStatus')) = 'AUDITED'
               AND NOT JSON_CONTAINS(step_status_json, JSON_QUOTE('pending'))
               AND NOT JSON_CONTAINS(step_status_json, JSON_QUOTE('running'))
            """)
    int claimExecutionRollback(@Param("executionId") String executionId);

    @Update("""
            UPDATE nx_emergency_sop_execution
               SET rollback_status = 'ROLLED_BACK',
                   rollback_at = #{rollbackAt},
                   rollback_reason = #{reason},
                   rollback_action_json = #{rollbackActionsJson},
                   updated_at = NOW()
             WHERE execution_id = #{executionId}
               AND is_deleted = 0
               AND rollback_status = 'ROLLING_BACK'
            """)
    int completeExecutionRollback(@Param("executionId") String executionId,
                                  @Param("rollbackAt") LocalDateTime rollbackAt,
                                  @Param("reason") String reason,
                                  @Param("rollbackActionsJson") String rollbackActionsJson);
}
