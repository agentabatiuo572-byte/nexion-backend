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
              event_no VARCHAR(96) NULL,
              user_id BIGINT NULL,
              user_no VARCHAR(32) NULL,
              path_key VARCHAR(64) NOT NULL,
              path_name VARCHAR(128) NOT NULL,
              description VARCHAR(500) NULL,
              cluster_code VARCHAR(64) NULL,
              k4_delta INT NOT NULL DEFAULT 0,
              event_count INT NOT NULL DEFAULT 1,
              occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_tamper_event_path_time (path_key, occurred_at),
              KEY idx_tamper_event_user_time (user_id, user_no, occurred_at),
              KEY idx_tamper_event_cluster (cluster_code, occurred_at)
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
                   COALESCE(reason, '') AS reason
              FROM nx_emergency_geo_country_policy
             WHERE is_deleted = 0
             ORDER BY FIELD(policy_status, 'blocked', 'limited', 'allowed'), country_code
            """)
    List<Map<String, Object>> geoCountryPolicies();

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
            SELECT CONCAT('边缘源 ', COALESCE(source, 'unknown')) AS `key`,
                   CONCAT(SUM(event_count), ' 次 / 24h') AS value,
                   'ok' AS tone
              FROM nx_emergency_geo_block_event
             WHERE is_deleted = 0
               AND recorded_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
             GROUP BY COALESCE(source, 'unknown')
             ORDER BY SUM(event_count) DESC
             LIMIT 6
            """)
    List<Map<String, Object>> geoEdgeMetrics();

    @Select("""
            SELECT setting_value
              FROM nx_emergency_control_setting
             WHERE setting_key = #{settingKey}
               AND is_deleted = 0
             LIMIT 1
            """)
    String settingValue(@Param("settingKey") String settingKey);

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
               AND occurred_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)
             GROUP BY path_key
             ORDER BY count DESC, id ASC
             LIMIT 20
            """)
    List<Map<String, Object>> tamperPaths();

    @Select("""
            SELECT COALESCE(user_no, CONCAT('U', LPAD(COALESCE(user_id, 0), 8, '0'))) AS userCode,
                   SUM(event_count) AS count,
                   CONCAT('+', SUM(k4_delta)) AS k4,
                   DATE_FORMAT(MAX(occurred_at), '%H:%i:%s') AS last,
                   GROUP_CONCAT(DISTINCT path_key ORDER BY path_key SEPARATOR ',') AS pathCsv,
                   COALESCE(MAX(cluster_code), '') AS cluster
              FROM nx_emergency_tamper_event
             WHERE is_deleted = 0
               AND occurred_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
             GROUP BY COALESCE(user_no, CONCAT('U', LPAD(COALESCE(user_id, 0), 8, '0')))
             ORDER BY count DESC, last DESC
             LIMIT 200
            """)
    List<Map<String, Object>> tamperAccounts();

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
                   updated_at = NOW()
             <if test='name != null'>, name = #{name}</if>
             <if test='scene != null'>, scene = #{scene}</if>
             <if test='emergency != null'>, emergency_track = #{emergency}</if>
             <if test='sla != null'>, sla = #{sla}</if>
             <if test='state != null'>, state = #{state}</if>
             <if test='owner != null'>, owner = #{owner}</if>
             <if test='notifyCampaignNo != null'>, notify_campaign_no = #{notifyCampaignNo}</if>
             <if test='notifyTemplate != null'>, notify_template = #{notifyTemplate}</if>
             <if test='rollback != null'>, rollback_plan = #{rollback}</if>
             <if test='drillRequired != null'>, drill_required = #{drillRequired}</if>
             <if test='summary != null'>, summary = #{summary}</if>
             WHERE code = #{code}
               AND is_deleted = 0
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
                   updated_by = #{operator},
                   updated_at = NOW()
             WHERE code = #{code}
               AND is_deleted = 0
            """)
    int markPlaybookDrilled(@Param("code") String code,
                            @Param("drillAt") LocalDateTime drillAt,
                            @Param("operator") String operator);

    @Select("""
            SELECT execution_id AS executionId,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS timestamp,
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
                   rollback_action_json AS rollbackActionsJson
              FROM nx_emergency_sop_execution
             WHERE is_deleted = 0
               AND execution_id = #{executionId}
             LIMIT 1
            """)
    Map<String, Object> execution(@Param("executionId") String executionId);

    @Select("""
            SELECT execution_id AS executionId,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS timestamp,
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
                   rollback_action_json AS rollbackActionsJson
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
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS timestamp,
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
                   rollback_action_json AS rollbackActionsJson
              FROM nx_emergency_sop_execution
             WHERE is_deleted = 0
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> executions(@Param("limit") int limit);

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
               SET rollback_status = 'ROLLED_BACK',
                   rollback_at = #{rollbackAt},
                   rollback_reason = #{reason},
                   rollback_action_json = #{rollbackActionsJson},
                   updated_at = NOW()
             WHERE execution_id = #{executionId}
               AND is_deleted = 0
            """)
    int markExecutionRolledBack(@Param("executionId") String executionId,
                                @Param("rollbackAt") LocalDateTime rollbackAt,
                                @Param("reason") String reason,
                                @Param("rollbackActionsJson") String rollbackActionsJson);
}
