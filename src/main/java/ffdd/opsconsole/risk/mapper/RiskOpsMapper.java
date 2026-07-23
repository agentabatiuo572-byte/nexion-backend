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
              priority INT NOT NULL DEFAULT 50,
              version BIGINT NOT NULL DEFAULT 0,
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

    @Update("ALTER TABLE nx_admin_risk_withdraw_rule ADD COLUMN priority INT NOT NULL DEFAULT 50 AFTER built_in")
    void addWithdrawRulePriorityColumn();

    @Update("ALTER TABLE nx_admin_risk_withdraw_rule ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER priority")
    void addWithdrawRuleVersionColumn();

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
              version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_arbitrage_param (param_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createArbitrageParamTable();

    @Update("ALTER TABLE nx_admin_risk_arbitrage_param ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER note_text")
    void addArbitrageParamVersionColumn();

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
              version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_arbitrage_row (row_id),
              KEY idx_admin_risk_arbitrage_view (view_key,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createArbitrageRowTable();

    @Update("ALTER TABLE nx_admin_risk_arbitrage_row ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER disposition")
    void addArbitrageRowVersionColumn();

    @Update("UPDATE nx_admin_risk_arbitrage_param SET is_deleted = 1, updated_at = NOW() WHERE param_key = #{key} AND is_deleted = 0")
    int retireObsoleteArbitrageParam(@Param("key") String key);

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
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_model (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              model_version BIGINT NOT NULL,
              row_version BIGINT NOT NULL DEFAULT 0,
              state VARCHAR(16) NOT NULL,
              weights_json VARCHAR(1000) NOT NULL,
              input_sources_json VARCHAR(1000) NOT NULL,
              score_mapping_json MEDIUMTEXT NOT NULL,
              band_low_max INT NOT NULL,
              band_high_min INT NOT NULL,
              auto_escalate_score INT NOT NULL,
              reason VARCHAR(200) NOT NULL,
              created_by VARCHAR(64) NOT NULL,
              published_by VARCHAR(64),
              published_at DATETIME,
              archived_at DATETIME,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_score_model_version (model_version),
              KEY idx_admin_risk_score_model_state (state,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreModelTable();

    @Update("ALTER TABLE nx_admin_risk_score_model ADD COLUMN score_mapping_json MEDIUMTEXT NULL AFTER input_sources_json")
    void addScoreModelMappingColumn();

    @Update("UPDATE nx_admin_risk_score_model SET score_mapping_json=#{mappingJson} WHERE score_mapping_json IS NULL OR score_mapping_json='' ")
    int backfillScoreModelMappings(@Param("mappingJson") String mappingJson);

    @Update("ALTER TABLE nx_admin_risk_score_user ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 AFTER model_version")
    void addScoreUserRowVersionColumn();

    @Update("ALTER TABLE nx_admin_risk_score_user ADD COLUMN as_of DATETIME NULL AFTER row_version")
    void addScoreUserAsOfColumn();

    @Update("ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN dim_key VARCHAR(64) NULL AFTER user_no")
    void addScoreContributionDimKeyColumn();

    @Update("ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN hit TINYINT NOT NULL DEFAULT 0 AFTER name")
    void addScoreContributionHitColumn();

    @Update("ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN sub_score INT NOT NULL DEFAULT 0 AFTER evidence")
    void addScoreContributionSubScoreColumn();

    @Update("ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN weight_pct INT NOT NULL DEFAULT 0 AFTER sub_score")
    void addScoreContributionWeightColumn();

    @Update("ALTER TABLE nx_admin_risk_score_contribution ADD COLUMN model_version BIGINT NULL AFTER user_no")
    void addScoreContributionModelVersionColumn();

    @Update("UPDATE nx_admin_risk_score_contribution SET model_version=#{modelVersion} WHERE model_version IS NULL")
    int backfillContributionModelVersion(@Param("modelVersion") long modelVersion);

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_history (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_no VARCHAR(64) NOT NULL,
              model_version BIGINT NOT NULL,
              model_score INT NOT NULL,
              effective_score INT NOT NULL,
              score_state VARCHAR(32) NOT NULL,
              contributions_json MEDIUMTEXT NOT NULL,
              reason VARCHAR(200) NOT NULL,
              operator VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_admin_risk_score_history_user (user_no,model_version,created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createScoreHistoryTable();

    @Update("ALTER TABLE nx_admin_risk_score_override ADD COLUMN active_user_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN active = 1 AND is_deleted = 0 THEN user_no ELSE NULL END) STORED")
    void addScoreOverrideActiveKeyColumn();

    @Update("CREATE UNIQUE INDEX uk_admin_risk_score_override_active ON nx_admin_risk_score_override(active_user_key)")
    void addScoreOverrideActiveUniqueKey();

    @Update("""
            UPDATE nx_admin_risk_score_override o
              JOIN (SELECT user_no,MAX(id) keep_id FROM nx_admin_risk_score_override
                     WHERE active=1 AND is_deleted=0 GROUP BY user_no HAVING COUNT(*)>1) d
                ON d.user_no=o.user_no
               SET o.active=0,o.updated_at=NOW()
             WHERE o.active=1 AND o.is_deleted=0 AND o.id<>d.keep_id
            """)
    int deactivateDuplicateActiveScoreOverrides();

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
              row_version BIGINT NOT NULL DEFAULT 0,
              as_of DATETIME,
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
              model_version BIGINT,
              dim_key VARCHAR(64),
              name VARCHAR(64) NOT NULL,
              hit TINYINT NOT NULL DEFAULT 0,
              evidence VARCHAR(255) NOT NULL,
              sub_score INT NOT NULL DEFAULT 0,
              weight_pct INT NOT NULL DEFAULT 0,
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
              active_user_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN active = 1 AND is_deleted = 0 THEN user_no ELSE NULL END) STORED,
              UNIQUE KEY uk_admin_risk_score_override_active (active_user_key),
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
              version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_param (section_key,param_key),
              KEY idx_admin_risk_param_section (section_key,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRiskParamTable();

    @Update("ALTER TABLE nx_admin_risk_param ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER sort_order")
    void addRiskParamVersionColumn();

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
              gifts_json MEDIUMTEXT DEFAULT NULL,
              nodes_json MEDIUMTEXT DEFAULT NULL,
              edges_json MEDIUMTEXT DEFAULT NULL,
              projection_fingerprint VARCHAR(64) DEFAULT NULL,
              threshold_hit TINYINT NOT NULL DEFAULT 0,
              review_note VARCHAR(1000) DEFAULT NULL,
              version BIGINT NOT NULL DEFAULT 0,
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

    @Update("ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN edges_json MEDIUMTEXT DEFAULT NULL AFTER nodes_json")
    void addMultiAccountClusterEdgesColumn();

    @Update("ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER review_note")
    void addMultiAccountClusterVersionColumn();

    @Update("ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN projection_fingerprint VARCHAR(64) DEFAULT NULL AFTER edges_json")
    void addMultiAccountClusterFingerprintColumn();

    @Update("ALTER TABLE nx_admin_risk_multi_account_cluster ADD COLUMN threshold_hit TINYINT NOT NULL DEFAULT 0 AFTER projection_fingerprint")
    void addMultiAccountClusterThresholdHitColumn();

    @Update("ALTER TABLE nx_admin_risk_multi_account_cluster MODIFY COLUMN gifts_json MEDIUMTEXT NULL, MODIFY COLUMN nodes_json MEDIUMTEXT NULL, MODIFY COLUMN edges_json MEDIUMTEXT NULL")
    void widenMultiAccountEvidenceColumns();

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
              amount_usdt DECIMAL(20,8) DEFAULT NULL,
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
              due_at DATETIME DEFAULT NULL,
              version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              open_user_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('triggered','in-review') AND is_deleted=0 THEN user_no ELSE NULL END) STORED,
              UNIQUE KEY uk_admin_risk_kyc_ticket (ticket_id),
              UNIQUE KEY uk_admin_risk_kyc_open_user (open_user_key),
              KEY idx_admin_risk_kyc_status (status,is_deleted),
              KEY idx_admin_risk_kyc_user (user_no,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createKycReviewTicketTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_review_source (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              ticket_id VARCHAR(64) NOT NULL,
              source_domain VARCHAR(16) NOT NULL,
              source_no VARCHAR(128) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_kyc_review_source (ticket_id,source_domain,source_no),
              KEY idx_admin_risk_kyc_review_source_time (source_domain,created_at,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createKycReviewSourceTable();

    @Insert("""
            INSERT IGNORE INTO nx_admin_risk_kyc_review_source(ticket_id,source_domain,source_no,is_deleted)
            VALUES (#{ticketId},#{sourceDomain},#{sourceNo},0)
            """)
    int insertKycReviewSource(@Param("ticketId") String ticketId,
                              @Param("sourceDomain") String sourceDomain,
                              @Param("sourceNo") String sourceNo);

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_score_kyc_trigger_state (
              user_no VARCHAR(64) PRIMARY KEY,
              above_threshold TINYINT NOT NULL DEFAULT 0,
              last_score INT NOT NULL,
              last_threshold INT NOT NULL,
              last_transition_id VARCHAR(160) NOT NULL,
              trigger_sequence BIGINT NOT NULL DEFAULT 0,
              version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              KEY idx_k4_k5_trigger_threshold (last_threshold,above_threshold,updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createK4KycReviewTriggerStateTable();

    @Select("""
            SELECT user_no AS userNo,above_threshold AS aboveThreshold,last_score AS lastScore,
                   last_threshold AS lastThreshold,last_transition_id AS lastTransitionId,
                   trigger_sequence AS triggerSequence,version
              FROM nx_admin_risk_score_kyc_trigger_state
             WHERE user_no=#{userNo}
             FOR UPDATE
            """)
    K4KycTriggerStateRecord findK4KycTriggerStateForUpdate(@Param("userNo") String userNo);

    @Insert("""
            INSERT INTO nx_admin_risk_score_kyc_trigger_state
              (user_no,above_threshold,last_score,last_threshold,last_transition_id,trigger_sequence,version)
            VALUES
              (#{userNo},#{above},#{score},#{threshold},#{transitionId},#{triggerSequence},0)
            """)
    int insertK4KycTriggerState(
            @Param("userNo") String userNo,
            @Param("above") boolean above,
            @Param("score") int score,
            @Param("threshold") int threshold,
            @Param("transitionId") String transitionId,
            @Param("triggerSequence") long triggerSequence);

    @Update("""
            UPDATE nx_admin_risk_score_kyc_trigger_state
               SET above_threshold=#{above},last_score=#{score},last_threshold=#{threshold},
                   last_transition_id=#{transitionId},
                   trigger_sequence=trigger_sequence+#{triggerIncrement},version=version+1,updated_at=NOW()
             WHERE user_no=#{userNo} AND version=#{expectedVersion}
            """)
    int updateK4KycTriggerState(
            @Param("userNo") String userNo,
            @Param("above") boolean above,
            @Param("score") int score,
            @Param("threshold") int threshold,
            @Param("transitionId") String transitionId,
            @Param("triggerIncrement") int triggerIncrement,
            @Param("expectedVersion") long expectedVersion);

    @Select("""
            SELECT s.user_no
              FROM nx_admin_risk_score_user s
              JOIN nx_admin_risk_score_model m
                ON m.state='active' AND m.is_deleted=0
               AND s.model_version=CONCAT('k4-v',m.model_version)
              LEFT JOIN nx_admin_risk_score_kyc_trigger_state t ON t.user_no=s.user_no
             WHERE s.is_deleted=0
               AND s.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
               AND (t.user_no IS NULL OR t.last_threshold<>#{threshold})
             ORDER BY s.user_no ASC
             LIMIT #{limit}
            """)
    List<String> scoreUserNosNeedingKycTriggerThresholdSync(
            @Param("threshold") int threshold, @Param("limit") int limit);

    @Select("""
            SELECT source_domain AS sourceDomain,source_no AS sourceNo
              FROM nx_admin_risk_kyc_review_source
             WHERE ticket_id=#{ticketId} AND is_deleted=0
             ORDER BY id ASC
            """)
    List<KycReviewSourceRecord> kycReviewSources(@Param("ticketId") String ticketId);

    @Insert("""
            INSERT IGNORE INTO nx_admin_risk_kyc_review_source(ticket_id,source_domain,source_no,is_deleted)
            SELECT t.ticket_id,
                   MAX(CASE WHEN j.item_key='sourceDomain' THEN j.item_value END),
                   MAX(CASE WHEN j.item_key='sourceNo' THEN j.item_value END),0
              FROM nx_admin_risk_kyc_review_ticket t
              JOIN JSON_TABLE(
                    CASE WHEN JSON_VALID(t.info_json) THEN t.info_json ELSE JSON_ARRAY() END,
                    '$[*]' COLUMNS(item_key VARCHAR(64) PATH '$[0]',item_value VARCHAR(128) PATH '$[1]')
                   ) j
             WHERE t.is_deleted=0
             GROUP BY t.ticket_id
            HAVING MAX(CASE WHEN j.item_key='sourceDomain' THEN j.item_value END) IN ('D2','G2')
               AND MAX(CASE WHEN j.item_key='sourceNo' THEN j.item_value END) IS NOT NULL
            """)
    int backfillKycReviewSources();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN amount_usdt DECIMAL(20,8) DEFAULT NULL AFTER amount_text")
    void addKycTicketAmountColumn();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN due_at DATETIME DEFAULT NULL AFTER reviewed_at")
    void addKycTicketDueAtColumn();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER due_at")
    void addKycTicketVersionColumn();

    @Update("UPDATE nx_admin_risk_kyc_review_ticket SET status='in-review' WHERE status='triggered' AND is_deleted=0")
    int promoteTriggeredKycTickets();

    @Update("""
            UPDATE nx_admin_risk_kyc_review_ticket t
            JOIN nx_admin_risk_kyc_review_ticket newer
              ON newer.user_no=t.user_no AND newer.id>t.id
             AND newer.status IN ('triggered','in-review') AND newer.is_deleted=0
               SET t.status='rejected',t.is_deleted=1,t.decision_reason='MIGRATION_MERGED_DUPLICATE',t.updated_at=NOW()
             WHERE t.status IN ('triggered','in-review') AND t.is_deleted=0
            """)
    int mergeDuplicateOpenKycTickets();

    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND COLUMN_NAME='open_user_key'")
    int countKycTicketOpenUserKeyColumn();

    @Select("SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND COLUMN_NAME='open_user_key' LIMIT 1")
    String kycTicketOpenUserKeyExpression();

    @Select("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket' AND INDEX_NAME='uk_admin_risk_kyc_open_user'")
    int countKycTicketOpenUserUniqueKey();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket DROP INDEX uk_admin_risk_kyc_open_user")
    void dropKycTicketOpenUserUniqueKey();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket DROP COLUMN open_user_key")
    void dropKycTicketOpenUserKeyColumn();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket ADD COLUMN open_user_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('triggered','in-review') AND is_deleted=0 THEN user_no ELSE NULL END) STORED")
    void addKycTicketOpenUserKeyColumn();

    @Update("ALTER TABLE nx_admin_risk_kyc_review_ticket ADD UNIQUE KEY uk_admin_risk_kyc_open_user (open_user_key)")
    void addKycTicketOpenUserUniqueKey();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_alert (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              event_key VARCHAR(128) DEFAULT NULL,
              tone VARCHAR(32) NOT NULL,
              title VARCHAR(128) NOT NULL,
              body VARCHAR(1000) NOT NULL,
              time_text VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_risk_kyc_alert_event (event_key),
              KEY idx_admin_risk_kyc_alert_tone (tone,is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createKycAlertTable();

    @Update("ALTER TABLE nx_admin_risk_kyc_alert ADD COLUMN event_key VARCHAR(128) DEFAULT NULL AFTER id")
    void addKycAlertEventKeyColumn();

    @Update("ALTER TABLE nx_admin_risk_kyc_alert ADD UNIQUE KEY uk_admin_risk_kyc_alert_event (event_key)")
    void addKycAlertEventUniqueKey();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_risk_kyc_alert_subscription (
              operator_name VARCHAR(64) PRIMARY KEY,
              alert_types_json TEXT NOT NULL,
              channels_json TEXT NOT NULL,
              version BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createKycAlertSubscriptionTable();

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
            INSERT INTO nx_risk_signal (
                signal_no, user_id, signal_type, severity, evidence, created_by, created_at, updated_at, is_deleted
            ) VALUES (#{signalNo}, #{userId}, #{signalType}, #{severity}, #{evidence}, #{operator}, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE signal_no = VALUES(signal_no)
            """)
    int insertSignalIfAbsent(@Param("signalNo") String signalNo, @Param("userId") Long userId,
                             @Param("signalType") String signalType, @Param("severity") String severity,
                             @Param("evidence") String evidence, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_admin_risk_score_user
              (user_no,model_score,model_version,updated_text,is_deleted)
            VALUES (#{userNo},0,'tamper-v1','刚刚',0)
            ON DUPLICATE KEY UPDATE
              model_score = IF(is_deleted = 1, 0, model_score),
              model_version = 'tamper-v1', updated_text = '刚刚',
              updated_at = NOW(), is_deleted = 0
            """)
    int ensureTamperScoreUser(@Param("userNo") String userNo);

    @Select("""
            SELECT model_score
              FROM nx_admin_risk_score_user
             WHERE user_no = #{userNo} AND is_deleted = 0
             LIMIT 1
             FOR UPDATE
            """)
    Integer lockTamperScoreValue(@Param("userNo") String userNo);

    @Update("""
            INSERT INTO nx_admin_risk_score_user (user_no,model_score,model_version,updated_text,is_deleted)
            VALUES (#{userNo},#{points},'tamper-v1','刚刚',0)
            ON DUPLICATE KEY UPDATE
              model_score = LEAST(100, model_score + VALUES(model_score)),
              model_version = 'tamper-v1', updated_text = '刚刚', updated_at = NOW(), is_deleted = 0
            """)
    int applyTamperScore(@Param("userNo") String userNo, @Param("points") int points);

    @Select("""
            SELECT model_score
              FROM nx_admin_risk_score_user
             WHERE user_no = #{userNo} AND is_deleted = 0
             LIMIT 1
            """)
    Integer scoreValue(@Param("userNo") String userNo);

    @Insert("""
            INSERT INTO nx_admin_risk_score_contribution
              (user_no,name,evidence,points,sort_order,is_deleted)
            VALUES (#{userNo},'篡改拦截',#{evidence},#{points},90,0)
            """)
    int insertTamperScoreContribution(@Param("userNo") String userNo,
                                      @Param("evidence") String evidence,
                                      @Param("points") int points);

    @Select("""
            SELECT COUNT(*) AS signalCount,
                   COUNT(DISTINCT user_id) AS accountCount,
                   COALESCE(DATE_FORMAT(MAX(created_at), '%Y-%m-%d %H:%i:%s'), '') AS latestAt
              FROM nx_risk_signal
             WHERE signal_type = 'TAMPER_DETECTED'
               AND created_at >= #{since}
               AND is_deleted = 0
            """)
    TamperRadarRecord tamperRadarSnapshot(@Param("since") java.time.LocalDateTime since);

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

    @Select("SELECT COUNT(*) FROM nx_admin_risk_withdraw_rule WHERE is_deleted = 0")
    long countWithdrawRules();

    @Select("""
            SELECT rule_id AS ruleId,dimension,condition_text AS conditionText,action,state,built_in AS builtIn,
                   priority,version,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_admin_risk_withdraw_rule
             WHERE is_deleted = 0
             ORDER BY FIELD(state,'active','paused','draft','archived'), built_in DESC, id ASC
            """)
    List<RiskRuleView> withdrawRules();

    @Select("""
            SELECT rule_id AS ruleId,dimension,condition_text AS conditionText,action,state,built_in AS builtIn,
                   priority,version,created_at AS createdAt,updated_at AS updatedAt
              FROM nx_admin_risk_withdraw_rule
             WHERE is_deleted = 0
             ORDER BY FIELD(state,'active','paused','draft','archived'), built_in DESC, id ASC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<RiskRuleView> withdrawRulesPage(@Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT rule_id AS ruleId,dimension,condition_text AS conditionText,action,state,built_in AS builtIn,
                   priority,version,created_at AS createdAt,updated_at AS updatedAt
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
                   (
                       SELECT COALESCE(SUM(w3.amount), 0)
                         FROM nx_withdrawal_order w3
                        WHERE w3.user_id = w.user_id
                          AND w3.is_deleted = 0
                          AND w3.created_at >= DATE_SUB(w.created_at, INTERVAL 24 HOUR)
                          AND w3.created_at <= w.created_at
                   ) AS withdrawalSum24h,
                   GREATEST(TIMESTAMPDIFF(DAY, u.created_at, w.created_at), 0) AS accountAgeDays,
                   CASE
                     WHEN UPPER(COALESCE(rd.rule_codes, '')) REGEXP 'ADDRESS_(BLACKLIST|REPUTATION_LOW)'
                       OR UPPER(COALESCE(rd.reason, '')) REGEXP 'ADDRESS_(BLACKLIST|REPUTATION_LOW)'
                     THEN 'low'
                     ELSE 'normal'
                    END AS addressReputation,
                    w.chain AS chain,
                    w.target_address AS targetAddress,
                    CONCAT_WS(' ',
                       COALESCE(rd.rule_codes, ''),
                       COALESCE(rd.reason, ''),
                       COALESCE(u.status, ''),
                       COALESCE(kyc.status, '')
                   ) AS existingSignals
              FROM nx_withdrawal_order w
              LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
              LEFT JOIN nx_user u ON u.id = w.user_id AND u.is_deleted = 0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=w.user_id AND kyc.is_deleted=0
             WHERE w.is_deleted = 0
               AND w.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
               AND w.status IN ('PENDING','REVIEWING','FROZEN','REJECTED','PENDING_CHAIN')
             ORDER BY w.id DESC
             LIMIT #{limit}
            """)
    List<RiskWithdrawCandidateView> withdrawRuleCandidates(@Param("limit") int limit);

    @Insert("""
            INSERT INTO nx_admin_risk_withdraw_rule (
                rule_id, dimension, condition_text, action, state, built_in, priority, version,
                created_by, created_at, updated_at, is_deleted
            ) VALUES (#{ruleId}, #{dimension}, #{conditionText}, #{action}, #{state}, #{builtIn}, #{priority}, 0,
                      #{operator}, NOW(), NOW(), 0)
            """)
    int insertWithdrawRule(@Param("ruleId") String ruleId, @Param("dimension") String dimension,
                           @Param("conditionText") String conditionText, @Param("action") String action,
                           @Param("state") String state, @Param("builtIn") boolean builtIn,
                           @Param("priority") int priority, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_risk_decision (
                decision_no,user_id,biz_type,biz_no,decision,reason,risk_score,
                rule_codes,rule_snapshot,reviewed_by,reviewed_at,created_at,updated_at,is_deleted
            ) VALUES (
                #{decisionNo},#{userId},'WITHDRAW_RULE',#{withdrawalNo},#{decision},#{reason},#{riskScore},
                #{ruleCodes},#{ruleSnapshot},'system',NOW(),NOW(),NOW(),0
            )
            ON DUPLICATE KEY UPDATE
                decision=VALUES(decision),reason=VALUES(reason),risk_score=VALUES(risk_score),
                rule_codes=VALUES(rule_codes),rule_snapshot=VALUES(rule_snapshot),
                reviewed_by='system',reviewed_at=NOW(),updated_at=NOW(),is_deleted=0
            """)
    int upsertWithdrawRuleDecision(
            @Param("decisionNo") String decisionNo,
            @Param("userId") Long userId,
            @Param("withdrawalNo") String withdrawalNo,
            @Param("decision") String decision,
            @Param("reason") String reason,
            @Param("riskScore") int riskScore,
            @Param("ruleCodes") String ruleCodes,
            @Param("ruleSnapshot") String ruleSnapshot);

    @Update("""
            UPDATE nx_admin_risk_withdraw_rule
               SET state = #{state}, version = version + 1, updated_at = NOW()
             WHERE rule_id = #{ruleId} AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int updateWithdrawRuleState(
            @Param("ruleId") String ruleId, @Param("expectedVersion") long expectedVersion,
            @Param("state") String state);

    @Update("""
            UPDATE nx_admin_risk_withdraw_rule
               SET condition_text = #{conditionText}, action = #{action}, priority = #{priority},
                   version = version + 1, updated_at = NOW()
             WHERE rule_id = #{ruleId} AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int updateWithdrawRuleConfiguration(
            @Param("ruleId") String ruleId, @Param("expectedVersion") long expectedVersion,
            @Param("conditionText") String conditionText, @Param("action") String action,
            @Param("priority") int priority);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_route_count WHERE is_deleted = 0")
    long countRouteCounts();

    @Select("""
            SELECT action AS routeKey,
                   CASE action WHEN 'delay' THEN '延迟' WHEN 'manual' THEN '转人工'
                               WHEN 'freeze' THEN '冻结' ELSE action END AS label,
                   COUNT(*) AS count,
                   CASE action WHEN 'delay' THEN 'var(--amber)' WHEN 'manual' THEN 'var(--cyan)'
                               WHEN 'freeze' THEN 'var(--red)' ELSE 'var(--muted)' END AS color
              FROM nx_admin_risk_withdraw_hit
             WHERE is_deleted = 0
             GROUP BY action
             ORDER BY FIELD(action,'delay','manual','freeze')
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
                   DATE_FORMAT(h.created_at, '%Y-%m-%d %H:%i:%s') AS timeText
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
                   DATE_FORMAT(h.created_at, '%Y-%m-%d %H:%i:%s') AS timeText
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

    @Insert("INSERT IGNORE INTO nx_admin_risk_withdraw_hit (withdrawal_no,user_no,amount_text,rule_id,dimension,action,time_text,is_deleted) VALUES (#{withdrawalNo},#{userNo},#{amountText},#{ruleId},#{dimension},#{action},#{timeText},0)")
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
            SELECT param_key AS `key`,name,value_text AS value,sub_text AS sub,note_text AS note,version
              FROM nx_admin_risk_arbitrage_param
             WHERE is_deleted = 0
             ORDER BY id ASC
            """)
    List<RiskArbitrageParamView> arbitrageParams();

    @Select("""
            SELECT param_key AS `key`,name,value_text AS value,sub_text AS sub,note_text AS note,version
              FROM nx_admin_risk_arbitrage_param
             WHERE param_key = #{key} AND is_deleted = 0
             LIMIT 1
            """)
    RiskArbitrageParamView findArbitrageParam(@Param("key") String key);

    @Insert("""
            INSERT INTO nx_admin_risk_arbitrage_param (param_key,name,value_text,sub_text,note_text,version,is_deleted)
            VALUES (#{key},#{name},#{value},#{sub},#{note},#{version},0)
            """)
    int insertArbitrageParam(@Param("key") String key, @Param("name") String name, @Param("value") String value,
                             @Param("sub") String sub, @Param("note") String note, @Param("version") long version);

    @Update("""
            UPDATE nx_admin_risk_arbitrage_param
               SET name = #{name}, value_text = #{value}, sub_text = #{sub}, note_text = #{note},
                   version = version + 1, is_deleted = 0, updated_at = NOW()
             WHERE param_key = #{key} AND version = #{expectedVersion} AND is_deleted = 1
            """)
    int restoreArbitrageParam(@Param("key") String key, @Param("name") String name,
                              @Param("value") String value, @Param("sub") String sub,
                              @Param("note") String note, @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE nx_admin_risk_arbitrage_param
               SET value_text = #{value}, version = version + 1, updated_at = NOW()
             WHERE param_key = #{key} AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int updateArbitrageParam(@Param("key") String key, @Param("expectedVersion") long expectedVersion,
                             @Param("value") String value);

    @Update("""
            UPDATE nx_admin_risk_arbitrage_row
               SET is_deleted = 1, updated_at = NOW()
             WHERE row_id LIKE 'K2-E3-U%'
               AND is_deleted = 0
            """)
    int retireE3TradeinArbitrageRows();

    @Insert("""
            INSERT INTO nx_admin_risk_arbitrage_row (
                row_id,view_key,cluster_id,cell1,cell2,cell3,cell4,cell5,cell6,
                level_value,actions_csv,is_deleted
            )
            SELECT CONCAT('K2-E3-U', a.user_id),
                   'tradein',
                   NULL,
                   CONCAT('U', LPAD(a.user_id, 8, '0')),
                   CONCAT(COUNT(DISTINCT a.id), ' 次置换'),
                   '近 30 天',
                   CONCAT(COUNT(DISTINCT a.id), ' 次高频下架置换'),
                   CONCAT_WS(' / ',
                       CASE WHEN COUNT(DISTINCT c.id) > 0
                            THEN CONCAT(COUNT(DISTINCT c.id), ' 笔返佣') END,
                       CASE WHEN COUNT(DISTINCT l.id) > 0
                            THEN CONCAT(COUNT(DISTINCT l.id), ' 笔礼金') END),
                   NULL,
                   4,
                   'flag',
                   0
              FROM nx_tradein_application a
              LEFT JOIN nx_commission_event c
                ON c.user_id = a.user_id
               AND c.is_deleted = 0
               AND c.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
               AND UPPER(c.status) NOT IN ('FAILED','REJECTED','CANCELLED')
               AND c.amount_usdt > 0
              LEFT JOIN nx_wallet_ledger l
                ON l.user_id = a.user_id
               AND l.is_deleted = 0
               AND l.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
               AND UPPER(l.biz_type) LIKE '%GIFT%'
               AND UPPER(l.status) IN ('POSTED','SUCCESS','COMPLETED')
               AND UPPER(l.direction) = 'IN'
               AND l.amount > 0
             WHERE a.is_deleted = 0
               AND UPPER(a.status) = 'COMPLETED'
               AND a.completed_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
             GROUP BY a.user_id
            HAVING COUNT(DISTINCT a.id) >= 3
               AND (COUNT(DISTINCT c.id) > 0 OR COUNT(DISTINCT l.id) > 0)
            ON DUPLICATE KEY UPDATE
                view_key = VALUES(view_key),
                cluster_id = VALUES(cluster_id),
                cell1 = VALUES(cell1),
                cell2 = VALUES(cell2),
                cell3 = VALUES(cell3),
                cell4 = VALUES(cell4),
                cell5 = VALUES(cell5),
                cell6 = VALUES(cell6),
                level_value = VALUES(level_value),
                actions_csv = VALUES(actions_csv),
                is_deleted = 0,
                updated_at = NOW()
            """)
    int upsertE3TradeinArbitrageRows();

    @Update("""
            UPDATE nx_admin_risk_arbitrage_row
               SET is_deleted = 1, updated_at = NOW()
             WHERE row_id LIKE 'K2-H2-U%'
               AND is_deleted = 0
            """)
    int retireH2TrialCycleRows();

    @Insert("""
            INSERT INTO nx_admin_risk_arbitrage_row (
                row_id,view_key,cluster_id,cell1,cell2,cell3,cell4,cell5,cell6,
                level_value,actions_csv,is_deleted
            )
            SELECT CONCAT('K2-H2-U', facts.user_id),
                   'trial',
                   facts.cluster_id,
                   CONCAT('U', LPAD(facts.user_id, 8, '0')),
                   CONCAT(facts.cycle_count, ' 次 / ', #{windowDays}, ' 天'),
                   CONCAT(COALESCE(JSON_LENGTH(cluster.nodes_json), 1), ' 个关联账户'),
                   CONCAT('H2 服务器试用开始事件 ', facts.cycle_count, ' 次'),
                   NULL,
                   NULL,
                   LEAST(5, GREATEST(2, facts.cycle_count)),
                   IF(facts.cluster_id IS NULL, 'flag', 'flag,freeze'),
                   0
              FROM (
                    SELECT starts.user_id,
                           COUNT(*) AS cycle_count,
                           (
                             SELECT c.cluster_id
                               FROM nx_admin_risk_multi_account_cluster c
                              WHERE c.is_deleted = 0
                                AND c.nodes_json IS NOT NULL
                                AND JSON_VALID(c.nodes_json) = 1
                                AND JSON_SEARCH(c.nodes_json, 'one', CONCAT('U', LPAD(starts.user_id, 8, '0'))) IS NOT NULL
                              ORDER BY FIELD(c.status, 'frozen', 'flagged', 'detected', 'released', 'cleared'), c.id DESC
                              LIMIT 1
                           ) AS cluster_id
                      FROM (
                            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.user_id')) AS UNSIGNED) AS user_id
                              FROM nx_event_outbox e
                             WHERE e.is_deleted = 0
                               AND e.event_name = 'trial.started'
                               AND e.analytics_event = 1
                               AND e.schema_registered = 1
                               AND e.is_server_authoritative = 1
                               AND e.created_at >= DATE_SUB(NOW(), INTERVAL #{windowDays} DAY)
                               AND JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.user_id')) REGEXP '^[1-9][0-9]*$'
                           ) starts
                      JOIN nx_user u ON u.id = starts.user_id AND u.is_deleted = 0
                     GROUP BY starts.user_id
                    HAVING COUNT(*) >= #{minimumCycles}
                   ) facts
              LEFT JOIN nx_admin_risk_multi_account_cluster cluster
                ON cluster.cluster_id = facts.cluster_id AND cluster.is_deleted = 0
            ON DUPLICATE KEY UPDATE
                view_key = VALUES(view_key),
                cluster_id = VALUES(cluster_id),
                cell1 = VALUES(cell1),
                cell2 = VALUES(cell2),
                cell3 = VALUES(cell3),
                cell4 = VALUES(cell4),
                cell5 = VALUES(cell5),
                cell6 = VALUES(cell6),
                level_value = VALUES(level_value),
                actions_csv = VALUES(actions_csv),
                is_deleted = 0,
                updated_at = NOW()
            """)
    int upsertH2TrialCycleRows(@Param("minimumCycles") int minimumCycles,
                               @Param("windowDays") int windowDays);

    @Select("""
            SELECT r.row_id AS rowId,
                   u.id AS userId,
                   r.cluster_id AS clusterId,
                   COUNT(e.id) AS cycleCount
              FROM nx_admin_risk_arbitrage_row r
              JOIN nx_user u
                ON r.row_id = CONCAT('K2-H2-U', u.id)
               AND u.is_deleted = 0
              JOIN nx_event_outbox e
                ON CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.user_id')) AS UNSIGNED) = u.id
               AND e.is_deleted = 0
               AND e.event_name = 'trial.started'
               AND e.analytics_event = 1
               AND e.schema_registered = 1
               AND e.is_server_authoritative = 1
               AND e.created_at >= DATE_SUB(NOW(), INTERVAL #{windowDays} DAY)
             WHERE r.is_deleted = 0
               AND r.view_key = 'trial'
               AND r.row_id LIKE 'K2-H2-U%'
             GROUP BY r.row_id, u.id, r.cluster_id
            HAVING COUNT(e.id) >= #{minimumCycles}
             ORDER BY u.id ASC
            """)
    List<TrialCycleDetectionRecord> trialCycleDetections(
            @Param("minimumCycles") int minimumCycles,
            @Param("windowDays") int windowDays);

    @Select("""
            SELECT DISTINCT u.id
              FROM nx_admin_risk_arbitrage_row r
              JOIN nx_user u ON u.is_deleted = 0
             WHERE r.row_id = #{rowId}
               AND r.is_deleted = 0
               AND (
                    r.row_id = CONCAT('K2-H2-U', u.id)
                 OR r.row_id = CONCAT('K2-E3-U', u.id)
                 OR r.cell1 = CONCAT('U', LPAD(u.id, 8, '0'))
                 OR EXISTS (
                      SELECT 1
                        FROM nx_admin_risk_multi_account_cluster c
                       WHERE c.cluster_id = r.cluster_id
                         AND c.is_deleted = 0
                         AND c.nodes_json IS NOT NULL
                         AND JSON_VALID(c.nodes_json) = 1
                         AND JSON_SEARCH(c.nodes_json, 'one', CONCAT('U', LPAD(u.id, 8, '0'))) IS NOT NULL
                    )
               )
             ORDER BY u.id ASC
            """)
    List<Long> arbitrageSubjectUserIds(@Param("rowId") String rowId);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_arbitrage_row WHERE is_deleted = 0")
    long countArbitrageRows();

    @Select("""
            SELECT r.row_id AS rowId,r.view_key AS viewKey,r.cluster_id AS clusterId,
                   r.cell1,r.cell2,r.cell3,r.cell4,r.cell5,r.cell6,r.level_value AS level,
                   r.actions_csv AS actionsCsv,r.disposition,r.version,
                   c.status AS clusterStatus,c.version AS clusterVersion
              FROM nx_admin_risk_arbitrage_row r
              LEFT JOIN nx_admin_risk_multi_account_cluster c
                ON c.cluster_id = r.cluster_id AND c.is_deleted = 0
             WHERE r.is_deleted = 0
             ORDER BY FIELD(r.view_key,'trial','tradein','gift','board'), r.id ASC
            """)
    List<RiskArbitrageRowRecord> arbitrageRows();

    @Select("""
            SELECT r.row_id AS rowId,r.view_key AS viewKey,r.cluster_id AS clusterId,
                   r.cell1,r.cell2,r.cell3,r.cell4,r.cell5,r.cell6,r.level_value AS level,
                   r.actions_csv AS actionsCsv,r.disposition,r.version,
                   c.status AS clusterStatus,c.version AS clusterVersion
              FROM nx_admin_risk_arbitrage_row r
              LEFT JOIN nx_admin_risk_multi_account_cluster c
                ON c.cluster_id = r.cluster_id AND c.is_deleted = 0
             WHERE r.row_id = #{rowId} AND r.is_deleted = 0
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
               SET disposition = #{disposition}, version = version + 1, updated_at = NOW()
             WHERE row_id = #{rowId} AND version = #{expectedVersion}
               AND disposition IS NULL AND is_deleted = 0
            """)
    int updateArbitrageDisposition(@Param("rowId") String rowId,
                                   @Param("expectedVersion") long expectedVersion,
                                   @Param("disposition") String disposition);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_dimension WHERE is_deleted = 0")
    long countScoreDimensions();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_model WHERE is_deleted=0")
    long countScoreModels();

    @Insert("""
            INSERT INTO nx_admin_risk_score_model
              (model_version,row_version,state,weights_json,input_sources_json,score_mapping_json,band_low_max,band_high_min,
               auto_escalate_score,reason,created_by,published_by,published_at,is_deleted)
            VALUES (1,0,'active',#{weightsJson},#{sourcesJson},#{mappingJson},40,70,85,
                    'K4 canonical initial model','system','system',NOW(),0)
            """)
    int insertInitialScoreModel(@Param("weightsJson") String weightsJson, @Param("sourcesJson") String sourcesJson,
                                @Param("mappingJson") String mappingJson);

    @Select("""
            SELECT model_version AS modelVersion,row_version AS rowVersion,state,weights_json AS weightsJson,
                   input_sources_json AS inputSourcesJson,score_mapping_json AS scoreMappingJson,
                   band_low_max AS bandLowMax,band_high_min AS bandHighMin,
                   auto_escalate_score AS autoEscalateScore,reason,created_by AS createdBy,
                   published_by AS publishedBy,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS createdAt,
                   DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s') AS publishedAt
              FROM nx_admin_risk_score_model
             WHERE state='active' AND is_deleted=0
             ORDER BY model_version DESC LIMIT 1
            """)
    ScoreModelRecord activeScoreModel();

    @Select("""
            SELECT model_version AS modelVersion,row_version AS rowVersion,state,weights_json AS weightsJson,
                   input_sources_json AS inputSourcesJson,score_mapping_json AS scoreMappingJson,
                   band_low_max AS bandLowMax,band_high_min AS bandHighMin,
                   auto_escalate_score AS autoEscalateScore,reason,created_by AS createdBy,
                   published_by AS publishedBy,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS createdAt,
                   DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s') AS publishedAt
              FROM nx_admin_risk_score_model
             WHERE state='draft' AND is_deleted=0
             ORDER BY model_version DESC LIMIT 1
            """)
    ScoreModelRecord draftScoreModel();

    @Select("""
            SELECT model_version AS modelVersion,row_version AS rowVersion,state,weights_json AS weightsJson,
                   input_sources_json AS inputSourcesJson,score_mapping_json AS scoreMappingJson,
                   band_low_max AS bandLowMax,band_high_min AS bandHighMin,
                   auto_escalate_score AS autoEscalateScore,reason,created_by AS createdBy,
                   published_by AS publishedBy,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS createdAt,
                   DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s') AS publishedAt
              FROM nx_admin_risk_score_model
             WHERE is_deleted=0
             ORDER BY model_version DESC
            """)
    List<ScoreModelRecord> scoreModels();

    @Select("""
            SELECT model_version AS modelVersion,row_version AS rowVersion,state,weights_json AS weightsJson,
                   input_sources_json AS inputSourcesJson,score_mapping_json AS scoreMappingJson,
                   band_low_max AS bandLowMax,band_high_min AS bandHighMin,
                   auto_escalate_score AS autoEscalateScore,reason,created_by AS createdBy,
                   published_by AS publishedBy,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS createdAt,
                   DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s') AS publishedAt
              FROM nx_admin_risk_score_model
             WHERE model_version=#{modelVersion} AND is_deleted=0
             LIMIT 1
            """)
    ScoreModelRecord scoreModel(@Param("modelVersion") long modelVersion);

    @Select("SELECT id FROM nx_admin_risk_score_model WHERE state='active' AND is_deleted=0 ORDER BY model_version DESC LIMIT 1 FOR UPDATE")
    Long lockActiveScoreModel();

    @Select("SELECT id FROM nx_admin_risk_score_model WHERE state='draft' AND is_deleted=0 ORDER BY model_version DESC LIMIT 1 FOR UPDATE")
    Long lockDraftScoreModel();

    @Insert("""
            INSERT INTO nx_admin_risk_score_model
              (model_version,row_version,state,weights_json,input_sources_json,score_mapping_json,band_low_max,band_high_min,
               auto_escalate_score,reason,created_by,is_deleted)
            VALUES (#{modelVersion},0,'draft',#{weightsJson},#{sourcesJson},#{mappingJson},#{bandLowMax},#{bandHighMin},
                    #{autoEscalateScore},#{reason},#{operator},0)
            """)
    int insertScoreModelDraft(
            @Param("modelVersion") long modelVersion,
            @Param("weightsJson") String weightsJson,
            @Param("sourcesJson") String sourcesJson,
            @Param("mappingJson") String mappingJson,
            @Param("bandLowMax") int bandLowMax,
            @Param("bandHighMin") int bandHighMin,
            @Param("autoEscalateScore") int autoEscalateScore,
            @Param("reason") String reason,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_admin_risk_score_model
               SET weights_json=#{weightsJson},input_sources_json=#{sourcesJson},
                   score_mapping_json=#{mappingJson},
                   band_low_max=#{bandLowMax},band_high_min=#{bandHighMin},
                   auto_escalate_score=#{autoEscalateScore},reason=#{reason},created_by=#{operator},
                   row_version=row_version+1,updated_at=NOW()
             WHERE state='draft' AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int updateScoreModelDraft(
            @Param("expectedVersion") long expectedVersion,
            @Param("weightsJson") String weightsJson,
            @Param("sourcesJson") String sourcesJson,
            @Param("mappingJson") String mappingJson,
            @Param("bandLowMax") int bandLowMax,
            @Param("bandHighMin") int bandHighMin,
            @Param("autoEscalateScore") int autoEscalateScore,
            @Param("reason") String reason,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_admin_risk_score_model
               SET state='archived',archived_at=NOW(),row_version=row_version+1,updated_at=NOW()
             WHERE state='active' AND model_version<>#{exceptVersion} AND is_deleted=0
            """)
    int archiveActiveScoreModel(@Param("exceptVersion") long exceptVersion);

    @Update("""
            UPDATE nx_admin_risk_score_model
               SET state='active',published_by=#{operator},published_at=NOW(),reason=#{reason},
                   row_version=row_version+1,updated_at=NOW()
             WHERE state='draft' AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int activateScoreModelDraft(
            @Param("expectedVersion") long expectedVersion,
            @Param("operator") String operator,
            @Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_model WHERE state='archived' AND is_deleted=0")
    long countArchivedScoreModels();

    @Insert("""
            INSERT INTO nx_admin_risk_score_dimension(dim_key,name,source_label,weight_pct,sort_order,is_deleted)
            VALUES(#{dimKey},#{name},#{source},#{weightPct},#{sortOrder},0)
            ON DUPLICATE KEY UPDATE name=VALUES(name),source_label=VALUES(source_label),
              weight_pct=VALUES(weight_pct),sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW()
            """)
    int upsertScoreDimension(@Param("dimKey") String dimKey,@Param("name") String name,
                             @Param("source") String source,@Param("weightPct") int weightPct,
                             @Param("sortOrder") int sortOrder);

    @Update("""
            <script>
            UPDATE nx_admin_risk_score_dimension SET is_deleted=1,updated_at=NOW()
             WHERE dim_key NOT IN
             <foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach>
               AND is_deleted=0
            </script>
            """)
    int retireNonCanonicalScoreDimensions(@Param("keys") java.util.Set<String> keys);

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

    @Insert("""
            INSERT INTO nx_admin_risk_score_config(config_key,value_text,is_deleted)
            VALUES(#{key},#{value},0)
            ON DUPLICATE KEY UPDATE value_text=VALUES(value_text),is_deleted=0,updated_at=NOW()
            """)
    int upsertScoreConfig(@Param("key") String key, @Param("value") String value);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_distribution WHERE is_deleted = 0")
    long countScoreDistribution();

    @Select("SELECT COALESCE(SUM(count_value),0) FROM nx_admin_risk_score_distribution WHERE is_deleted = 0")
    long sumScoreDistribution();

    @Select("""
            SELECT COALESCE(SUM(effective_score < #{lowMax}),0) AS lowCount,
                   COALESCE(SUM(effective_score >= #{lowMax} AND effective_score < #{highMin}),0) AS midCount,
                   COALESCE(SUM(effective_score >= #{highMin}),0) AS highCount
              FROM (
                    SELECT COALESCE((SELECT o.override_score
                                       FROM nx_admin_risk_score_override o
                                      WHERE o.user_no=s.user_no AND o.active=1 AND o.is_deleted=0
                                      ORDER BY o.id DESC LIMIT 1),s.model_score) AS effective_score
                      FROM nx_admin_risk_score_user s
                     WHERE s.is_deleted=0
              ) scores
            """)
    ScoreDistributionCountRecord scoreDistributionCounts(
            @Param("lowMax") int lowMax,@Param("highMin") int highMin);

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

    @Update("""
            UPDATE nx_admin_risk_score_override o
              LEFT JOIN nx_user u
                ON CONCAT('U',LPAD(u.id,8,'0'))=o.user_no AND u.is_deleted=0
               SET o.active=0,o.updated_at=NOW()
             WHERE o.active=1 AND o.is_deleted=0 AND u.id IS NULL
            """)
    int deactivateOrphanScoreOverrides();

    @Update("""
            UPDATE nx_admin_risk_score_contribution c
              LEFT JOIN nx_user u
                ON CONCAT('U',LPAD(u.id,8,'0'))=c.user_no AND u.is_deleted=0
               SET c.is_deleted=1
             WHERE c.is_deleted=0 AND u.id IS NULL
            """)
    int retireOrphanScoreContributions();

    @Update("""
            UPDATE nx_admin_risk_score_user s
              LEFT JOIN nx_user u
                ON CONCAT('U',LPAD(u.id,8,'0'))=s.user_no AND u.is_deleted=0
               SET s.is_deleted=1,s.updated_at=NOW()
             WHERE s.is_deleted=0 AND u.id IS NULL
            """)
    int retireOrphanScoreUsers();

    @Insert("""
            INSERT INTO nx_admin_risk_score_user
              (user_no,model_score,model_version,row_version,as_of,updated_text,is_deleted)
            SELECT CONCAT('U',LPAD(u.id,8,'0')),0,'pending',0,NOW(),'待首次评分',0
              FROM nx_user u
             WHERE u.is_deleted=0
            ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW()
            """)
    int ensureAllActiveUsersHaveScoreRows();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_user WHERE is_deleted = 0")
    long countScoreUsers();

    @Select("""
            SELECT user_no AS userNo,model_score AS modelScore,model_version AS modelVersion,
                   row_version AS rowVersion,
                   COALESCE(DATE_FORMAT(as_of,'%Y-%m-%d %H:%i:%s'),DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s')) AS asOf,
                   updated_text AS updatedText
              FROM nx_admin_risk_score_user
             WHERE user_no = #{userNo} AND is_deleted = 0
             LIMIT 1
            """)
    ScoreUserRecord findScoreUser(@Param("userNo") String userNo);

    @Select("""
            SELECT s.user_no AS userNo,s.model_score AS modelScore,s.model_version AS modelVersion,
                   s.row_version AS rowVersion,
                   COALESCE(DATE_FORMAT(s.as_of,'%Y-%m-%d %H:%i:%s'),DATE_FORMAT(s.updated_at,'%Y-%m-%d %H:%i:%s')) AS asOf,
                   s.updated_text AS updatedText
              FROM nx_admin_risk_score_user s
              JOIN nx_admin_risk_score_model m
                ON m.state = 'active'
               AND m.is_deleted = 0
               AND s.model_version = CONCAT('k4-v', m.model_version)
             WHERE s.user_no = #{userNo}
               AND s.is_deleted = 0
               AND s.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
             LIMIT 1
            """)
    ScoreUserRecord findCurrentScoreUser(@Param("userNo") String userNo);

    @Select("""
            SELECT s.user_no
              FROM nx_admin_risk_score_user s
              JOIN nx_user u
                ON CONCAT('U',LPAD(u.id,8,'0'))=s.user_no
               AND u.is_deleted=0
             WHERE s.is_deleted=0
             ORDER BY s.id
            """)
    List<String> scoreUserNos();

    @Select("""
            SELECT s.user_no
              FROM nx_admin_risk_score_user s
              JOIN nx_user u
                ON CONCAT('U',LPAD(u.id,8,'0'))=s.user_no
               AND u.is_deleted=0
              LEFT JOIN (
                    SELECT user_no,COUNT(*) AS contribution_count,
                           COUNT(DISTINCT dim_key) AS dimension_count,
                           SUM(CASE WHEN dim_key IN ('multiAccount','arbitrage','kycStatus',
                               'withdrawVelocity','accountAge','anomalyBehavior') THEN 1 ELSE 0 END) AS canonical_count,
                           COALESCE(SUM(points),0) AS point_total
                      FROM nx_admin_risk_score_contribution
                     WHERE is_deleted=0
                     GROUP BY user_no
              ) c ON c.user_no=s.user_no
             WHERE s.is_deleted=0
               AND (COALESCE(s.model_version,'')<>CONCAT('k4-v',#{modelVersion})
                    OR COALESCE(c.contribution_count,0)<>6
                    OR COALESCE(c.dimension_count,0)<>6
                    OR COALESCE(c.canonical_count,0)<>6
                    OR s.model_score<>COALESCE(c.point_total,0)
                    OR u.updated_at > COALESCE(s.as_of,'1970-01-01')
                    OR EXISTS (SELECT 1 FROM nx_admin_risk_multi_account_cluster k1
                                WHERE k1.updated_at > COALESCE(s.as_of,'1970-01-01')
                                  AND k1.nodes_json IS NOT NULL AND JSON_VALID(k1.nodes_json)=1
                                  AND JSON_SEARCH(k1.nodes_json,'one',s.user_no) IS NOT NULL)
                    OR EXISTS (SELECT 1 FROM nx_admin_risk_arbitrage_row k2
                                WHERE k2.updated_at > COALESCE(s.as_of,'1970-01-01')
                                  AND CONCAT_WS('|',k2.cell1,k2.cell2,k2.cell3,k2.cell4,k2.cell5,k2.cell6)
                                      LIKE CONCAT('%',s.user_no,'%'))
                    OR EXISTS (SELECT 1 FROM nx_kyc_profile c4
                                WHERE c4.user_id=u.id
                                  AND c4.updated_at > COALESCE(s.as_of,'1970-01-01'))
                    OR EXISTS (SELECT 1 FROM nx_withdrawal_order withdraw_fact
                                WHERE withdraw_fact.user_id=u.id
                                  AND withdraw_fact.updated_at > COALESCE(s.as_of,'1970-01-01'))
                    OR EXISTS (SELECT 1 FROM nx_risk_signal j3
                                WHERE j3.user_id=u.id
                                  AND j3.updated_at > COALESCE(s.as_of,'1970-01-01'))
                    OR s.as_of < NOW() - INTERVAL 1 DAY)
             ORDER BY s.id
             LIMIT #{limit}
            """)
    List<String> scoreUserNosNeedingProjection(
            @Param("modelVersion") long modelVersion, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_score_user s
              JOIN nx_user u
                ON CONCAT('U',LPAD(u.id,8,'0'))=s.user_no
               AND u.is_deleted=0
              LEFT JOIN (
                    SELECT user_no,COUNT(*) AS contribution_count,
                           COUNT(DISTINCT dim_key) AS dimension_count,
                           SUM(CASE WHEN dim_key IN ('multiAccount','arbitrage','kycStatus',
                               'withdrawVelocity','accountAge','anomalyBehavior') THEN 1 ELSE 0 END) AS canonical_count,
                           COALESCE(SUM(points),0) AS point_total
                      FROM nx_admin_risk_score_contribution
                     WHERE is_deleted=0
                     GROUP BY user_no
              ) c ON c.user_no=s.user_no
             WHERE s.is_deleted=0
               AND (COALESCE(s.model_version,'')<>CONCAT('k4-v',#{modelVersion})
                    OR COALESCE(c.contribution_count,0)<>6
                    OR COALESCE(c.dimension_count,0)<>6
                    OR COALESCE(c.canonical_count,0)<>6
                    OR s.model_score<>COALESCE(c.point_total,0)
                    OR u.updated_at > COALESCE(s.as_of,'1970-01-01')
                    OR EXISTS (SELECT 1 FROM nx_admin_risk_multi_account_cluster k1
                                WHERE k1.updated_at > COALESCE(s.as_of,'1970-01-01')
                                  AND k1.nodes_json IS NOT NULL AND JSON_VALID(k1.nodes_json)=1
                                  AND JSON_SEARCH(k1.nodes_json,'one',s.user_no) IS NOT NULL)
                    OR EXISTS (SELECT 1 FROM nx_admin_risk_arbitrage_row k2
                                WHERE k2.updated_at > COALESCE(s.as_of,'1970-01-01')
                                  AND CONCAT_WS('|',k2.cell1,k2.cell2,k2.cell3,k2.cell4,k2.cell5,k2.cell6)
                                      LIKE CONCAT('%',s.user_no,'%'))
                    OR EXISTS (SELECT 1 FROM nx_kyc_profile c4
                                WHERE c4.user_id=u.id
                                  AND c4.updated_at > COALESCE(s.as_of,'1970-01-01'))
                    OR EXISTS (SELECT 1 FROM nx_withdrawal_order withdraw_fact
                                WHERE withdraw_fact.user_id=u.id
                                  AND withdraw_fact.updated_at > COALESCE(s.as_of,'1970-01-01'))
                    OR EXISTS (SELECT 1 FROM nx_risk_signal j3
                                WHERE j3.user_id=u.id
                                  AND j3.updated_at > COALESCE(s.as_of,'1970-01-01'))
                    OR s.as_of < NOW() - INTERVAL 1 DAY)
            """)
    long countScoreUsersNeedingProjection(@Param("modelVersion") long modelVersion);

    @Select("""
            SELECT CONCAT('U',LPAD(u.id,8,'0')) AS userNo,
                   COALESCE((SELECT MAX(c.account_count)
                               FROM nx_admin_risk_multi_account_cluster c
                              WHERE c.is_deleted=0 AND c.status IN ('detected','flagged','frozen')
                                AND c.nodes_json IS NOT NULL AND JSON_VALID(c.nodes_json)=1
                                AND JSON_SEARCH(c.nodes_json,'one',CONCAT('U',LPAD(u.id,8,'0'))) IS NOT NULL),0)
                     AS multiAccountClusterSize,
                   EXISTS(SELECT 1 FROM nx_admin_risk_multi_account_cluster c
                           WHERE c.is_deleted=0 AND c.status='frozen'
                             AND c.nodes_json IS NOT NULL AND JSON_VALID(c.nodes_json)=1
                             AND JSON_SEARCH(c.nodes_json,'one',CONCAT('U',LPAD(u.id,8,'0'))) IS NOT NULL)
                     AS multiAccountFraud,
                   (SELECT COUNT(*) FROM nx_admin_risk_arbitrage_row a
                     WHERE a.is_deleted=0 AND CONCAT_WS('|',a.cell1,a.cell2,a.cell3,a.cell4,a.cell5,a.cell6)
                       LIKE CONCAT('%',CONCAT('U',LPAD(u.id,8,'0')),'%')) AS arbitrageSignals,
                   EXISTS(SELECT 1 FROM nx_admin_risk_arbitrage_row a
                           WHERE a.is_deleted=0 AND a.disposition IN ('cluster_frozen','gift_blocked','account_flagged')
                             AND CONCAT_WS('|',a.cell1,a.cell2,a.cell3,a.cell4,a.cell5,a.cell6)
                               LIKE CONCAT('%',CONCAT('U',LPAD(u.id,8,'0')),'%')) AS severeArbitrage,
                   COALESCE(kyc.status,'PENDING') AS kycStatus,
                   (SELECT COUNT(*) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0 AND w.created_at>=NOW()-INTERVAL 24 HOUR)
                     AS withdrawalCount24h,
                   (SELECT COALESCE(SUM(w.amount),0) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0 AND w.created_at>=NOW()-INTERVAL 24 HOUR)
                     AS withdrawalAmount24h,
                   (SELECT COUNT(*) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0 AND w.created_at>=NOW()-INTERVAL 7 DAY)
                     AS withdrawalCount7d,
                   (SELECT COALESCE(SUM(w.amount),0) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0 AND w.created_at>=NOW()-INTERVAL 7 DAY)
                     AS withdrawalAmount7d,
                   (SELECT COUNT(*) / 30.0 FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0
                       AND w.created_at>=NOW()-INTERVAL 37 DAY AND w.created_at<NOW()-INTERVAL 7 DAY)
                     AS withdrawalBaselineDailyCount,
                   (SELECT COALESCE(SUM(w.amount),0) / 30.0 FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0
                       AND w.created_at>=NOW()-INTERVAL 37 DAY AND w.created_at<NOW()-INTERVAL 7 DAY)
                     AS withdrawalBaselineDailyAmount,
                   (SELECT COALESCE(MAX(w.amount),0) FROM nx_withdrawal_order w
                     WHERE w.user_id=u.id AND w.is_deleted=0 AND w.created_at>=NOW()-INTERVAL 24 HOUR)
                     AS maxWithdrawal24h,
                   GREATEST(0,TIMESTAMPDIFF(DAY,u.created_at,NOW())) AS accountAgeDays,
                   (SELECT COUNT(*) FROM nx_risk_signal s
                     WHERE s.user_id=u.id AND s.is_deleted=0 AND s.created_at>=NOW()-INTERVAL 30 DAY)
                     AS anomalySignals,
                   EXISTS(SELECT 1 FROM nx_risk_signal s
                           WHERE s.user_id=u.id AND s.is_deleted=0 AND s.signal_type='TAMPER_DETECTED'
                             AND s.created_at>=NOW()-INTERVAL 30 DAY) AS tamperDetected
              FROM nx_user u
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=u.id AND kyc.is_deleted=0
             WHERE CONCAT('U',LPAD(u.id,8,'0'))=#{userNo} AND u.is_deleted=0
             LIMIT 1
            """)
    ScoreRawInputRecord scoreRawInput(@Param("userNo") String userNo);

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
            SELECT COALESCE(dim_key,name) AS dimKey,name,hit,evidence,sub_score AS subScore,
                   weight_pct AS weightPct,points
              FROM nx_admin_risk_score_contribution
             WHERE user_no = #{userNo} AND is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<ScoreContributionRecord> scoreContributions(@Param("userNo") String userNo);

    @Update("UPDATE nx_admin_risk_score_contribution SET is_deleted=1 WHERE user_no=#{userNo} AND is_deleted=0")
    int retireScoreContributions(@Param("userNo") String userNo);

    @Insert("""
            INSERT INTO nx_admin_risk_score_contribution
              (user_no,model_version,dim_key,name,hit,evidence,sub_score,weight_pct,points,sort_order,is_deleted)
            VALUES(#{userNo},#{modelVersion},#{dimKey},#{name},#{hit},#{evidence},#{subScore},#{weightPct},#{points},#{sortOrder},0)
            """)
    int insertCanonicalScoreContribution(
            @Param("userNo") String userNo,@Param("modelVersion") long modelVersion,
            @Param("dimKey") String dimKey,@Param("name") String name,
            @Param("hit") boolean hit,@Param("evidence") String evidence,@Param("subScore") int subScore,
            @Param("weightPct") int weightPct,@Param("points") int points,@Param("sortOrder") int sortOrder);

    @Insert("""
            INSERT INTO nx_admin_risk_score_history
              (user_no,model_version,model_score,effective_score,score_state,contributions_json,reason,operator,is_deleted)
            VALUES(#{userNo},#{modelVersion},#{modelScore},#{effectiveScore},#{scoreState},
                   #{contributionsJson},#{reason},#{operator},0)
            """)
    int insertScoreHistory(
            @Param("userNo") String userNo,@Param("modelVersion") long modelVersion,
            @Param("modelScore") int modelScore,@Param("effectiveScore") int effectiveScore,
            @Param("scoreState") String scoreState,@Param("contributionsJson") String contributionsJson,
            @Param("reason") String reason,@Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_score_history WHERE user_no=#{userNo} AND is_deleted=0")
    long countScoreHistory(@Param("userNo") String userNo);

    @Select("""
            SELECT model_version AS modelVersion,model_score AS modelScore,effective_score AS effectiveScore,
                   score_state AS scoreState,contributions_json AS contributionsJson,reason,operator,
                   DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS createdAt
              FROM nx_admin_risk_score_history
             WHERE user_no=#{userNo} AND is_deleted=0
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<ScoreHistoryRecord> scoreHistory(@Param("userNo") String userNo, @Param("limit") int limit);

    @Update("""
            UPDATE nx_admin_risk_score_user
               SET model_score=#{modelScore},model_version=#{modelVersion},as_of=NOW(),updated_text='刚刚',
                   row_version=row_version+1,updated_at=NOW()
             WHERE user_no=#{userNo} AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int updateScoreUserModelIfVersion(
            @Param("userNo") String userNo,@Param("expectedVersion") long expectedVersion,
            @Param("modelScore") int modelScore,@Param("modelVersion") String modelVersion);

    /**
     * Advances K4's source watermark to the newest fact it actually observed.
     * This prevents a producer/session clock offset from selecting the same row forever,
     * while keeping soft-delete timestamps visible to the next recompute.
     */
    @Update("""
            UPDATE nx_admin_risk_score_user s
              JOIN nx_user u ON CONCAT('U',LPAD(u.id,8,'0'))=s.user_no
               SET s.as_of=GREATEST(COALESCE(s.as_of,'1970-01-01'),NOW(),
                   COALESCE(u.updated_at,'1970-01-01'),
                   COALESCE((SELECT MAX(k1.updated_at)
                               FROM nx_admin_risk_multi_account_cluster k1
                              WHERE k1.nodes_json IS NOT NULL AND JSON_VALID(k1.nodes_json)=1
                                AND JSON_SEARCH(k1.nodes_json,'one',s.user_no) IS NOT NULL),'1970-01-01'),
                   COALESCE((SELECT MAX(k2.updated_at)
                               FROM nx_admin_risk_arbitrage_row k2
                              WHERE CONCAT_WS('|',k2.cell1,k2.cell2,k2.cell3,k2.cell4,k2.cell5,k2.cell6)
                                    LIKE CONCAT('%',s.user_no,'%')),'1970-01-01'),
                   COALESCE((SELECT MAX(c4.updated_at) FROM nx_kyc_profile c4
                              WHERE c4.user_id=u.id),'1970-01-01'),
                   COALESCE((SELECT MAX(withdraw_fact.updated_at) FROM nx_withdrawal_order withdraw_fact
                              WHERE withdraw_fact.user_id=u.id),'1970-01-01'),
                   COALESCE((SELECT MAX(j3.updated_at) FROM nx_risk_signal j3
                              WHERE j3.user_id=u.id),'1970-01-01'))
             WHERE s.user_no=#{userNo} AND s.is_deleted=0
            """)
    int advanceScoreAsOfToLatestSource(@Param("userNo") String userNo);

    @Update("""
            UPDATE nx_admin_risk_score_user
               SET row_version=row_version+1,as_of=NOW(),updated_text='刚刚',updated_at=NOW()
             WHERE user_no=#{userNo} AND row_version=#{expectedVersion} AND is_deleted=0
            """)
    int bumpScoreUserVersion(@Param("userNo") String userNo,@Param("expectedVersion") long expectedVersion);

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
            SELECT param_key AS `key`,name,value_text AS value,unit_text AS unit,sub_text AS sub,note_text AS note,
                   version, TRUE AS adjustable
              FROM nx_admin_risk_param
             WHERE section_key = #{section} AND is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<RiskParamRecord> riskParams(@Param("section") String section);

    @Select("SELECT value_text FROM nx_admin_risk_param WHERE section_key=#{section} AND param_key=#{key} AND is_deleted=0 LIMIT 1")
    String riskParamValue(@Param("section") String section, @Param("key") String key);

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

    @Update("""
            UPDATE nx_admin_risk_param
               SET value_text = #{value}, version = version + 1, updated_at = NOW()
             WHERE section_key = 'k5' AND param_key = #{key} AND version = #{expectedVersion} AND is_deleted = 0
            """)
    int updateK5RiskParam(@Param("key") String key, @Param("value") String value,
                          @Param("expectedVersion") long expectedVersion);

    @Update("""
            WITH RECURSIVE business_calendar AS (
              SELECT id,created_at,created_at AS candidate,0 AS working_days
                FROM nx_admin_risk_kyc_review_ticket
               WHERE status IN ('triggered','in-review') AND is_deleted=0
              UNION ALL
              SELECT id,created_at,DATE_ADD(candidate,INTERVAL 1 DAY),
                     working_days + CASE WHEN WEEKDAY(DATE_ADD(candidate,INTERVAL 1 DAY)) < 5 THEN 1 ELSE 0 END
                FROM business_calendar
               WHERE working_days < #{workingDays}
            )
            UPDATE nx_admin_risk_kyc_review_ticket t
            JOIN (SELECT id,MIN(candidate) AS due_at FROM business_calendar
                   WHERE working_days=#{workingDays} AND WEEKDAY(candidate) < 5 GROUP BY id) d ON d.id=t.id
               SET t.due_at=d.due_at,t.version=t.version+1,t.updated_at=NOW()
             WHERE t.status IN ('triggered','in-review') AND t.is_deleted=0
            """)
    int recomputeOpenKycDueAt(@Param("workingDays") int workingDays);

    @Insert("""
            INSERT INTO nx_admin_risk_param
              (section_key,param_key,name,value_text,unit_text,sub_text,note_text,sort_order,version,is_deleted)
            VALUES ('k5',#{key},#{name},#{value},#{unit},#{sub},#{note},#{sortOrder},0,0)
            ON DUPLICATE KEY UPDATE name=VALUES(name),unit_text=VALUES(unit_text),sub_text=VALUES(sub_text),
              note_text=VALUES(note_text),sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW()
            """)
    int upsertK5RiskParam(@Param("key") String key, @Param("name") String name,
                          @Param("value") String value, @Param("unit") String unit,
                          @Param("sub") String sub, @Param("note") String note,
                          @Param("sortOrder") int sortOrder);

    @Update("""
            UPDATE nx_admin_risk_param SET is_deleted=1,updated_at=NOW()
             WHERE section_key='k5' AND param_key='largeExchangeReviewUsdt'
            """)
    int deactivateLegacyK5RiskParam();

    @Insert("""
            INSERT INTO nx_admin_risk_param (section_key,param_key,name,value_text,unit_text,sub_text,note_text,sort_order,is_deleted)
            VALUES ('k1',#{key},#{name},#{value},#{unit},#{sub},#{note},#{sortOrder},0)
            ON DUPLICATE KEY UPDATE name=VALUES(name),unit_text=VALUES(unit_text),sub_text=VALUES(sub_text),
              note_text=VALUES(note_text),sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW()
            """)
    int upsertK1RiskParam(@Param("key") String key, @Param("name") String name,
                          @Param("value") String value, @Param("unit") String unit,
                          @Param("sub") String sub, @Param("note") String note,
                          @Param("sortOrder") int sortOrder);

    @Update("""
            UPDATE nx_admin_risk_param SET is_deleted=1,updated_at=NOW()
             WHERE section_key='k1' AND param_key IN ('sameDeviceThreshold','ipOverlapThreshold','clusterStrengthThreshold','autoFreezeHighCluster')
            """)
    int deactivateLegacyK1RiskParams();

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
                   gifts_json AS giftsJson,nodes_json AS nodesJson,edges_json AS edgesJson,
                   review_note AS reviewNote,version
              FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted = 0
             ORDER BY strength DESC, id ASC
            """)
    List<MultiAccountClusterRecord> multiAccountClusters();

    @Select("""
            <script>
            SELECT cluster_id AS id,dedupe_key AS `key`,layer_key AS layer,layer_label AS layerLabel,
                   account_count AS n,strength,span_text AS span,status,note_text AS note,
                   gifts_json AS giftsJson,nodes_json AS nodesJson,edges_json AS edgesJson,
                   review_note AS reviewNote,version
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

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted=0
             <if test="layer != null and layer != ''">AND layer_key=#{layer}</if>
             <if test="status != null and status != ''">AND status=#{status}</if>
            </script>
            """)
    long countMultiAccountClustersByFilter(@Param("layer") String layer, @Param("status") String status);

    @Select("""
            <script>
            SELECT cluster_id AS id,dedupe_key AS `key`,layer_key AS layer,layer_label AS layerLabel,
                   account_count AS n,strength,span_text AS span,status,note_text AS note,
                   gifts_json AS giftsJson,nodes_json AS nodesJson,edges_json AS edgesJson,
                   review_note AS reviewNote,version
              FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted=0
             <if test="layer != null and layer != ''">AND layer_key=#{layer}</if>
             <if test="status != null and status != ''">AND status=#{status}</if>
             <choose>
               <when test="sort == 'account_desc'">ORDER BY account_count DESC, strength DESC, id ASC</when>
               <otherwise>ORDER BY strength DESC, account_count DESC, id ASC</otherwise>
             </choose>
             LIMIT #{offset},#{pageSize}
            </script>
            """)
    List<MultiAccountClusterRecord> pageMultiAccountClustersByFilter(
            @Param("layer") String layer, @Param("status") String status, @Param("sort") String sort,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

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

    @Select("""
            SELECT cluster_id AS id,status,layer_key AS layer,strength,nodes_json AS nodesJson,
                   version,projection_fingerprint AS evidenceFingerprint,threshold_hit AS thresholdHit
              FROM nx_admin_risk_multi_account_cluster
             WHERE cluster_id=#{clusterId} AND is_deleted=0 LIMIT 1
            """)
    MultiAccountClusterStateRecord multiAccountCluster(@Param("clusterId") String clusterId);

    @Update("""
            UPDATE nx_admin_risk_multi_account_cluster
               SET status=#{status},review_note=#{reason},updated_by=#{operator},version=version+1,updated_at=NOW()
             WHERE cluster_id=#{clusterId} AND status=#{expectedStatus} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateMultiAccountClusterStatusIfVersion(
            @Param("clusterId") String clusterId, @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion, @Param("status") String status,
            @Param("reason") String reason, @Param("operator") String operator);

    @Update("""
            UPDATE nx_admin_risk_multi_account_cluster
               SET review_note=#{reason},updated_by=#{operator},version=version+1,updated_at=NOW()
             WHERE cluster_id=#{clusterId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateMultiAccountClusterReviewNoteIfVersion(
            @Param("clusterId") String clusterId, @Param("expectedVersion") long expectedVersion,
            @Param("reason") String reason, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_admin_risk_multi_account_cluster
              (cluster_id,dedupe_key,layer_key,layer_label,account_count,strength,span_text,status,note_text,
               gifts_json,nodes_json,edges_json,projection_fingerprint,threshold_hit,review_note,version,is_deleted)
            VALUES (#{id},#{key},#{layer},#{layerLabel},#{n},#{strength},#{span},'detected',#{note},
                    #{giftsJson},#{nodesJson},#{edgesJson},#{evidenceFingerprint},#{thresholdHit},NULL,0,0)
            ON DUPLICATE KEY UPDATE version=version+IF(
                NOT(dedupe_key <=> VALUES(dedupe_key)) OR NOT(layer_key <=> VALUES(layer_key))
                OR NOT(layer_label <=> VALUES(layer_label)) OR NOT(account_count <=> VALUES(account_count))
                OR NOT(strength <=> VALUES(strength)) OR NOT(span_text <=> VALUES(span_text))
                OR NOT(note_text <=> VALUES(note_text)) OR NOT(gifts_json <=> VALUES(gifts_json))
                OR NOT(nodes_json <=> VALUES(nodes_json)) OR NOT(edges_json <=> VALUES(edges_json))
                OR NOT(projection_fingerprint <=> VALUES(projection_fingerprint))
                OR NOT(threshold_hit <=> VALUES(threshold_hit)),1,0),
              dedupe_key=VALUES(dedupe_key),layer_key=VALUES(layer_key),
              layer_label=VALUES(layer_label),account_count=VALUES(account_count),strength=VALUES(strength),
              span_text=VALUES(span_text),note_text=VALUES(note_text),gifts_json=VALUES(gifts_json),
              nodes_json=VALUES(nodes_json),edges_json=VALUES(edges_json),
              projection_fingerprint=VALUES(projection_fingerprint),threshold_hit=VALUES(threshold_hit),
              is_deleted=0,updated_at=NOW()
            """)
    int upsertMultiAccountClusterProjection(
            @Param("id") String id,@Param("key") String key,@Param("layer") String layer,
            @Param("layerLabel") String layerLabel,@Param("n") int n,@Param("strength") double strength,
            @Param("span") String span,@Param("note") String note,@Param("evidenceFingerprint") String evidenceFingerprint,
            @Param("thresholdHit") boolean thresholdHit,
            @Param("giftsJson") String giftsJson,
            @Param("nodesJson") String nodesJson,@Param("edgesJson") String edgesJson);

    @Update("""
            <script>
            UPDATE nx_admin_risk_multi_account_cluster SET is_deleted=1,updated_at=NOW()
             WHERE is_deleted=0 AND status='detected'
             <if test="ids != null and ids.size() > 0">
               AND cluster_id NOT IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
             </if>
            </script>
            """)
    int retireMissingDetectedClusters(@Param("ids") java.util.Set<String> ids);

    @Update("""
            <script>
            UPDATE nx_admin_risk_multi_account_cluster
               SET status='cleared',review_note='IP 白名单自动清除',updated_by='K1_CLUSTER_SCHEDULER',
                   version=version+1,updated_at=NOW()
             WHERE is_deleted=0 AND status='detected'
             <choose>
               <when test="ids != null and ids.size() > 0">
                 AND cluster_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
               </when>
               <otherwise>AND 1=0</otherwise>
             </choose>
            </script>
            """)
    int clearWhitelistedDetectedClusters(@Param("ids") java.util.Set<String> ids);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_ip_whitelist WHERE is_deleted = 0")
    long countIpWhitelist();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_ip_whitelist WHERE is_deleted = 0 AND active = 1 AND STR_TO_DATE(expire_text,'%Y-%m-%d') >= CURRENT_DATE")
    long countActiveIpWhitelist();

    @Select("""
            SELECT cidr,note_text AS note,operator,expire_text AS expireText,active
              FROM nx_admin_risk_ip_whitelist
             WHERE is_deleted = 0 AND active = 1 AND STR_TO_DATE(expire_text,'%Y-%m-%d') >= CURRENT_DATE
             ORDER BY id ASC
            """)
    List<IpWhitelistRecord> ipWhitelist();

    @Select("""
            SELECT cidr,note_text AS note,operator,expire_text AS expireText,active
              FROM nx_admin_risk_ip_whitelist
             WHERE is_deleted = 0 AND active = 1 AND STR_TO_DATE(expire_text,'%Y-%m-%d') >= CURRENT_DATE
             ORDER BY id ASC
             LIMIT #{offset}, #{pageSize}
            """)
    List<IpWhitelistRecord> pageIpWhitelist(@Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select("""
            SELECT cidr,note_text AS note,operator,expire_text AS expireText,active
              FROM nx_admin_risk_ip_whitelist
             WHERE cidr=#{cidr} AND is_deleted=0
             LIMIT 1
            """)
    IpWhitelistRecord ipWhitelistState(@Param("cidr") String cidr);

    @Select("SELECT cidr FROM nx_admin_risk_ip_whitelist WHERE is_deleted=0 AND active=1 AND STR_TO_DATE(expire_text,'%Y-%m-%d') >= CURRENT_DATE")
    java.util.Set<String> activeIpWhitelistCidrs();

    @Select("""
            SELECT u.id AS userId,CONCAT('U',LPAD(u.id,8,'0')) AS userNo,u.created_at AS joinedAt,
                   NULL AS sponsorUserId,NULL AS gotWelcomeGift,NULL AS depositCumulativeUsdt,
                   COALESCE(u.status,'ACTIVE') AS accountStatus,
                   'device' AS layer,d.device_fingerprint AS rawKey,
                   CONCAT(LEFT(d.device_fingerprint,6),'***') AS maskedKey
              FROM nx_user u JOIN nx_risk_decision d ON d.user_id=u.id AND d.is_deleted=0
             WHERE u.is_deleted=0 AND d.device_fingerprint IS NOT NULL AND d.device_fingerprint<>''
            """)
    List<MultiAccountSignalFactRecord> multiAccountSignalFacts();

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
               AND status = 'in-review'
            """)
    long countKycOpenTickets();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_kyc_review_ticket WHERE is_deleted=0 AND status='in-review' AND due_at IS NOT NULL AND due_at < NOW()")
    long countOverdueKycTickets();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_kyc_review_ticket WHERE is_deleted=0 AND status IN ('passed','rejected') AND reviewed_at >= DATE_FORMAT(NOW(),'%Y-%m-01')")
    long countKycDecidedThisMonth();

    @Select("SELECT COUNT(*) FROM nx_admin_risk_kyc_review_ticket WHERE is_deleted=0 AND status='passed' AND reviewed_at >= DATE_FORMAT(NOW(),'%Y-%m-01')")
    long countKycPassedThisMonth();

    @Select("SELECT COALESCE(SUM(amount),0) FROM nx_withdrawal_order WHERE is_deleted=0 AND status='FROZEN' AND failure_reason LIKE 'K5_REVIEW:%'")
    java.math.BigDecimal sumFrozenWithdrawalUsdt();

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_kyc_review_ticket
             WHERE user_no = #{userNo}
               AND is_deleted = 0
               AND status = 'in-review'
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
                 <when test='filter == "overdue"'>AND status = 'in-review' AND due_at IS NOT NULL AND due_at &lt; NOW()</when>
                 <otherwise>AND ticket_type = #{filter}</otherwise>
               </choose>
             </if>
            </script>
            """)
    long countKycTicketsByFilter(@Param("filter") String filter);

    @Select("""
            <script>
            SELECT t.ticket_id AS id,t.ticket_type AS type,t.user_no AS user,t.amount_text AS amt,t.cumulative_text AS cum,
                   CASE WHEN u.id IS NULL THEN 'USER_UNAVAILABLE'
                        ELSE COALESCE(kyc.status,'PENDING') END AS kyc,
                   CASE WHEN t.status='in-review' AND t.due_at IS NOT NULL AND t.due_at &lt; NOW() THEN 'overdue' ELSE t.status END AS st,
                   CASE WHEN t.due_at IS NULL THEN 0 ELSE LEAST(1,GREATEST(0,TIMESTAMPDIFF(SECOND,t.created_at,NOW())/NULLIF(TIMESTAMPDIFF(SECOND,t.created_at,t.due_at),0))) END AS slaPct,
                   CASE WHEN t.status IN ('passed','rejected') THEN '已完成'
                        WHEN t.due_at IS NULL THEN '未设置'
                        WHEN t.due_at &lt; NOW() THEN CONCAT('逾期 ',TIMESTAMPDIFF(DAY,t.due_at,NOW()),' 天')
                        ELSE CONCAT('剩 ',GREATEST(0,TIMESTAMPDIFF(DAY,NOW(),t.due_at)),' 天') END AS slaTxt,
                   t.info_json AS infoJson,t.history_json AS histJson,t.version
              FROM nx_admin_risk_kyc_review_ticket t
              LEFT JOIN nx_user u ON CONCAT('U',LPAD(u.id,8,'0'))=t.user_no AND u.is_deleted=0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=u.id AND kyc.is_deleted=0
             WHERE t.is_deleted = 0
             <if test='filter != null and filter != ""'>
               <choose>
                 <when test='filter == "overdue"'>AND t.status = 'in-review' AND t.due_at IS NOT NULL AND t.due_at &lt; NOW()</when>
                 <otherwise>AND t.ticket_type = #{filter}</otherwise>
               </choose>
             </if>
             ORDER BY CASE WHEN t.status='in-review' AND t.due_at IS NOT NULL AND t.due_at &lt; NOW() THEN 0
                           WHEN t.status='in-review' THEN 1 WHEN t.status='passed' THEN 2 ELSE 3 END, t.id ASC
             LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<KycReviewTicketRecord> pageKycReviewTickets(@Param("filter") String filter, @Param("offset") int offset,
                                                     @Param("pageSize") int pageSize);

    @Select("""
            SELECT t.ticket_id AS id,t.ticket_type AS type,t.user_no AS user,t.amount_text AS amt,t.cumulative_text AS cum,
                   CASE WHEN u.id IS NULL THEN 'USER_UNAVAILABLE'
                        ELSE COALESCE(kyc.status,'PENDING') END AS kyc,
                   t.status AS st,t.sla_pct AS slaPct,t.sla_text AS slaTxt,
                   t.info_json AS infoJson,t.history_json AS histJson,t.version
              FROM nx_admin_risk_kyc_review_ticket t
              LEFT JOIN nx_user u ON CONCAT('U',LPAD(u.id,8,'0'))=t.user_no AND u.is_deleted=0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=u.id AND kyc.is_deleted=0
             WHERE t.ticket_id = #{ticketId} AND t.is_deleted = 0
             LIMIT 1
            """)
    KycReviewTicketRecord findKycReviewTicket(@Param("ticketId") String ticketId);

    @Select("""
            SELECT t.ticket_id AS id,t.ticket_type AS type,t.user_no AS user,t.amount_text AS amt,t.cumulative_text AS cum,
                   CASE WHEN u.id IS NULL THEN 'USER_UNAVAILABLE'
                        ELSE COALESCE(kyc.status,'PENDING') END AS kyc,
                   t.status AS st,t.sla_pct AS slaPct,t.sla_text AS slaTxt,
                   t.info_json AS infoJson,t.history_json AS histJson,t.version
              FROM nx_admin_risk_kyc_review_ticket t
              LEFT JOIN nx_user u ON CONCAT('U',LPAD(u.id,8,'0'))=t.user_no AND u.is_deleted=0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=u.id AND kyc.is_deleted=0
             WHERE t.user_no=#{userNo} AND t.status='in-review' AND t.is_deleted=0
             ORDER BY t.id DESC LIMIT 1
            """)
    KycReviewTicketRecord findOpenKycReviewTicketByUser(@Param("userNo") String userNo);

    @Select("""
            SELECT t.ticket_id AS id,t.ticket_type AS type,t.user_no AS user,t.amount_text AS amt,t.cumulative_text AS cum,
                   CASE WHEN u.id IS NULL THEN 'USER_UNAVAILABLE'
                        ELSE COALESCE(kyc.status,'PENDING') END AS kyc,
                   t.status AS st,t.sla_pct AS slaPct,t.sla_text AS slaTxt,
                   t.info_json AS infoJson,t.history_json AS histJson,t.version
              FROM nx_admin_risk_kyc_review_ticket t
              LEFT JOIN nx_user u ON CONCAT('U',LPAD(u.id,8,'0'))=t.user_no AND u.is_deleted=0
              LEFT JOIN nx_kyc_profile kyc ON kyc.user_id=u.id AND kyc.is_deleted=0
             WHERE t.user_no=#{userNo} AND t.status='in-review' AND t.is_deleted=0
             ORDER BY t.id DESC LIMIT 1 FOR UPDATE
            """)
    KycReviewTicketRecord findOpenKycReviewTicketByUserForUpdate(@Param("userNo") String userNo);

    @Insert("""
            INSERT INTO nx_admin_risk_kyc_review_ticket (
                ticket_id,ticket_type,user_no,amount_text,amount_usdt,cumulative_text,kyc_text,status,sla_pct,sla_text,
                info_json,history_json,due_at,version,is_deleted
            ) VALUES (
                #{id},#{type},#{user},#{amt},#{amountUsdt},#{cum},#{kyc},#{st},#{slaPct},#{slaTxt},#{infoJson},#{histJson},
                #{dueAt},0,0
            )
            """)
    int insertKycReviewTicket(@Param("id") String id, @Param("type") String type, @Param("user") String user,
                              @Param("amt") String amt, @Param("amountUsdt") java.math.BigDecimal amountUsdt,
                              @Param("cum") String cum, @Param("kyc") String kyc,
                              @Param("st") String st, @Param("slaPct") double slaPct, @Param("slaTxt") String slaTxt,
                              @Param("infoJson") String infoJson, @Param("histJson") String histJson,
                              @Param("dueAt") java.time.LocalDateTime dueAt);

    @Update("""
            UPDATE nx_admin_risk_kyc_review_ticket
               SET status = #{status},
                   history_json = JSON_ARRAY_APPEND(COALESCE(history_json,JSON_ARRAY()), '$',
                     JSON_ARRAY(DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'),
                       CONCAT(CASE WHEN #{status}='passed' THEN '复审通过' ELSE '复审驳回' END,
                         '·',#{reasonCode},'·',#{reason},'·操作人:',#{operator}),
                       CASE WHEN #{status}='passed' THEN '' ELSE 'bad' END)),
                   decision_reason = CONCAT(#{reasonCode},':',#{reason}), reviewed_by = #{operator},
                   reviewed_at = NOW(), version = version + 1, updated_at = NOW()
             WHERE ticket_id = #{ticketId} AND status='in-review' AND version=#{expectedVersion} AND is_deleted = 0
            """)
    int updateKycReviewTicketStatus(@Param("ticketId") String ticketId, @Param("status") String status,
                                    @Param("expectedVersion") long expectedVersion,
                                    @Param("reasonCode") String reasonCode,
                                    @Param("reason") String reason, @Param("operator") String operator);

    @Update("""
            UPDATE nx_admin_risk_kyc_review_ticket
               SET history_json = JSON_ARRAY_APPEND(COALESCE(history_json,JSON_ARRAY()), '$',
                   JSON_ARRAY(DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'),
                     CONCAT('并入重复信号·',#{reason},'·操作人:',#{operator}),'warn')),
                   info_json = JSON_ARRAY_APPEND(COALESCE(info_json,JSON_ARRAY()), '$',
                     JSON_ARRAY('触发原因',#{reason})),
                   version=version+1,updated_at=NOW()
             WHERE ticket_id=#{ticketId} AND status='in-review' AND version=#{expectedVersion} AND is_deleted=0
            """)
    int mergeOpenKycReviewTicket(@Param("ticketId") String ticketId, @Param("expectedVersion") long expectedVersion,
                                 @Param("reason") String reason, @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM nx_admin_risk_kyc_alert WHERE is_deleted = 0")
    long countKycAlerts();

    @Select("""
            <script>
            SELECT event_key AS eventKey,tone,title,body,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i') AS timeText
              FROM nx_admin_risk_kyc_alert
             WHERE is_deleted = 0
             <choose>
               <when test='types != null and types.size() > 0'>
                 AND (<foreach collection='types' item='type' separator=' OR '>event_key LIKE CONCAT(#{type},':%')</foreach>)
               </when>
               <otherwise>AND 1=0</otherwise>
             </choose>
             ORDER BY created_at DESC,id DESC
             LIMIT 100
            </script>
            """)
    List<KycAlertRecord> kycAlerts(@Param("types") List<String> types);

    @Insert("INSERT IGNORE INTO nx_admin_risk_kyc_alert (event_key,tone,title,body,time_text,is_deleted) VALUES (#{eventKey},#{tone},#{title},#{body},#{timeText},0)")
    int insertKycAlert(@Param("eventKey") String eventKey, @Param("tone") String tone,
                       @Param("title") String title, @Param("body") String body,
                       @Param("timeText") String timeText);

    @Insert("""
            INSERT IGNORE INTO nx_admin_risk_kyc_alert(event_key,tone,title,body,time_text,is_deleted)
            SELECT CONCAT('sla-breach:',ticket_id),'bad',CONCAT('KYC 复审 SLA 逾期 · ',ticket_id),
                   CONCAT(user_no,' · ',ticket_type,' · 截止 ',DATE_FORMAT(due_at,'%Y-%m-%d %H:%i')),
                   DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i'),0
              FROM nx_admin_risk_kyc_review_ticket
             WHERE status='in-review' AND due_at IS NOT NULL AND due_at<NOW() AND is_deleted=0
            """)
    int insertOverdueKycAlerts();

    @Insert("""
            INSERT IGNORE INTO nx_admin_risk_kyc_alert(event_key,tone,title,body,time_text,is_deleted)
            SELECT CONCAT('large-withdraw-burst:',DATE_FORMAT(NOW(),'%Y%m%d%H%i')),'bad',
                   '大额提现集中触发 KYC 复审',
                   CONCAT('最近 1 小时共有 ',COUNT(*),' 笔大额提现进入 K5 复审'),
                   DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i'),0
              FROM nx_admin_risk_kyc_review_source s
             WHERE s.source_domain='D2' AND s.is_deleted=0
               AND s.created_at>=DATE_SUB(NOW(),INTERVAL 1 HOUR)
               AND NOT EXISTS (
                   SELECT 1 FROM nx_admin_risk_kyc_alert a
                    WHERE a.is_deleted=0
                      AND a.event_key LIKE 'large-withdraw-burst:%'
                      AND a.created_at>=DATE_SUB(NOW(),INTERVAL 1 HOUR)
               )
            HAVING COUNT(*) >= 5
            """)
    int insertLargeWithdrawalBurstKycAlert();

    @Insert("""
            INSERT INTO nx_admin_risk_kyc_alert_subscription(operator_name,alert_types_json,channels_json,version)
            VALUES (#{operator},'[\"sla-breach\"]','[\"in-app\"]',0)
            ON DUPLICATE KEY UPDATE operator_name=operator_name
            """)
    int ensureKycAlertSubscription(@Param("operator") String operator);

    @Insert("INSERT INTO nx_admin_risk_kyc_alert_subscription(operator_name,alert_types_json,channels_json,version) VALUES (#{operator},#{alertTypesJson},#{channelsJson},1)")
    int insertKycAlertSubscription(@Param("operator") String operator,
                                   @Param("alertTypesJson") String alertTypesJson,
                                   @Param("channelsJson") String channelsJson);

    @Select("SELECT operator_name AS operatorName,alert_types_json AS alertTypesJson,channels_json AS channelsJson,version FROM nx_admin_risk_kyc_alert_subscription WHERE operator_name=#{operator}")
    KycAlertSubscriptionRecord findKycAlertSubscription(@Param("operator") String operator);

    @Update("""
            UPDATE nx_admin_risk_kyc_alert_subscription
               SET alert_types_json=#{alertTypesJson},channels_json=#{channelsJson},version=version+1,updated_at=NOW()
             WHERE operator_name=#{operator} AND version=#{expectedVersion}
            """)
    int updateKycAlertSubscription(@Param("operator") String operator,
                                   @Param("alertTypesJson") String alertTypesJson,
                                   @Param("channelsJson") String channelsJson,
                                   @Param("expectedVersion") long expectedVersion);

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
            String disposition,
            Long version,
            String clusterStatus,
            Long clusterVersion
    ) {
    }

    record ScoreConfigRecord(String configKey, String valueText) {
    }

    record ScoreModelRecord(
            Long modelVersion, Long rowVersion, String state, String weightsJson, String inputSourcesJson,
            String scoreMappingJson,
            Integer bandLowMax, Integer bandHighMin, Integer autoEscalateScore, String reason,
            String createdBy, String publishedBy, String createdAt, String publishedAt) {
    }

    record ScoreDistributionRecord(String band, String rangeText, Long count, String color, String tone) {
    }

    record ScoreDistributionCountRecord(Long lowCount, Long midCount, Long highCount) {
    }

    record ScoreUserRecord(
            String userNo, Integer modelScore, String modelVersion, Long rowVersion, String asOf, String updatedText) {
    }

    record K4KycTriggerStateRecord(
            String userNo, Boolean aboveThreshold, Integer lastScore, Integer lastThreshold,
            String lastTransitionId, Long triggerSequence, Long version) {
    }

    record ScoreUserSearchRecord(String userNo, Integer modelScore, String modelVersion, String updatedText,
                                 String nickname, String phoneMasked, String referralCode) {
    }

    record ScoreContributionRecord(
            String dimKey, String name, Boolean hit, String evidence,
            Integer subScore, Integer weightPct, Integer points) {
    }

    record ScoreHistoryRecord(
            Long modelVersion, Integer modelScore, Integer effectiveScore, String scoreState,
            String contributionsJson, String reason, String operator, String createdAt) {
    }

    record ScoreRawInputRecord(
            String userNo, Integer multiAccountClusterSize, Boolean multiAccountFraud,
            Integer arbitrageSignals, Boolean severeArbitrage, String kycStatus,
            Integer withdrawalCount24h, java.math.BigDecimal withdrawalAmount24h,
            Integer withdrawalCount7d, java.math.BigDecimal withdrawalAmount7d,
            java.math.BigDecimal withdrawalBaselineDailyCount,
            java.math.BigDecimal withdrawalBaselineDailyAmount,
            java.math.BigDecimal maxWithdrawal24h,
            Integer accountAgeDays, Integer anomalySignals, Boolean tamperDetected) {
    }

    record RiskParamRecord(String key, String name, String value, String unit, String sub, String note,
                           Long version, Boolean adjustable) {
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
            String edgesJson,
            String reviewNote,
            Long version
    ) {
    }

    record TrialCycleDetectionRecord(String rowId, Long userId, String clusterId, Integer cycleCount) {
    }

    record KycAlertSubscriptionRecord(String operatorName, String alertTypesJson, String channelsJson, Long version) {
    }

    record MultiAccountClusterStateRecord(
            String id,
            String status,
            String layer,
            Double strength,
            String nodesJson,
            Long version,
            String evidenceFingerprint,
            Boolean thresholdHit
    ) {
    }

    record MultiAccountSignalFactRecord(Long userId,String userNo,java.time.LocalDateTime joinedAt,
                                        Long sponsorUserId,Boolean gotWelcomeGift,java.math.BigDecimal depositCumulativeUsdt,
                                        String accountStatus,String layer,String rawKey,String maskedKey) {}

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
            String histJson,
            Long version
    ) {
    }

    record KycAlertRecord(String eventKey, String tone, String title, String body, String timeText) {
    }

    record KycReviewSourceRecord(String sourceDomain, String sourceNo) {
    }

    record TamperRadarRecord(Long signalCount, Long accountCount, String latestAt) {
    }
}
