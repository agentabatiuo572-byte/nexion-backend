package ffdd.opsconsole.janus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.janus.infrastructure.JanusDeviceRecord;
import ffdd.opsconsole.janus.infrastructure.JanusStrategyRecord;
import ffdd.opsconsole.janus.infrastructure.JanusStrategyVersionRecord;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface JanusMapper extends BaseMapper<JanusDeviceRecord> {
    String DEVICE_COLUMNS = """
            sid, device_id AS deviceId,
            CAST(UNIX_TIMESTAMP(first_seen_at) * 1000 AS UNSIGNED) AS firstSeenAt,
            CAST(UNIX_TIMESTAMP(last_seen_at) * 1000 AS UNSIGNED) AS lastSeenAt,
            CAST(UNIX_TIMESTAMP(install_at) * 1000 AS UNSIGNED) AS installAt,
            DATEDIFF(CURRENT_DATE, DATE(install_at)) AS installDays,
            invite_code AS inviteCode, channel, cohort_id AS cohortId,
            reported_status AS status, desired_status AS desiredStatus, command_state AS commandState,
            status_source AS statusSource, activated, remote_url_key AS remoteUrlKey,
            maturity_score AS maturityScore, recommendation_score AS recommendationScore,
            environment_risk_score AS environmentRiskScore, priority_score AS priorityScore,
            ua, platform, model, os_name AS osName, browser,
            CAST(maturity_json AS CHAR) AS maturityJson,
            CAST(environment_json AS CHAR) AS environmentJson,
            hit_strategy AS hitStrategy, hit_strategy_version AS hitStrategyVersion,
            CAST(latest_decision_json AS CHAR) AS latestDecisionJson,
            CAST(latest_session_json AS CHAR) AS latestSessionJson,
            CAST(manual_override_json AS CHAR) AS manualOverrideJson,
            last_operator_id AS lastOperatorId, last_operation_reason AS lastOperationReason,
            activation_kind AS activationKind, CAST(tags_json AS CHAR) AS tagsJson,
            lock_version AS lockVersion
            """;

    String STRATEGY_COLUMNS = """
            strategy_id AS strategyId, name, description, status, version, priority, owner,
            CAST(scope_json AS CHAR) AS scopeJson, CAST(rule_tree_json AS CHAR) AS ruleTreeJson,
            CAST(action_json AS CHAR) AS actionJson, CAST(safeguards_json AS CHAR) AS safeguardsJson,
            CAST(rollout_json AS CHAR) AS rolloutJson, CAST(health_config_json AS CHAR) AS healthConfigJson,
            template_key AS templateKey,
            CAST(UNIX_TIMESTAMP(created_at) * 1000 AS UNSIGNED) AS createdAt,
            CAST(UNIX_TIMESTAMP(published_at) * 1000 AS UNSIGNED) AS publishedAt,
            lock_version AS lockVersion
            """;

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_device (
              sid VARCHAR(96) PRIMARY KEY,
              user_id BIGINT NOT NULL,
              device_id VARCHAR(128) NOT NULL,
              first_seen_at DATETIME(3) NOT NULL,
              last_seen_at DATETIME(3) NOT NULL,
              install_at DATETIME(3) NOT NULL,
              invite_code VARCHAR(96) DEFAULT NULL,
              channel VARCHAR(64) DEFAULT NULL,
              cohort_id VARCHAR(96) DEFAULT NULL,
              reported_status VARCHAR(32) NOT NULL DEFAULT 'NEW',
              desired_status VARCHAR(32) DEFAULT NULL,
              desired_revision BIGINT NOT NULL DEFAULT 0,
              acked_revision BIGINT NOT NULL DEFAULT 0,
              command_state VARCHAR(24) DEFAULT NULL,
              status_source VARCHAR(24) NOT NULL DEFAULT 'system',
              activated TINYINT NOT NULL DEFAULT 0,
              remote_url_key VARCHAR(64) DEFAULT NULL,
              maturity_score INT NOT NULL DEFAULT 0,
              recommendation_score INT NOT NULL DEFAULT 0,
              environment_risk_score INT NOT NULL DEFAULT 0,
              priority_score INT NOT NULL DEFAULT 0,
              ua VARCHAR(1000) DEFAULT NULL,
              platform VARCHAR(64) DEFAULT NULL,
              model VARCHAR(128) DEFAULT NULL,
              os_name VARCHAR(128) DEFAULT NULL,
              browser VARCHAR(128) DEFAULT NULL,
              maturity_json JSON NOT NULL,
              environment_json JSON NOT NULL,
              hit_strategy VARCHAR(96) DEFAULT NULL,
              hit_strategy_version INT DEFAULT NULL,
              latest_decision_json JSON DEFAULT NULL,
              latest_session_json JSON DEFAULT NULL,
              manual_override_json JSON DEFAULT NULL,
              last_operator_id VARCHAR(96) DEFAULT NULL,
              last_operation_reason VARCHAR(500) DEFAULT NULL,
              activation_kind VARCHAR(48) DEFAULT NULL,
              tags_json JSON NOT NULL,
              lock_version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              CONSTRAINT ck_janus_device_reported_status CHECK (reported_status IN ('NEW','OBSERVING','RECOMMENDED','HIT','ACTIVATED','ENV_FILTERED','MANUAL_HOLD','MANUAL_FORCED','BLOCKED','STALE','RESET','ERROR')),
              CONSTRAINT ck_janus_device_desired_status CHECK (desired_status IS NULL OR desired_status IN ('NEW','OBSERVING','RECOMMENDED','HIT','ACTIVATED','ENV_FILTERED','MANUAL_HOLD','MANUAL_FORCED','BLOCKED','STALE','RESET','ERROR')),
              UNIQUE KEY uk_janus_device_owner (user_id, device_id),
              KEY idx_janus_device_queue (reported_status, priority_score, last_seen_at),
              KEY idx_janus_device_channel (channel, reported_status),
              KEY idx_janus_device_strategy (hit_strategy),
              KEY idx_janus_device_risk (environment_risk_score)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createDeviceTable();

    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device' AND COLUMN_NAME='user_id'")
    int countDeviceUserIdColumn();

    @Update("ALTER TABLE nx_janus_device ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 AFTER sid")
    void addDeviceUserIdColumn();

    @Select("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_device' AND INDEX_NAME='uk_janus_device_owner'")
    int countDeviceOwnerIndex();

    @Update("ALTER TABLE nx_janus_device ADD UNIQUE KEY uk_janus_device_owner (user_id, device_id)")
    void addDeviceOwnerIndex();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_strategy (
              strategy_id VARCHAR(96) PRIMARY KEY,
              name VARCHAR(160) NOT NULL,
              description VARCHAR(1000) DEFAULT NULL,
              status VARCHAR(24) NOT NULL DEFAULT 'draft',
              version INT NOT NULL DEFAULT 1,
              priority INT NOT NULL DEFAULT 0,
              owner VARCHAR(96) NOT NULL,
              scope_json JSON NOT NULL,
              rule_tree_json JSON NOT NULL,
              action_json JSON NOT NULL,
              safeguards_json JSON NOT NULL,
              rollout_json JSON NOT NULL,
              health_config_json JSON NOT NULL,
              template_key VARCHAR(64) DEFAULT NULL,
              published_at DATETIME(3) DEFAULT NULL,
              lock_version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              CONSTRAINT ck_janus_strategy_status CHECK (status IN ('draft','active','paused','archived')),
              KEY idx_janus_strategy_state (status, priority)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createStrategyTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_strategy_version (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              strategy_id VARCHAR(96) NOT NULL,
              version INT NOT NULL,
              note VARCHAR(500) NOT NULL,
              actor_id VARCHAR(96) NOT NULL,
              snapshot_json JSON NOT NULL,
              config_hash CHAR(64) NOT NULL,
              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              UNIQUE KEY uk_janus_strategy_version (strategy_id, version),
              KEY idx_janus_strategy_version_time (strategy_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createStrategyVersionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_evaluation (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              report_id VARCHAR(128) NOT NULL,
              sid VARCHAR(96) NOT NULL,
              session_id VARCHAR(128) DEFAULT NULL,
              strategy_id VARCHAR(96) DEFAULT NULL,
              strategy_version INT DEFAULT NULL,
              input_snapshot_json JSON NOT NULL,
              rule_results_json JSON NOT NULL,
              action VARCHAR(48) DEFAULT NULL,
              recommended_status VARCHAR(32) DEFAULT NULL,
              error_code VARCHAR(96) DEFAULT NULL,
              elapsed_ms INT DEFAULT NULL,
              engine_version VARCHAR(64) NOT NULL,
              evaluated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              UNIQUE KEY uk_janus_evaluation_report (sid, report_id),
              KEY idx_janus_evaluation_strategy (strategy_id, strategy_version, evaluated_at),
              KEY idx_janus_evaluation_time (evaluated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createEvaluationTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_daily_quota (
              strategy_id VARCHAR(96) NOT NULL,
              quota_day DATE NOT NULL,
              action VARCHAR(48) NOT NULL,
              used_count INT NOT NULL DEFAULT 0,
              updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (strategy_id, quota_day, action)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createDailyQuotaTable();

    @Insert("""
            INSERT INTO nx_janus_daily_quota(strategy_id,quota_day,action,used_count)
            SELECT strategy_id,CURRENT_DATE,action,COUNT(*) FROM nx_janus_evaluation
            WHERE strategy_id IS NOT NULL AND action IS NOT NULL AND evaluated_at>=CURRENT_DATE
            GROUP BY strategy_id,action
            ON DUPLICATE KEY UPDATE used_count=GREATEST(used_count,VALUES(used_count))
            """)
    int syncDailyQuotaFromEvaluations();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_dry_run (
              dry_run_id VARCHAR(96) PRIMARY KEY,
              strategy_id VARCHAR(96) NOT NULL,
              expected_version BIGINT NOT NULL,
              config_hash CHAR(64) NOT NULL,
              result_json JSON NOT NULL,
              actor_id VARCHAR(96) NOT NULL,
              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              expires_at DATETIME(3) NOT NULL,
              KEY idx_janus_dry_run_strategy (strategy_id, expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createDryRunTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_janus_command (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              idempotency_key VARCHAR(128) NOT NULL,
              command_type VARCHAR(64) NOT NULL,
              target_id VARCHAR(96) NOT NULL,
              request_hash CHAR(64) NOT NULL,
              actor_id VARCHAR(96) NOT NULL,
              state VARCHAR(24) NOT NULL,
              payload_json JSON NOT NULL,
              created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              expires_at DATETIME(3) DEFAULT NULL,
              UNIQUE KEY uk_janus_command_idem (idempotency_key),
              KEY idx_janus_command_target (target_id, created_at),
              KEY idx_janus_command_expiry (expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createCommandTable();

    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_janus_command' AND COLUMN_NAME='expires_at'")
    int countCommandExpiresAtColumn();

    @Update("ALTER TABLE nx_janus_command ADD COLUMN expires_at DATETIME(3) DEFAULT NULL AFTER created_at, ADD KEY idx_janus_command_expiry (expires_at)")
    void addCommandExpiresAtColumn();

    @Update("UPDATE nx_janus_command SET expires_at=DATE_ADD(created_at, INTERVAL 24 HOUR) WHERE expires_at IS NULL")
    void expireLegacyCommands();

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_janus_device
            <where>
              <if test="q != null and q != ''">AND (sid LIKE CONCAT('%', #{q}, '%') OR device_id LIKE CONCAT('%', #{q}, '%') OR invite_code LIKE CONCAT('%', #{q}, '%') OR model LIKE CONCAT('%', #{q}, '%') OR ua LIKE CONCAT('%', #{q}, '%'))</if>
              <if test="status != null and status != ''">AND reported_status = #{status}</if>
              <if test="channel != null and channel != ''">AND channel = #{channel}</if>
              <if test="strategyId != null and strategyId != ''">AND hit_strategy = #{strategyId}</if>
              <if test="riskBand == 'low'">AND environment_risk_score &lt; 40</if>
              <if test="riskBand == 'medium'">AND environment_risk_score BETWEEN 40 AND 59</if>
              <if test="riskBand == 'high'">AND environment_risk_score &gt;= 60</if>
            </where>
            </script>
            """)
    long countDevices(@Param("q") String q, @Param("status") String status, @Param("riskBand") String riskBand,
                      @Param("channel") String channel, @Param("strategyId") String strategyId);

    @Select("""
            <script>
            SELECT
            """ + DEVICE_COLUMNS + """
            FROM nx_janus_device
            <where>
              <if test="q != null and q != ''">AND (sid LIKE CONCAT('%', #{q}, '%') OR device_id LIKE CONCAT('%', #{q}, '%') OR invite_code LIKE CONCAT('%', #{q}, '%') OR model LIKE CONCAT('%', #{q}, '%') OR ua LIKE CONCAT('%', #{q}, '%'))</if>
              <if test="status != null and status != ''">AND reported_status = #{status}</if>
              <if test="channel != null and channel != ''">AND channel = #{channel}</if>
              <if test="strategyId != null and strategyId != ''">AND hit_strategy = #{strategyId}</if>
              <if test="riskBand == 'low'">AND environment_risk_score &lt; 40</if>
              <if test="riskBand == 'medium'">AND environment_risk_score BETWEEN 40 AND 59</if>
              <if test="riskBand == 'high'">AND environment_risk_score &gt;= 60</if>
            </where>
            ORDER BY priority_score DESC, last_seen_at DESC LIMIT #{offset}, #{limit}
            </script>
            """)
    List<JanusDeviceRecord> pageDevices(@Param("q") String q, @Param("status") String status,
                                         @Param("riskBand") String riskBand, @Param("channel") String channel,
                                         @Param("strategyId") String strategyId, @Param("offset") long offset,
                                         @Param("limit") int limit);

    @Select("SELECT " + DEVICE_COLUMNS + " FROM nx_janus_device WHERE sid = #{sid}")
    JanusDeviceRecord findDevice(@Param("sid") String sid);

    @Insert("""
            INSERT INTO nx_janus_device(
              sid,user_id,device_id,first_seen_at,last_seen_at,install_at,invite_code,channel,cohort_id,
              reported_status,status_source,activated,maturity_score,recommendation_score,environment_risk_score,
              priority_score,ua,platform,model,os_name,browser,maturity_json,environment_json,hit_strategy,
              hit_strategy_version,latest_decision_json,latest_session_json,tags_json)
            VALUES(
              #{sid},#{userId},#{r.deviceId},FROM_UNIXTIME(#{r.firstSeenAt}/1000),FROM_UNIXTIME(#{r.reportedAt}/1000),
              FROM_UNIXTIME(#{r.installAt}/1000),#{r.inviteCode},#{r.channel},#{r.cohortId},#{r.reportedStatus},
              'system',#{r.activated},#{r.maturityScore},#{r.recommendationScore},#{r.environmentRiskScore},
              #{r.priorityScore},#{r.ua},#{r.platform},#{r.model},#{r.osName},#{r.browser},CAST(#{maturityJson} AS JSON),
              CAST(#{environmentJson} AS JSON),#{r.hitStrategy},#{r.hitStrategyVersion},CAST(#{latestDecisionJson} AS JSON),
              CAST(#{latestSessionJson} AS JSON),CAST(#{tagsJson} AS JSON))
            ON DUPLICATE KEY UPDATE
              user_id=IF(user_id=0,VALUES(user_id),user_id),
              invite_code=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(invite_code),invite_code),
              channel=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(channel),channel),
              cohort_id=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(cohort_id),cohort_id),
              reported_status=IF(VALUES(last_seen_at)>=last_seen_at AND (desired_status IS NULL OR acked_revision<desired_revision OR status_source<>'manual'),VALUES(reported_status),reported_status),
              activated=IF(VALUES(last_seen_at)>=last_seen_at AND (desired_status IS NULL OR acked_revision<desired_revision OR status_source<>'manual'),VALUES(activated),activated),
              maturity_score=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(maturity_score),maturity_score),
              recommendation_score=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(recommendation_score),recommendation_score),
              environment_risk_score=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(environment_risk_score),environment_risk_score),
              priority_score=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(priority_score),priority_score),
              ua=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(ua),ua),
              platform=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(platform),platform),
              model=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(model),model),
              os_name=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(os_name),os_name),
              browser=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(browser),browser),
              maturity_json=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(maturity_json),maturity_json),
              environment_json=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(environment_json),environment_json),
              hit_strategy=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(hit_strategy),hit_strategy),
              hit_strategy_version=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(hit_strategy_version),hit_strategy_version),
              latest_decision_json=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(latest_decision_json),latest_decision_json),
              latest_session_json=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(latest_session_json),latest_session_json),
              tags_json=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(tags_json),tags_json),
              first_seen_at=LEAST(first_seen_at,VALUES(first_seen_at)),
              install_at=LEAST(install_at,VALUES(install_at)),
              last_seen_at=GREATEST(last_seen_at,VALUES(last_seen_at))
            """)
    void upsertDeviceReport(@Param("userId") long userId, @Param("sid") String sid,
                            @Param("r") JanusDeviceReportRequest request,
                            @Param("maturityJson") String maturityJson,
                            @Param("environmentJson") String environmentJson,
                            @Param("latestDecisionJson") String latestDecisionJson,
                            @Param("latestSessionJson") String latestSessionJson,
                            @Param("tagsJson") String tagsJson);

    @Insert("""
            INSERT IGNORE INTO nx_janus_evaluation(
              report_id,sid,request_hash,session_id,strategy_id,strategy_version,input_snapshot_json,rule_results_json,
              action,recommended_status,error_code,elapsed_ms,engine_version)
            VALUES(#{reportId},#{sid},#{requestHash},#{sessionId},#{strategyId},#{strategyVersion},
              CAST(#{inputSnapshotJson} AS JSON),CAST(#{ruleResultsJson} AS JSON),#{action},
              #{recommendedStatus},#{errorCode},#{elapsedMs},#{engineVersion})
            """)
    int insertEvaluation(@Param("sid") String sid, @Param("reportId") String reportId,
                         @Param("requestHash") String requestHash,
                         @Param("sessionId") String sessionId, @Param("strategyId") String strategyId,
                         @Param("strategyVersion") Integer strategyVersion,
                         @Param("inputSnapshotJson") String inputSnapshotJson,
                         @Param("ruleResultsJson") String ruleResultsJson, @Param("action") String action,
                         @Param("recommendedStatus") String recommendedStatus,
                         @Param("errorCode") String errorCode, @Param("elapsedMs") int elapsedMs,
                         @Param("engineVersion") String engineVersion);

    @Select("SELECT request_hash FROM nx_janus_evaluation WHERE sid=#{sid} AND report_id=#{reportId}")
    String findEvaluationRequestHash(@Param("sid") String sid, @Param("reportId") String reportId);

    @Insert("""
            INSERT INTO nx_janus_daily_quota(strategy_id,quota_day,action,used_count)
            VALUES(#{strategyId},CURRENT_DATE,#{action},LAST_INSERT_ID(IF(#{cap}>0,1,0)))
            ON DUPLICATE KEY UPDATE
              used_count=IF(used_count<#{cap},LAST_INSERT_ID(used_count+1),used_count+LAST_INSERT_ID(0))
            """)
    int reserveDailyEvaluation(@Param("strategyId") String strategyId, @Param("action") String action,
                               @Param("cap") int cap);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE nx_janus_daily_quota SET used_count=GREATEST(0,used_count-1)
            WHERE strategy_id=#{strategyId} AND quota_day=CURRENT_DATE AND action=#{action}
            """)
    int releaseDailyEvaluation(@Param("strategyId") String strategyId, @Param("action") String action);

    @Update("""
            UPDATE nx_janus_device SET desired_status=NULL,command_state=NULL,remote_url_key=NULL,
              manual_override_json=NULL,status_source='system',
              acked_revision=GREATEST(acked_revision,desired_revision),lock_version=lock_version+1
            WHERE user_id=#{userId} AND sid=#{sid} AND desired_status IS NOT NULL
              AND CAST(JSON_UNQUOTE(JSON_EXTRACT(manual_override_json,'$.expireAt')) AS UNSIGNED)>0
              AND CAST(JSON_UNQUOTE(JSON_EXTRACT(manual_override_json,'$.expireAt')) AS UNSIGNED)<=#{now}
            """)
    int expireDeviceOverride(@Param("userId") long userId, @Param("sid") String sid, @Param("now") long now);

    @Update("""
            UPDATE nx_janus_device SET desired_status=NULL,command_state=NULL,remote_url_key=NULL,
              manual_override_json=NULL,status_source='system',
              acked_revision=GREATEST(acked_revision,desired_revision),lock_version=lock_version+1
            WHERE desired_status IS NOT NULL
              AND CAST(JSON_UNQUOTE(JSON_EXTRACT(manual_override_json,'$.expireAt')) AS UNSIGNED)>0
              AND CAST(JSON_UNQUOTE(JSON_EXTRACT(manual_override_json,'$.expireAt')) AS UNSIGNED)<=#{now}
            """)
    int expireDeviceOverrides(@Param("now") long now);

    @Select("""
            SELECT desired_status AS desiredStatus, desired_revision AS revision,
                   remote_url_key AS remoteUrlKey, command_state AS commandState,
                   CAST(manual_override_json AS CHAR) AS payloadJson
            FROM nx_janus_device
            WHERE user_id=#{userId} AND sid=#{sid} AND desired_revision>acked_revision
              AND command_state IN ('PENDING','PUBLISHED')
            """)
    Map<String, Object> findPendingDeviceCommand(@Param("userId") long userId, @Param("sid") String sid);

    @Update("""
            UPDATE nx_janus_device SET
              reported_status=CASE WHEN #{success}=1 THEN desired_status ELSE reported_status END,
              activated=CASE WHEN #{success}=1 THEN desired_status='ACTIVATED' ELSE activated END,
              status_source=CASE
                WHEN #{success}=1 AND JSON_UNQUOTE(JSON_EXTRACT(manual_override_json,'$.source'))='strategy' THEN 'strategy'
                WHEN #{success}=1 THEN 'manual'
                ELSE status_source END,
              acked_revision=desired_revision,
              command_state=CASE WHEN #{success}=1 THEN 'ACKED' ELSE 'FAILED' END,
              lock_version=lock_version+1
            WHERE user_id=#{userId} AND sid=#{sid} AND desired_revision=#{revision}
              AND acked_revision<#{revision} AND command_state IN ('PENDING','PUBLISHED')
              AND (#{success}=0 OR desired_status=#{appliedStatus})
            """)
    int acknowledgeDeviceCommand(@Param("userId") long userId, @Param("sid") String sid,
                                 @Param("revision") long revision, @Param("success") boolean success,
                                 @Param("appliedStatus") String appliedStatus);

    @Select("""
            SELECT COUNT(1) FROM nx_janus_device
            WHERE user_id=#{userId} AND sid=#{sid} AND acked_revision>=#{revision}
              AND (desired_revision>#{revision} OR (desired_revision=#{revision}
                AND (command_state=CASE WHEN #{success}=1 THEN 'ACKED' ELSE 'FAILED' END
                  OR (command_state IS NULL AND desired_status IS NULL))
                AND (command_state IS NULL OR #{success}=0 OR reported_status=#{appliedStatus})))
            """)
    int countDeviceCommandAckReplay(@Param("userId") long userId, @Param("sid") String sid,
                                    @Param("revision") long revision, @Param("success") boolean success,
                                    @Param("appliedStatus") String appliedStatus);

    @Update("UPDATE nx_janus_command SET state=#{state} WHERE target_id=#{sid} AND command_type='DEVICE_STATUS' AND state IN ('PENDING','PUBLISHED')")
    int updateDeviceCommandRecord(@Param("sid") String sid, @Param("state") String state);

    @Update("""
            UPDATE nx_janus_device SET desired_status = #{targetStatus}, desired_revision = desired_revision + 1,
              command_state = #{commandState},
              remote_url_key = COALESCE(#{remoteUrlKey}, remote_url_key), last_operator_id = #{operator},
              last_operation_reason = #{reason}, manual_override_json = CAST(#{manualOverrideJson} AS JSON),
              lock_version = lock_version + 1
            WHERE sid = #{sid} AND lock_version = #{expectedVersion}
            """)
    int updateDeviceStatus(@Param("sid") String sid, @Param("expectedVersion") long expectedVersion,
                           @Param("targetStatus") String targetStatus,
                           @Param("remoteUrlKey") String remoteUrlKey, @Param("operator") String operator,
                           @Param("reason") String reason, @Param("manualOverrideJson") String manualOverrideJson,
                           @Param("commandState") String commandState);

    @Update("""
            UPDATE nx_janus_device SET desired_status=#{targetStatus},desired_revision=desired_revision+1,
              command_state='PUBLISHED',remote_url_key=#{remoteUrlKey},
              manual_override_json=CAST(#{payloadJson} AS JSON),status_source='strategy',
              last_operator_id='janus-engine',last_operation_reason='策略自动判定',
              lock_version=lock_version+1
            WHERE sid=#{sid} AND lock_version=#{expectedVersion}
              AND (desired_status IS NULL OR (acked_revision>=desired_revision AND status_source<>'manual'
                AND (command_state='FAILED' OR desired_status<>#{targetStatus}
                  OR COALESCE(remote_url_key,'')<>COALESCE(#{remoteUrlKey},''))))
            """)
    int publishStrategyCommand(@Param("sid") String sid, @Param("expectedVersion") long expectedVersion,
                               @Param("targetStatus") String targetStatus,
                               @Param("remoteUrlKey") String remoteUrlKey,
                               @Param("payloadJson") String payloadJson);

    @Select("SELECT " + STRATEGY_COLUMNS + " FROM nx_janus_strategy ORDER BY priority DESC, updated_at DESC")
    List<JanusStrategyRecord> strategies();

    @Select("SELECT " + STRATEGY_COLUMNS + " FROM nx_janus_strategy WHERE strategy_id = #{strategyId}")
    JanusStrategyRecord findStrategy(@Param("strategyId") String strategyId);

    @Insert("""
            INSERT INTO nx_janus_strategy(strategy_id,name,description,status,version,priority,owner,scope_json,rule_tree_json,action_json,safeguards_json,rollout_json,health_config_json,template_key)
            VALUES(#{strategyId},#{name},#{description},'draft',1,#{priority},#{owner},CAST(#{scopeJson} AS JSON),CAST(#{ruleTreeJson} AS JSON),CAST(#{actionJson} AS JSON),CAST(#{safeguardsJson} AS JSON),CAST(#{rolloutJson} AS JSON),CAST(#{healthConfigJson} AS JSON),#{templateKey})
            """)
    void insertStrategy(@Param("strategyId") String strategyId, @Param("name") String name,
                        @Param("description") String description, @Param("priority") int priority,
                        @Param("owner") String owner, @Param("scopeJson") String scopeJson,
                        @Param("ruleTreeJson") String ruleTreeJson, @Param("actionJson") String actionJson,
                        @Param("safeguardsJson") String safeguardsJson, @Param("rolloutJson") String rolloutJson,
                        @Param("healthConfigJson") String healthConfigJson, @Param("templateKey") String templateKey);

    @Update("""
            UPDATE nx_janus_strategy SET name=#{name},description=#{description},priority=#{priority},owner=#{owner},
              scope_json=CAST(#{scopeJson} AS JSON),rule_tree_json=CAST(#{ruleTreeJson} AS JSON),action_json=CAST(#{actionJson} AS JSON),
              safeguards_json=CAST(#{safeguardsJson} AS JSON),rollout_json=CAST(#{rolloutJson} AS JSON),health_config_json=CAST(#{healthConfigJson} AS JSON),
              template_key=#{templateKey},lock_version=lock_version+1
            WHERE strategy_id=#{strategyId} AND lock_version=#{expectedVersion} AND status='draft'
            """)
    int updateStrategy(@Param("strategyId") String strategyId, @Param("expectedVersion") long expectedVersion,
                       @Param("name") String name, @Param("description") String description,
                       @Param("priority") int priority, @Param("owner") String owner,
                       @Param("scopeJson") String scopeJson, @Param("ruleTreeJson") String ruleTreeJson,
                       @Param("actionJson") String actionJson, @Param("safeguardsJson") String safeguardsJson,
                       @Param("rolloutJson") String rolloutJson, @Param("healthConfigJson") String healthConfigJson,
                       @Param("templateKey") String templateKey);

    @Update("UPDATE nx_janus_strategy SET status=#{status},version=#{version},published_at=FROM_UNIXTIME(#{publishedAt}/1000),lock_version=lock_version+1 WHERE strategy_id=#{strategyId} AND lock_version=#{expectedVersion}")
    int updateStrategyLifecycle(@Param("strategyId") String strategyId, @Param("expectedVersion") long expectedVersion,
                                @Param("status") String status, @Param("version") int version,
                                @Param("publishedAt") Long publishedAt);

    @Update("""
            UPDATE nx_janus_strategy SET
              name=JSON_UNQUOTE(JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.name')),
              description=JSON_UNQUOTE(JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.description')),
              priority=CAST(JSON_UNQUOTE(JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.priority')) AS SIGNED),
              owner=JSON_UNQUOTE(JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.owner')),
              scope_json=JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.scope'),
              rule_tree_json=JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.ruleTree'),
              action_json=JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.action'),
              safeguards_json=JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.safeguards'),
              rollout_json=JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.rollout'),
              health_config_json=JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.healthConfig'),
              template_key=JSON_UNQUOTE(JSON_EXTRACT(CAST(#{snapshotJson} AS JSON),'$.templateKey')),
              status=#{status},version=#{version},lock_version=lock_version+1
            WHERE strategy_id=#{strategyId} AND lock_version=#{expectedVersion}
            """)
    int replaceStrategyFromSnapshot(@Param("strategyId") String strategyId, @Param("expectedVersion") long expectedVersion,
                                    @Param("version") int version, @Param("status") String status,
                                    @Param("snapshotJson") String snapshotJson);

    @Delete("DELETE FROM nx_janus_strategy WHERE strategy_id=#{strategyId} AND lock_version=#{expectedVersion} AND status='draft'")
    int deleteStrategy(@Param("strategyId") String strategyId, @Param("expectedVersion") long expectedVersion);

    @Insert("INSERT INTO nx_janus_strategy_version(strategy_id,version,note,actor_id,snapshot_json,config_hash) VALUES(#{strategyId},#{version},#{note},#{actorId},CAST(#{snapshotJson} AS JSON),#{configHash})")
    void insertStrategyVersion(@Param("strategyId") String strategyId, @Param("version") int version,
                               @Param("note") String note, @Param("actorId") String actorId,
                               @Param("snapshotJson") String snapshotJson, @Param("configHash") String configHash);

    @Select("SELECT version,note,actor_id AS actorId,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS createdAt,CAST(snapshot_json AS CHAR) AS snapshotJson,config_hash AS configHash FROM nx_janus_strategy_version WHERE strategy_id=#{strategyId} ORDER BY version DESC")
    List<JanusStrategyVersionRecord> strategyVersions(@Param("strategyId") String strategyId);

    @Select("SELECT version,note,actor_id AS actorId,CAST(UNIX_TIMESTAMP(created_at)*1000 AS UNSIGNED) AS createdAt,CAST(snapshot_json AS CHAR) AS snapshotJson,config_hash AS configHash FROM nx_janus_strategy_version WHERE strategy_id=#{strategyId} AND version=#{version}")
    JanusStrategyVersionRecord findStrategyVersion(@Param("strategyId") String strategyId, @Param("version") int version);

    @Insert("INSERT INTO nx_janus_dry_run(dry_run_id,strategy_id,expected_version,config_hash,result_json,actor_id,expires_at) VALUES(#{dryRunId},#{strategyId},#{expectedVersion},#{configHash},CAST(#{resultJson} AS JSON),#{actorId},FROM_UNIXTIME(#{expiresAt}/1000))")
    void insertDryRun(@Param("dryRunId") String dryRunId, @Param("strategyId") String strategyId,
                      @Param("expectedVersion") long expectedVersion, @Param("configHash") String configHash,
                      @Param("resultJson") String resultJson, @Param("actorId") String actorId,
                      @Param("expiresAt") long expiresAt);

    @Select("SELECT dry_run_id AS dryRunId,strategy_id AS strategyId,expected_version AS expectedVersion,config_hash AS configHash,CAST(result_json AS CHAR) AS resultJson,actor_id AS actorId,CAST(UNIX_TIMESTAMP(expires_at)*1000 AS UNSIGNED) AS expiresAt FROM nx_janus_dry_run WHERE dry_run_id=#{dryRunId}")
    Map<String, Object> findDryRun(@Param("dryRunId") String dryRunId);

    @Select("SELECT idempotency_key AS idempotencyKey,command_type AS commandType,target_id AS targetId,request_hash AS requestHash,actor_id AS actorId,state,CAST(payload_json AS CHAR) AS payloadJson FROM nx_janus_command WHERE idempotency_key=#{idempotencyKey} AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(3))")
    Map<String, Object> findCommand(@Param("idempotencyKey") String idempotencyKey);

    @Delete("DELETE FROM nx_janus_command WHERE idempotency_key=#{idempotencyKey} AND expires_at<=CURRENT_TIMESTAMP(3)")
    int deleteExpiredCommand(@Param("idempotencyKey") String idempotencyKey);

    @Insert("INSERT INTO nx_janus_command(idempotency_key,command_type,target_id,request_hash,actor_id,state,payload_json,expires_at) VALUES(#{idempotencyKey},#{commandType},#{targetId},#{requestHash},#{actorId},'PROCESSING',JSON_OBJECT(),DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR))")
    int insertCommandReservation(@Param("idempotencyKey") String idempotencyKey,
                                 @Param("commandType") String commandType, @Param("targetId") String targetId,
                                 @Param("requestHash") String requestHash, @Param("actorId") String actorId);

    @Update("UPDATE nx_janus_command SET state=#{state},payload_json=CAST(#{payloadJson} AS JSON) WHERE idempotency_key=#{idempotencyKey} AND state='PROCESSING'")
    int completeCommand(@Param("idempotencyKey") String idempotencyKey, @Param("state") String state,
                        @Param("payloadJson") String payloadJson);

    @Delete("DELETE FROM nx_janus_command WHERE idempotency_key=#{idempotencyKey} AND state='PROCESSING'")
    int releaseCommandReservation(@Param("idempotencyKey") String idempotencyKey);

}
