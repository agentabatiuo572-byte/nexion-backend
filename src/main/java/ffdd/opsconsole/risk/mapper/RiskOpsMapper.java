package ffdd.opsconsole.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.risk.domain.RiskArbitrageParamView;
import ffdd.opsconsole.risk.domain.RiskArbitrageStatView;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskRouteCountView;
import ffdd.opsconsole.risk.domain.RiskRuleHitView;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.domain.RiskScoreDimensionView;
import ffdd.opsconsole.risk.domain.RiskScoreOverrideView;
import ffdd.opsconsole.risk.domain.RiskWithdrawCandidateView;
import ffdd.opsconsole.risk.infrastructure.RiskDecisionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RiskOpsMapper extends BaseMapper<RiskDecisionEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_risk_decision (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              decision_no VARCHAR(64) NOT NULL,
              user_id BIGINT NOT NULL,
              biz_type VARCHAR(64) NOT NULL,
              biz_no VARCHAR(128) NOT NULL,
              region VARCHAR(32) DEFAULT NULL,
              user_level VARCHAR(32) DEFAULT NULL,
              client_ip VARCHAR(64) DEFAULT NULL,
              device_fingerprint VARCHAR(128) DEFAULT NULL,
              decision VARCHAR(64) DEFAULT 'REVIEW',
              reason VARCHAR(500) DEFAULT NULL,
              risk_score INT DEFAULT 0,
              rule_codes VARCHAR(512) DEFAULT NULL,
              rule_snapshot TEXT DEFAULT NULL,
              reviewed_by VARCHAR(64) DEFAULT NULL,
              reviewed_at DATETIME DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_risk_decision_no (decision_no),
              KEY idx_risk_decision_user (user_id,is_deleted),
              KEY idx_risk_decision_status (decision,reviewed_at,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRiskDecisionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_risk_signal (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              signal_no VARCHAR(64) NOT NULL,
              user_id BIGINT NOT NULL,
              signal_type VARCHAR(64) NOT NULL,
              severity VARCHAR(32) NOT NULL,
              evidence TEXT DEFAULT NULL,
              created_by VARCHAR(64) DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_risk_signal_no (signal_no),
              KEY idx_risk_signal_user (user_id,is_deleted),
              KEY idx_risk_signal_type (signal_type,severity,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRiskSignalTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_withdraw_rule (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              rule_id VARCHAR(64) NOT NULL,
              dimension VARCHAR(64) NOT NULL,
              condition_text VARCHAR(1000) NOT NULL,
              action VARCHAR(32) NOT NULL,
              state VARCHAR(32) NOT NULL DEFAULT 'draft',
              built_in TINYINT NOT NULL DEFAULT 0,
              created_by VARCHAR(64) DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_withdraw_rule (rule_id),
              KEY idx_admin_risk_withdraw_rule_state (state,is_deleted),
              KEY idx_admin_risk_withdraw_rule_dimension (dimension,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createWithdrawRuleTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_route_count (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              route_key VARCHAR(32) NOT NULL,
              label VARCHAR(32) NOT NULL,
              count_value BIGINT NOT NULL DEFAULT 0,
              color VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_route_count (route_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRouteCountTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_withdraw_hit (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              withdrawal_no VARCHAR(64) NOT NULL,
              user_no VARCHAR(64) NOT NULL,
              amount_text VARCHAR(64) NOT NULL,
              rule_id VARCHAR(64) NOT NULL,
              dimension VARCHAR(64) NOT NULL,
              action VARCHAR(32) NOT NULL,
              time_text VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_admin_risk_withdraw_hit_action (action,is_deleted),
              KEY idx_admin_risk_withdraw_hit_rule (rule_id,is_deleted),
              UNIQUE KEY uk_admin_risk_withdraw_hit_once (withdrawal_no,rule_id,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createWithdrawHitTable();

    @Update("""
            DELETE h1 FROM nx_admin_risk_withdraw_hit h1
            JOIN nx_admin_risk_withdraw_hit h2
              ON h1.withdrawal_no = h2.withdrawal_no
             AND h1.rule_id = h2.rule_id
             AND h1.is_deleted = h2.is_deleted
             AND h1.id < h2.id
            """)
    void deleteDuplicateWithdrawHits();

    @Update("""
            ALTER TABLE nx_admin_risk_withdraw_hit
            ADD UNIQUE KEY uk_admin_risk_withdraw_hit_once (withdrawal_no,rule_id,is_deleted)
            """)
    void addWithdrawHitUniqueKey();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_arbitrage_stat (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              stat_key VARCHAR(64) NOT NULL,
              label VARCHAR(64) NOT NULL,
              value_text VARCHAR(64) NOT NULL,
              sub_text VARCHAR(255) NOT NULL,
              tone VARCHAR(32) NOT NULL DEFAULT '',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_arbitrage_stat (stat_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createArbitrageStatTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_arbitrage_param (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              param_key VARCHAR(64) NOT NULL,
              name VARCHAR(128) NOT NULL,
              value_text VARCHAR(128) NOT NULL,
              sub_text VARCHAR(255) NOT NULL,
              note_text VARCHAR(1000) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_arbitrage_param (param_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createArbitrageParamTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_arbitrage_row (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              row_id VARCHAR(64) NOT NULL,
              view_key VARCHAR(32) NOT NULL,
              cluster_id VARCHAR(64) DEFAULT NULL,
              cell1 VARCHAR(255) NOT NULL,
              cell2 VARCHAR(255) DEFAULT NULL,
              cell3 VARCHAR(255) DEFAULT NULL,
              cell4 VARCHAR(255) DEFAULT NULL,
              cell5 VARCHAR(255) DEFAULT NULL,
              cell6 VARCHAR(255) DEFAULT NULL,
              level_value INT NOT NULL DEFAULT 1,
              actions_csv VARCHAR(128) NOT NULL,
              disposition VARCHAR(64) DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_arbitrage_row (row_id),
              KEY idx_admin_risk_arbitrage_view (view_key,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createArbitrageRowTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_dimension (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              dim_key VARCHAR(64) NOT NULL,
              name VARCHAR(64) NOT NULL,
              source_label VARCHAR(64) NOT NULL,
              weight_pct INT NOT NULL,
              sort_order INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_score_dimension (dim_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreDimensionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_config (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              config_key VARCHAR(64) NOT NULL,
              value_text VARCHAR(128) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_score_config (config_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreConfigTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_distribution (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              band_key VARCHAR(32) NOT NULL,
              band_label VARCHAR(32) NOT NULL,
              range_text VARCHAR(32) NOT NULL,
              count_value BIGINT NOT NULL DEFAULT 0,
              color VARCHAR(64) NOT NULL,
              tone VARCHAR(32) NOT NULL,
              sort_order INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_score_distribution (band_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreDistributionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_user (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_no VARCHAR(64) NOT NULL,
              model_score INT NOT NULL,
              model_version VARCHAR(32) NOT NULL,
              updated_text VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_score_user (user_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreUserTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_contribution (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_no VARCHAR(64) NOT NULL,
              name VARCHAR(64) NOT NULL,
              evidence VARCHAR(255) NOT NULL,
              points INT NOT NULL DEFAULT 0,
              sort_order INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_admin_risk_score_contribution_user (user_no,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreContributionTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_override (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_no VARCHAR(64) NOT NULL,
              model_score INT NOT NULL,
              override_score INT NOT NULL,
              reason VARCHAR(500) NOT NULL,
              operator VARCHAR(64) NOT NULL,
              time_text VARCHAR(64) NOT NULL,
              active TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_admin_risk_score_override_user (user_no,active,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreOverrideTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_param (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              section_key VARCHAR(32) NOT NULL,
              param_key VARCHAR(64) NOT NULL,
              name VARCHAR(128) NOT NULL,
              value_text VARCHAR(128) NOT NULL,
              unit_text VARCHAR(64) DEFAULT NULL,
              sub_text VARCHAR(255) NOT NULL,
              note_text VARCHAR(1000) NOT NULL,
              sort_order INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_param (section_key,param_key),
              KEY idx_admin_risk_param_section (section_key,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRiskParamTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_multi_account_cluster (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              cluster_id VARCHAR(64) NOT NULL,
              dedupe_key VARCHAR(128) NOT NULL,
              layer_key VARCHAR(32) NOT NULL,
              layer_label VARCHAR(64) NOT NULL,
              account_count INT NOT NULL,
              strength DECIMAL(6,4) NOT NULL,
              span_text VARCHAR(128) NOT NULL,
              status VARCHAR(32) NOT NULL,
              note_text VARCHAR(1000) NOT NULL,
              gifts_json TEXT DEFAULT NULL,
              nodes_json TEXT DEFAULT NULL,
              review_note VARCHAR(1000) DEFAULT NULL,
              updated_by VARCHAR(64) DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_cluster (cluster_id),
              KEY idx_admin_risk_cluster_status (status,is_deleted),
              KEY idx_admin_risk_cluster_layer (layer_key,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createMultiAccountClusterTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_ip_whitelist (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              cidr VARCHAR(64) NOT NULL,
              note_text VARCHAR(255) NOT NULL,
              operator VARCHAR(64) NOT NULL,
              expire_text VARCHAR(64) NOT NULL,
              active TINYINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_ip_whitelist (cidr),
              KEY idx_admin_risk_ip_whitelist_active (active,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createIpWhitelistTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_review_ticket (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              ticket_id VARCHAR(64) NOT NULL,
              ticket_type VARCHAR(64) NOT NULL,
              user_no VARCHAR(64) NOT NULL,
              amount_text VARCHAR(64) NOT NULL,
              cumulative_text VARCHAR(64) NOT NULL,
              kyc_text VARCHAR(128) NOT NULL,
              status VARCHAR(32) NOT NULL,
              sla_pct DECIMAL(6,4) NOT NULL DEFAULT 0,
              sla_text VARCHAR(64) NOT NULL,
              info_json TEXT DEFAULT NULL,
              history_json TEXT DEFAULT NULL,
              decision_reason VARCHAR(1000) DEFAULT NULL,
              reviewed_by VARCHAR(64) DEFAULT NULL,
              reviewed_at DATETIME DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_kyc_ticket (ticket_id),
              KEY idx_admin_risk_kyc_status (status,is_deleted),
              KEY idx_admin_risk_kyc_user (user_no,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createKycReviewTicketTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_alert (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              tone VARCHAR(32) NOT NULL,
              title VARCHAR(128) NOT NULL,
              body VARCHAR(1000) NOT NULL,
              time_text VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_admin_risk_kyc_alert_tone (tone,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createKycAlertTable();

    @Select("SELECT COUNT(*) FROM nx_risk_decision WHERE is_deleted = 0")
    long countRiskCases();

    @Select("""
            SELECT COUNT(*) FROM nx_risk_decision
             WHERE is_deleted = 0 AND UPPER(COALESCE(decision, 'REVIEW')) IN ('REVIEW', 'MANUAL_REVIEW', 'PENDING_REVIEW')
            """)
    long countManualReview();

    @Select("""
            SELECT COUNT(*) FROM nx_risk_decision
             WHERE is_deleted = 0 AND UPPER(COALESCE(decision, '')) IN ('BLOCK', 'REJECT', 'DENY')
            """)
    long countBlocked();

    @Select("SELECT COUNT(*) FROM nx_risk_decision WHERE is_deleted = 0 AND COALESCE(risk_score, 0) >= 80")
    long countHighRisk();

    @Select("""
            <script>
            SELECT decision_no AS caseNo,
                   user_id AS userId,
                   biz_type AS bizType,
                   biz_no AS bizNo,
                   region,
                   user_level AS userLevel,
                   COALESCE(decision, 'REVIEW') AS decision,
                   reason,
                   risk_score AS riskScore,
                   rule_codes AS ruleCodes,
                   CASE WHEN reviewed_at IS NULL THEN 'REVIEWING' ELSE 'FINALIZED' END AS status,
                   reviewed_by AS reviewedBy,
                   reviewed_at AS reviewedAt,
                   created_at AS createdAt
              FROM nx_risk_decision
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='decision != null and decision != ""'>AND UPPER(COALESCE(decision, 'REVIEW')) = UPPER(#{decision})</if>
             <if test='openOnly'>AND reviewed_at IS NULL</if>
             <if test='finalizedOnly'>AND reviewed_at IS NOT NULL</if>
             ORDER BY created_at DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<RiskCaseView> searchCases(
            @Param("userId") Long userId,
            @Param("decision") String decision,
            @Param("openOnly") boolean openOnly,
            @Param("finalizedOnly") boolean finalizedOnly,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_risk_decision
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='decision != null and decision != ""'>AND UPPER(COALESCE(decision, 'REVIEW')) = #{decision}</if>
             <if test='openOnly'>AND reviewed_at IS NULL</if>
             <if test='finalizedOnly'>AND reviewed_at IS NOT NULL</if>
            </script>
            """)
    long countCasesByQuery(
            @Param("userId") Long userId,
            @Param("decision") String decision,
            @Param("openOnly") boolean openOnly,
            @Param("finalizedOnly") boolean finalizedOnly);

    @Select("""
            <script>
            SELECT decision_no AS caseNo,
                   user_id AS userId,
                   biz_type AS bizType,
                   biz_no AS bizNo,
                   region,
                   user_level AS userLevel,
                   COALESCE(decision, 'REVIEW') AS decision,
                   reason,
                   risk_score AS riskScore,
                   rule_codes AS ruleCodes,
                   CASE WHEN reviewed_at IS NULL THEN 'REVIEWING' ELSE 'FINALIZED' END AS status,
                   reviewed_by AS reviewedBy,
                   reviewed_at AS reviewedAt,
                   created_at AS createdAt
              FROM nx_risk_decision
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
             <if test='decision != null and decision != ""'>AND UPPER(COALESCE(decision, 'REVIEW')) = #{decision}</if>
             <if test='openOnly'>AND reviewed_at IS NULL</if>
             <if test='finalizedOnly'>AND reviewed_at IS NOT NULL</if>
             ORDER BY created_at DESC, id DESC LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<RiskCaseView> pageCasesByQuery(
            @Param("userId") Long userId,
            @Param("decision") String decision,
            @Param("openOnly") boolean openOnly,
            @Param("finalizedOnly") boolean finalizedOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT decision_no AS caseNo,
                   user_id AS userId,
                   biz_type AS bizType,
                   biz_no AS bizNo,
                   region,
                   user_level AS userLevel,
                   COALESCE(decision, 'REVIEW') AS decision,
                   reason,
                   risk_score AS riskScore,
                   rule_codes AS ruleCodes,
                   CASE WHEN reviewed_at IS NULL THEN 'REVIEWING' ELSE 'FINALIZED' END AS status,
                   reviewed_by AS reviewedBy,
                   reviewed_at AS reviewedAt,
                   created_at AS createdAt
              FROM nx_risk_decision
             WHERE decision_no = #{caseNo} AND is_deleted = 0
             LIMIT 1
            """)
    RiskCaseView findCase(@Param("caseNo") String caseNo);

    @Update("""
            UPDATE nx_risk_decision
               SET decision = #{decision},
                   reason = #{reason},
                   reviewed_by = #{operator},
                   reviewed_at = NOW(),
                   updated_at = NOW()
             WHERE decision_no = #{caseNo} AND is_deleted = 0
            """)
    int updateDecision(@Param("caseNo") String caseNo, @Param("decision") String decision, @Param("reason") String reason, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_risk_signal (
                signal_no, user_id, signal_type, severity, evidence, created_by, created_at, updated_at, is_deleted
            ) VALUES (#{signalNo}, #{userId}, #{signalType}, #{severity}, #{evidence}, #{operator}, NOW(), NOW(), 0)
            """)
    int insertSignal(@Param("signalNo") String signalNo, @Param("userId") Long userId, @Param("signalType") String signalType,
                     @Param("severity") String severity, @Param("evidence") String evidence, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_risk_decision (
                decision_no, user_id, biz_type, biz_no, user_level, decision, reason, risk_score, rule_codes, rule_snapshot,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{caseNo}, #{userId}, #{bizType}, #{bizNo}, 'KYC', 'REVIEW', #{reason}, #{riskScore}, #{ruleCodes}, #{ruleSnapshot},
                NOW(), NOW(), 0
            )
            """)
    int insertManualReviewCase(@Param("caseNo") String caseNo, @Param("userId") Long userId, @Param("bizType") String bizType,
                               @Param("bizNo") String bizNo, @Param("reason") String reason, @Param("riskScore") int riskScore,
                               @Param("ruleCodes") String ruleCodes, @Param("ruleSnapshot") String ruleSnapshot, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_risk_decision (
                decision_no, user_id, biz_type, biz_no, region, user_level, decision, reason, risk_score, rule_codes, rule_snapshot,
                created_at, updated_at, is_deleted
            ) VALUES (
                #{caseNo}, #{userId}, #{bizType}, #{bizNo}, #{region}, #{userLevel}, #{decision}, #{reason}, #{riskScore}, #{ruleCodes}, #{ruleSnapshot},
                NOW(), NOW(), 0
            )
            """)
    int insertSeedRiskDecision(@Param("caseNo") String caseNo, @Param("userId") long userId, @Param("bizType") String bizType,
                               @Param("bizNo") String bizNo, @Param("region") String region, @Param("userLevel") String userLevel,
                               @Param("decision") String decision, @Param("reason") String reason, @Param("riskScore") int riskScore,
                               @Param("ruleCodes") String ruleCodes, @Param("ruleSnapshot") String ruleSnapshot);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_withdraw_rule WHERE is_deleted = 0")
    long countWithdrawRules();

    @Select("""
            SELECT rule_id AS ruleId,dimension,condition_text AS conditionText,action,state,built_in AS builtIn,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_admin_risk_withdraw_rule
             WHERE is_deleted = 0
             ORDER BY FIELD(state,'active','paused','draft','archived'), built_in DESC, id ASC
            """)
    List<RiskRuleView> withdrawRules();

    @Select("""
            SELECT rule_id AS ruleId,dimension,condition_text AS conditionText,action,state,built_in AS builtIn,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_admin_risk_withdraw_rule
             WHERE is_deleted = 0
             ORDER BY FIELD(state,'active','paused','draft','archived'), built_in DESC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<RiskRuleView> withdrawRulesPage(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT rule_id AS ruleId,dimension,condition_text AS conditionText,action,state,built_in AS builtIn,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_admin_risk_withdraw_rule
             WHERE rule_id = #{ruleId} AND is_deleted = 0
             LIMIT 1
            """)
    RiskRuleView findWithdrawRule(@Param("ruleId") String ruleId);

    @Select("""
            SELECT w.withdrawal_no AS withdrawalNo,
                   CONCAT('U', LPAD(w.user_id, 8, '0')) AS userNo,
                   w.amount AS amount,
                   (
                       SELECT COUNT(1)
                         FROM nx_withdrawal_order w2
                        WHERE w2.user_id = w.user_id
                          AND w2.is_deleted = 0
                          AND w2.created_at >= DATE_SUB(w.created_at, INTERVAL 24 HOUR)
                          AND w2.created_at <= w.created_at
                   ) AS withdrawalCount24h,
                   CONCAT_WS(' ',
                       COALESCE(rd.rule_codes, ''),
                       COALESCE(rd.reason, ''),
                       COALESCE(u.status, ''),
                       COALESCE(u.kyc_status, '')
                   ) AS existingSignals
              FROM nx_withdrawal_order w
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
             WHERE w.is_deleted = 0
               AND w.status IN ('PENDING','REVIEWING','FROZEN','REJECTED','PENDING_CHAIN')
             ORDER BY w.id DESC
             LIMIT #{limit}
            """)
    List<RiskWithdrawCandidateView> withdrawRuleCandidates(@Param("limit") int limit);

    @Insert("""
            INSERT INTO nx_admin_risk_withdraw_rule (
                rule_id, dimension, condition_text, action, state, built_in, created_by, created_at, updated_at, is_deleted
            ) VALUES (#{ruleId}, #{dimension}, #{conditionText}, #{action}, #{state}, #{builtIn}, #{operator}, NOW(), NOW(), 0)
            """)
    int insertWithdrawRule(@Param("ruleId") String ruleId, @Param("dimension") String dimension,
                           @Param("conditionText") String conditionText, @Param("action") String action,
                           @Param("state") String state, @Param("builtIn") boolean builtIn, @Param("operator") String operator);

    @Update("""
            UPDATE nx_admin_risk_withdraw_rule
               SET state = #{state}, updated_at = NOW()
             WHERE rule_id = #{ruleId} AND is_deleted = 0
            """)
    int updateWithdrawRuleState(@Param("ruleId") String ruleId, @Param("state") String state);

    @Update("""
            UPDATE nx_admin_risk_withdraw_rule
               SET condition_text = #{conditionText}, updated_at = NOW()
             WHERE rule_id = #{ruleId} AND is_deleted = 0
            """)
    int updateWithdrawRuleCondition(@Param("ruleId") String ruleId, @Param("conditionText") String conditionText);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_route_count WHERE is_deleted = 0")
    long countRouteCounts();

    @Select("""
            SELECT route_key AS routeKey,label,count_value AS count,color
              FROM nx_admin_risk_route_count
             WHERE is_deleted = 0
             ORDER BY FIELD(route_key,'pass','delay','manual','freeze'), id ASC
            """)
    List<RiskRouteCountView> routeCounts();

    @Insert("INSERT INTO nx_admin_risk_route_count (route_key,label,count_value,color,is_deleted) VALUES (#{routeKey},#{label},#{countValue},#{color},0)")
    int insertRouteCount(@Param("routeKey") String routeKey, @Param("label") String label, @Param("countValue") long countValue, @Param("color") String color);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_withdraw_hit WHERE is_deleted = 0")
    long countWithdrawHits();

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_risk_withdraw_hit
             WHERE is_deleted = 0
             <if test='action != null and action != "" and action != "all"'>AND action = #{action}</if>
            </script>
            """)
    long countWithdrawHitsByAction(@Param("action") String action);

    @Select("""
            <script>
            SELECT h.withdrawal_no AS withdrawalNo,
                   h.user_no AS userNo,
                   h.amount_text AS amountText,
                   h.rule_id AS ruleId,
                   h.dimension,
                   h.action,
                   COALESCE(
                       rd.reason,
                       CONCAT(h.dimension, '规则命中:', COALESCE(r.condition_text, h.rule_id), ' -> ', h.action)
                   ) AS reason,
                   h.time_text AS timeText
              FROM nx_admin_risk_withdraw_hit h
              LEFT JOIN nx_admin_risk_withdraw_rule r
                ON r.rule_id = h.rule_id AND r.is_deleted = 0
              LEFT JOIN nx_risk_decision rd
                ON rd.id = (
                    SELECT MAX(rd2.id)
                      FROM nx_risk_decision rd2
                     WHERE rd2.is_deleted = 0 AND rd2.biz_type = 'WITHDRAW_RULE' AND rd2.biz_no = h.withdrawal_no
                )
             WHERE h.is_deleted = 0
             <if test='action != null and action != "" and action != "all"'>AND h.action = #{action}</if>
             ORDER BY h.id DESC LIMIT #{limit}
            </script>
            """)
    List<RiskRuleHitView> withdrawHits(@Param("action") String action, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT h.withdrawal_no AS withdrawalNo,
                   h.user_no AS userNo,
                   h.amount_text AS amountText,
                   h.rule_id AS ruleId,
                   h.dimension,
                   h.action,
                   COALESCE(
                       rd.reason,
                       CONCAT(h.dimension, '规则命中:', COALESCE(r.condition_text, h.rule_id), ' -> ', h.action)
                   ) AS reason,
                   h.time_text AS timeText
              FROM nx_admin_risk_withdraw_hit h
              LEFT JOIN nx_admin_risk_withdraw_rule r
                ON r.rule_id = h.rule_id AND r.is_deleted = 0
              LEFT JOIN nx_risk_decision rd
                ON rd.id = (
                    SELECT MAX(rd2.id)
                      FROM nx_risk_decision rd2
                     WHERE rd2.is_deleted = 0 AND rd2.biz_type = 'WITHDRAW_RULE' AND rd2.biz_no = h.withdrawal_no
                )
             WHERE h.is_deleted = 0
             <if test='action != null and action != "" and action != "all"'>AND h.action = #{action}</if>
             ORDER BY h.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RiskRuleHitView> withdrawHitsPage(@Param("action") String action, @Param("offset") int offset, @Param("limit") int limit);

    @Insert("INSERT INTO nx_admin_risk_withdraw_hit (withdrawal_no,user_no,amount_text,rule_id,dimension,action,time_text,is_deleted) VALUES (#{withdrawalNo},#{userNo},#{amountText},#{ruleId},#{dimension},#{action},#{timeText},0) ON DUPLICATE KEY UPDATE user_no=VALUES(user_no),amount_text=VALUES(amount_text),dimension=VALUES(dimension),action=VALUES(action),time_text=VALUES(time_text),created_at=CURRENT_TIMESTAMP")
    int insertWithdrawHit(@Param("withdrawalNo") String withdrawalNo, @Param("userNo") String userNo,
                          @Param("amountText") String amountText, @Param("ruleId") String ruleId,
                          @Param("dimension") String dimension, @Param("action") String action,
                          @Param("timeText") String timeText);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_arbitrage_stat WHERE is_deleted = 0")
    long countArbitrageStats();

    @Select("""
            SELECT stat_key AS `key`,label,value_text AS value,sub_text AS sub,tone
              FROM nx_admin_risk_arbitrage_stat
             WHERE is_deleted = 0
             ORDER BY id ASC
            """)
    List<RiskArbitrageStatView> arbitrageStats();

    @Insert("INSERT INTO nx_admin_risk_arbitrage_stat (stat_key,label,value_text,sub_text,tone,is_deleted) VALUES (#{key},#{label},#{value},#{sub},#{tone},0)")
    int insertArbitrageStat(@Param("key") String key, @Param("label") String label, @Param("value") String value, @Param("sub") String sub, @Param("tone") String tone);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_arbitrage_param WHERE is_deleted = 0")
    long countArbitrageParams();

    @Select("""
            SELECT param_key AS `key`,name,value_text AS value,sub_text AS sub,note_text AS note
              FROM nx_admin_risk_arbitrage_param
             WHERE is_deleted = 0
             ORDER BY id ASC
            """)
    List<RiskArbitrageParamView> arbitrageParams();

    @Select("""
            SELECT param_key AS `key`,name,value_text AS value,sub_text AS sub,note_text AS note
              FROM nx_admin_risk_arbitrage_param
             WHERE param_key = #{key} AND is_deleted = 0
             LIMIT 1
            """)
    RiskArbitrageParamView findArbitrageParam(@Param("key") String key);

    @Insert("INSERT INTO nx_admin_risk_arbitrage_param (param_key,name,value_text,sub_text,note_text,is_deleted) VALUES (#{key},#{name},#{value},#{sub},#{note},0)")
    int insertArbitrageParam(@Param("key") String key, @Param("name") String name, @Param("value") String value,
                             @Param("sub") String sub, @Param("note") String note);

    @Update("""
            UPDATE nx_admin_risk_arbitrage_param
               SET value_text = #{value}, updated_at = NOW()
             WHERE param_key = #{key} AND is_deleted = 0
            """)
    int updateArbitrageParam(@Param("key") String key, @Param("value") String value);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_arbitrage_row WHERE is_deleted = 0")
    long countArbitrageRows();

    @Select("""
            SELECT row_id AS rowId,view_key AS viewKey,cluster_id AS clusterId,cell1,cell2,cell3,cell4,cell5,cell6,level_value AS level,actions_csv AS actionsCsv,disposition
              FROM nx_admin_risk_arbitrage_row
             WHERE is_deleted = 0
             ORDER BY FIELD(view_key,'trial','tradein','gift','board'), id ASC
            """)
    List<RiskArbitrageRowRecord> arbitrageRows();

    @Select("""
            SELECT row_id AS rowId,view_key AS viewKey,cluster_id AS clusterId,cell1,cell2,cell3,cell4,cell5,cell6,level_value AS level,actions_csv AS actionsCsv,disposition
              FROM nx_admin_risk_arbitrage_row
             WHERE row_id = #{rowId} AND is_deleted = 0
             LIMIT 1
            """)
    RiskArbitrageRowRecord findArbitrageRow(@Param("rowId") String rowId);

    @Insert("""
            INSERT INTO nx_admin_risk_arbitrage_row (
                row_id,view_key,cluster_id,cell1,cell2,cell3,cell4,cell5,cell6,level_value,actions_csv,is_deleted
            ) VALUES (#{rowId},#{viewKey},#{clusterId},#{cell1},#{cell2},#{cell3},#{cell4},#{cell5},#{cell6},#{level},#{actions},0)
            """)
    int insertArbitrageRow(@Param("rowId") String rowId, @Param("viewKey") String viewKey, @Param("clusterId") String clusterId,
                           @Param("level") int level, @Param("actions") String actions, @Param("cell1") String cell1,
                           @Param("cell2") String cell2, @Param("cell3") String cell3, @Param("cell4") String cell4,
                           @Param("cell5") String cell5, @Param("cell6") String cell6);

    @Update("""
            UPDATE nx_admin_risk_arbitrage_row
               SET disposition = #{disposition}, updated_at = NOW()
             WHERE row_id = #{rowId} AND is_deleted = 0
            """)
    int updateArbitrageDisposition(@Param("rowId") String rowId, @Param("disposition") String disposition);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_dimension WHERE is_deleted = 0")
    long countScoreDimensions();

    @Select("""
            SELECT dim_key AS dimKey,name,source_label AS source,weight_pct AS weightPct
              FROM nx_admin_risk_score_dimension
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<RiskScoreDimensionView> scoreDimensions();

    @Insert("INSERT INTO nx_admin_risk_score_dimension (dim_key,name,source_label,weight_pct,sort_order,is_deleted) VALUES (#{dimKey},#{name},#{source},#{weightPct},#{sortOrder},0)")
    int insertScoreDimension(@Param("dimKey") String dimKey, @Param("name") String name, @Param("source") String source,
                             @Param("weightPct") int weightPct, @Param("sortOrder") int sortOrder);

    @Update("""
            UPDATE nx_admin_risk_score_dimension
               SET weight_pct = #{weightPct}, updated_at = NOW()
             WHERE dim_key = #{dimKey} AND is_deleted = 0
            """)
    int updateScoreDimensionWeight(@Param("dimKey") String dimKey, @Param("weightPct") int weightPct);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_config WHERE is_deleted = 0")
    long countScoreConfig();

    @Select("SELECT config_key AS configKey,value_text AS valueText FROM nx_admin_risk_score_config WHERE is_deleted = 0")
    List<ScoreConfigRecord> scoreConfigRows();

    @Insert("INSERT INTO nx_admin_risk_score_config (config_key,value_text,is_deleted) VALUES (#{key},#{value},0)")
    int insertScoreConfig(@Param("key") String key, @Param("value") String value);

    @Update("""
            UPDATE nx_admin_risk_score_config
               SET value_text = #{value}, updated_at = NOW()
             WHERE config_key = #{key} AND is_deleted = 0
            """)
    int updateScoreConfig(@Param("key") String key, @Param("value") String value);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_distribution WHERE is_deleted = 0")
    long countScoreDistribution();

    @Select("SELECT COALESCE(SUM(count_value),0) FROM nx_admin_risk_score_distribution WHERE is_deleted = 0")
    long sumScoreDistribution();

    @Select("""
            SELECT band_label AS band,range_text AS rangeText,count_value AS count,color,tone
              FROM nx_admin_risk_score_distribution
             WHERE is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<ScoreDistributionRecord> scoreDistributionRows();

    @Insert("INSERT INTO nx_admin_risk_score_distribution (band_key,band_label,range_text,count_value,color,tone,sort_order,is_deleted) VALUES (#{bandKey},#{band},#{rangeText},#{count},#{color},#{tone},#{sortOrder},0)")
    int insertScoreDistribution(@Param("bandKey") String bandKey, @Param("band") String band, @Param("rangeText") String rangeText,
                                @Param("count") long count, @Param("color") String color, @Param("tone") String tone,
                                @Param("sortOrder") int sortOrder);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_user WHERE is_deleted = 0")
    long countScoreUsers();

    @Select("""
            SELECT user_no AS userNo,model_score AS modelScore,model_version AS modelVersion,updated_text AS updatedText
              FROM nx_admin_risk_score_user
             WHERE user_no = #{userNo} AND is_deleted = 0
             LIMIT 1
            """)
    ScoreUserRecord findScoreUser(@Param("userNo") String userNo);

    @Select("""
            <script>
            SELECT s.user_no AS userNo,
                   s.model_score AS modelScore,
                   s.model_version AS modelVersion,
                   s.updated_text AS updatedText,
                   u.nickname AS nickname,
                   CASE
                       WHEN u.phone IS NULL OR LENGTH(u.phone) &lt; 7 THEN u.phone
                       ELSE CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3))
                   END AS phoneMasked,
                   u.referral_code AS referralCode
              FROM nx_admin_risk_score_user s
              LEFT JOIN nx_user u
                ON CONCAT('U', LPAD(u.id, 8, '0')) = s.user_no
               AND u.is_deleted = 0
             WHERE s.is_deleted = 0
               AND (
                    #{keyword} IS NULL
                    OR #{keyword} = ''
                    OR s.user_no LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR u.phone LIKE CONCAT('%', #{keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{keyword}, '%')
               )
             ORDER BY
                   CASE
                       WHEN s.user_no = #{keyword} THEN 0
                       WHEN u.referral_code = #{keyword} THEN 1
                       WHEN s.user_no LIKE CONCAT(#{keyword}, '%') THEN 2
                       WHEN u.referral_code LIKE CONCAT(#{keyword}, '%') THEN 3
                       WHEN u.nickname LIKE CONCAT(#{keyword}, '%') THEN 4
                       ELSE 5
                   END,
                   s.updated_at DESC,
                   s.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<ScoreUserSearchRecord> searchScoreUsers(@Param("keyword") String keyword, @Param("limit") int limit);

    @Insert("INSERT INTO nx_admin_risk_score_user (user_no,model_score,model_version,updated_text,is_deleted) VALUES (#{userNo},#{modelScore},#{modelVersion},#{updatedText},0)")
    int insertScoreUser(@Param("userNo") String userNo, @Param("modelScore") int modelScore,
                        @Param("modelVersion") String modelVersion, @Param("updatedText") String updatedText);

    @Select("""
            SELECT name,evidence,points
              FROM nx_admin_risk_score_contribution
             WHERE user_no = #{userNo} AND is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<ScoreContributionRecord> scoreContributions(@Param("userNo") String userNo);

    @Insert("INSERT INTO nx_admin_risk_score_contribution (user_no,name,evidence,points,sort_order,is_deleted) VALUES (#{userNo},#{name},#{evidence},#{points},#{sortOrder},0)")
    int insertScoreContribution(@Param("userNo") String userNo, @Param("name") String name, @Param("evidence") String evidence,
                                @Param("points") int points, @Param("sortOrder") int sortOrder);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_override WHERE is_deleted = 0")
    long countScoreOverrides();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_override WHERE is_deleted = 0 AND active = 1")
    long countActiveScoreOverrides();

    @Select("""
            SELECT user_no AS userNo,model_score AS modelScore,override_score AS overrideScore,reason,operator,time_text AS timeText,active
              FROM nx_admin_risk_score_override
             WHERE is_deleted = 0
             ORDER BY active DESC, created_at DESC, id DESC
             LIMIT 20
            """)
    List<RiskScoreOverrideView> scoreOverrides();

    @Select("""
            SELECT user_no AS userNo,model_score AS modelScore,override_score AS overrideScore,reason,operator,time_text AS timeText,active
              FROM nx_admin_risk_score_override
             WHERE is_deleted = 0
             ORDER BY active DESC, created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<RiskScoreOverrideView> scoreOverridesPage(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT user_no AS userNo,model_score AS modelScore,override_score AS overrideScore,reason,operator,time_text AS timeText,active
              FROM nx_admin_risk_score_override
             WHERE user_no = #{userNo} AND active = 1 AND is_deleted = 0
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    RiskScoreOverrideView activeScoreOverride(@Param("userNo") String userNo);

    @Update("""
            UPDATE nx_admin_risk_score_override
               SET active = 0, updated_at = NOW()
             WHERE user_no = #{userNo} AND active = 1 AND is_deleted = 0
            """)
    int deactivateScoreOverrides(@Param("userNo") String userNo);

    @Insert("""
            INSERT INTO nx_admin_risk_score_override (user_no,model_score,override_score,reason,operator,time_text,active,is_deleted)
            VALUES (#{userNo},#{modelScore},#{overrideScore},#{reason},#{operator},#{timeText},#{active},0)
            """)
    int insertScoreOverride(@Param("userNo") String userNo, @Param("modelScore") int modelScore,
                            @Param("overrideScore") int overrideScore, @Param("reason") String reason,
                            @Param("operator") String operator, @Param("timeText") String timeText,
                            @Param("active") boolean active);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_param WHERE is_deleted = 0 AND section_key = #{section}")
    long countRiskParams(@Param("section") String section);

    @Select("""
            SELECT param_key AS `key`,name,value_text AS value,unit_text AS unit,sub_text AS sub,note_text AS note
              FROM nx_admin_risk_param
             WHERE section_key = #{section} AND is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<RiskParamRecord> riskParams(@Param("section") String section);

    @Insert("""
            INSERT INTO nx_admin_risk_param (section_key,param_key,name,value_text,unit_text,sub_text,note_text,sort_order,is_deleted)
            VALUES (#{section},#{key},#{name},#{value},#{unit},#{sub},#{note},#{sortOrder},0)
            """)
    int insertRiskParam(@Param("section") String section, @Param("key") String key, @Param("name") String name,
                        @Param("value") String value, @Param("unit") String unit, @Param("sub") String sub,
                        @Param("note") String note, @Param("sortOrder") int sortOrder);

    @Update("""
            UPDATE nx_admin_risk_param
               SET value_text = #{value}, updated_at = NOW()
             WHERE section_key = #{section} AND param_key = #{key} AND is_deleted = 0
            """)
    int updateRiskParam(@Param("section") String section, @Param("key") String key, @Param("value") String value);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_multi_account_cluster WHERE is_deleted = 0")
    long countMultiAccountClusters();

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted = 0
             <if test="layer != null and layer != ''">
               AND layer_key = #{layer}
             </if>
            </script>
            """)
    long countMultiAccountClustersByLayer(@Param("layer") String layer);

    @Select("""
            SELECT cluster_id AS id,dedupe_key AS `key`,layer_key AS layer,layer_label AS layerLabel,
                   account_count AS n,strength,span_text AS span,status,note_text AS note,
                   gifts_json AS giftsJson,nodes_json AS nodesJson,review_note AS reviewNote
              FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted = 0
             ORDER BY strength DESC, id ASC
            """)
    List<MultiAccountClusterRecord> multiAccountClusters();

    @Select("""
            <script>
            SELECT cluster_id AS id,dedupe_key AS `key`,layer_key AS layer,layer_label AS layerLabel,
                   account_count AS n,strength,span_text AS span,status,note_text AS note,
                   gifts_json AS giftsJson,nodes_json AS nodesJson,review_note AS reviewNote
              FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted = 0
             <if test="layer != null and layer != ''">
               AND layer_key = #{layer}
             </if>
             ORDER BY strength DESC, id ASC
             LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<MultiAccountClusterRecord> pageMultiAccountClusters(@Param("layer") String layer,
                                                             @Param("offset") int offset,
                                                             @Param("pageSize") int pageSize);

    @Insert("""
            INSERT INTO nx_admin_risk_multi_account_cluster (
                cluster_id,dedupe_key,layer_key,layer_label,account_count,strength,span_text,status,note_text,gifts_json,nodes_json,is_deleted
            ) VALUES (
                #{id},#{key},#{layer},#{layerLabel},#{n},#{strength},#{span},#{status},#{note},#{giftsJson},#{nodesJson},0
            )
            """)
    int insertMultiAccountCluster(@Param("id") String id, @Param("key") String key, @Param("layer") String layer,
                                  @Param("layerLabel") String layerLabel, @Param("n") int n, @Param("strength") double strength,
                                  @Param("span") String span, @Param("status") String status, @Param("note") String note,
                                  @Param("giftsJson") String giftsJson, @Param("nodesJson") String nodesJson);

    @Update("""
            UPDATE nx_admin_risk_multi_account_cluster
               SET status = #{status}, review_note = #{reason}, updated_by = #{operator}, updated_at = NOW()
             WHERE cluster_id = #{clusterId} AND is_deleted = 0
            """)
    int updateMultiAccountClusterStatus(@Param("clusterId") String clusterId, @Param("status") String status,
                                        @Param("reason") String reason, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_ip_whitelist WHERE is_deleted = 0")
    long countIpWhitelist();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_ip_whitelist WHERE is_deleted = 0 AND active = 1")
    long countActiveIpWhitelist();

    @Select("""
            SELECT cidr,note_text AS note,operator,expire_text AS expireText,active
              FROM nx_admin_risk_ip_whitelist
             WHERE is_deleted = 0 AND active = 1
             ORDER BY id ASC
            """)
    List<IpWhitelistRecord> ipWhitelist();

    @Select("""
            SELECT cidr,note_text AS note,operator,expire_text AS expireText,active
              FROM nx_admin_risk_ip_whitelist
             WHERE is_deleted = 0 AND active = 1
             ORDER BY id ASC
             LIMIT #{offset}, #{pageSize}
            """)
    List<IpWhitelistRecord> pageIpWhitelist(@Param("offset") int offset, @Param("pageSize") int pageSize);

    @Insert("""
            INSERT INTO nx_admin_risk_ip_whitelist (cidr,note_text,operator,expire_text,active,is_deleted)
            VALUES (#{cidr},#{note},#{operator},#{expireText},1,0)
            ON DUPLICATE KEY UPDATE note_text = VALUES(note_text), operator = VALUES(operator), expire_text = VALUES(expire_text), active = 1, updated_at = NOW(), is_deleted = 0
            """)
    int upsertIpWhitelist(@Param("cidr") String cidr, @Param("note") String note, @Param("operator") String operator,
                          @Param("expireText") String expireText);

    @Update("""
            UPDATE nx_admin_risk_ip_whitelist
               SET active = 0, operator = #{operator}, updated_at = NOW()
             WHERE cidr = #{cidr} AND is_deleted = 0
            """)
    int disableIpWhitelist(@Param("cidr") String cidr, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_kyc_review_ticket WHERE is_deleted = 0")
    long countKycTickets();

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_kyc_review_ticket
             WHERE is_deleted = 0
               AND status NOT IN ('passed','rejected')
            """)
    long countKycOpenTickets();

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_kyc_review_ticket
             WHERE user_no = #{userNo}
               AND is_deleted = 0
               AND status NOT IN ('passed','rejected')
            """)
    long countOpenKycTicketsByUser(@Param("userNo") String userNo);

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_kyc_review_ticket
             WHERE is_deleted = 0
               AND status = #{status}
            """)
    long countKycTicketsByStatus(@Param("status") String status);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_risk_kyc_review_ticket
             WHERE is_deleted = 0
             <if test='filter != null and filter != ""'>
               <choose>
                 <when test='filter == "overdue"'>AND status = 'overdue'</when>
                 <otherwise>AND ticket_type = #{filter}</otherwise>
               </choose>
             </if>
            </script>
            """)
    long countKycTicketsByFilter(@Param("filter") String filter);

    @Select("""
            <script>
            SELECT ticket_id AS id,ticket_type AS type,user_no AS user,amount_text AS amt,cumulative_text AS cum,
                   kyc_text AS kyc,status AS st,sla_pct AS slaPct,sla_text AS slaTxt,info_json AS infoJson,history_json AS histJson
              FROM nx_admin_risk_kyc_review_ticket
             WHERE is_deleted = 0
             <if test='filter != null and filter != ""'>
               <choose>
                 <when test='filter == "overdue"'>AND status = 'overdue'</when>
                 <otherwise>AND ticket_type = #{filter}</otherwise>
               </choose>
             </if>
             ORDER BY FIELD(status,'overdue','in-review','triggered','passed','rejected'), id ASC
             LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<KycReviewTicketRecord> pageKycReviewTickets(@Param("filter") String filter, @Param("offset") int offset,
                                                     @Param("pageSize") int pageSize);

    @Select("""
            SELECT ticket_id AS id,ticket_type AS type,user_no AS user,amount_text AS amt,cumulative_text AS cum,
                   kyc_text AS kyc,status AS st,sla_pct AS slaPct,sla_text AS slaTxt,info_json AS infoJson,history_json AS histJson
              FROM nx_admin_risk_kyc_review_ticket
             WHERE ticket_id = #{ticketId} AND is_deleted = 0
             LIMIT 1
            """)
    KycReviewTicketRecord findKycReviewTicket(@Param("ticketId") String ticketId);

    @Insert("""
            INSERT INTO nx_admin_risk_kyc_review_ticket (
                ticket_id,ticket_type,user_no,amount_text,cumulative_text,kyc_text,status,sla_pct,sla_text,info_json,history_json,is_deleted
            ) VALUES (
                #{id},#{type},#{user},#{amt},#{cum},#{kyc},#{st},#{slaPct},#{slaTxt},#{infoJson},#{histJson},0
            )
            """)
    int insertKycReviewTicket(@Param("id") String id, @Param("type") String type, @Param("user") String user,
                              @Param("amt") String amt, @Param("cum") String cum, @Param("kyc") String kyc,
                              @Param("st") String st, @Param("slaPct") double slaPct, @Param("slaTxt") String slaTxt,
                              @Param("infoJson") String infoJson, @Param("histJson") String histJson);

    @Update("""
            UPDATE nx_admin_risk_kyc_review_ticket
               SET status = #{status}, decision_reason = #{reason}, reviewed_by = #{operator}, reviewed_at = NOW(), updated_at = NOW()
             WHERE ticket_id = #{ticketId} AND is_deleted = 0
            """)
    int updateKycReviewTicketStatus(@Param("ticketId") String ticketId, @Param("status") String status,
                                    @Param("reason") String reason, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_kyc_alert WHERE is_deleted = 0")
    long countKycAlerts();

    @Select("SELECT tone,title,body,time_text AS timeText FROM nx_admin_risk_kyc_alert WHERE is_deleted = 0 ORDER BY id ASC")
    List<KycAlertRecord> kycAlerts();

    @Insert("INSERT INTO nx_admin_risk_kyc_alert (tone,title,body,time_text,is_deleted) VALUES (#{tone},#{title},#{body},#{timeText},0)")
    int insertKycAlert(@Param("tone") String tone, @Param("title") String title, @Param("body") String body,
                       @Param("timeText") String timeText);

    record RiskArbitrageRowRecord(
            String rowId,
            String viewKey,
            String clusterId,
            String cell1,
            String cell2,
            String cell3,
            String cell4,
            String cell5,
            String cell6,
            Integer level,
            String actionsCsv,
            String disposition
    ) {
    }

    record ScoreConfigRecord(String configKey, String valueText) {
    }

    record ScoreDistributionRecord(String band, String rangeText, Long count, String color, String tone) {
    }

    record ScoreUserRecord(String userNo, Integer modelScore, String modelVersion, String updatedText) {
    }

    record ScoreUserSearchRecord(String userNo, Integer modelScore, String modelVersion, String updatedText,
                                 String nickname, String phoneMasked, String referralCode) {
    }

    record ScoreContributionRecord(String name, String evidence, Integer points) {
    }

    record RiskParamRecord(String key, String name, String value, String unit, String sub, String note) {
    }

    record MultiAccountClusterRecord(
            String id,
            String key,
            String layer,
            String layerLabel,
            Integer n,
            Double strength,
            String span,
            String status,
            String note,
            String giftsJson,
            String nodesJson,
            String reviewNote
    ) {
    }

    record IpWhitelistRecord(String cidr, String note, String operator, String expireText, Boolean active) {
    }

    record KycReviewTicketRecord(
            String id,
            String type,
            String user,
            String amt,
            String cum,
            String kyc,
            String st,
            Double slaPct,
            String slaTxt,
            String infoJson,
            String histJson
    ) {
    }

    record KycAlertRecord(String tone, String title, String body, String timeText) {
    }
}
