package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.infrastructure.BiReportEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BiReportMapper extends BaseMapper<BiReportEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_fourth_batch_report (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              module_code VARCHAR(16) NOT NULL,
              report_id VARCHAR(64) NOT NULL,
              report_name VARCHAR(128) NOT NULL,
              report_type VARCHAR(64) NOT NULL,
              cycle VARCHAR(32) NOT NULL,
              file_format VARCHAR(16) NOT NULL,
              scope_text VARCHAR(255) NOT NULL,
              field_text VARCHAR(255) NOT NULL,
              row_count BIGINT NOT NULL DEFAULT 0,
              contains_pii TINYINT NOT NULL DEFAULT 0,
              masking_policy VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              note VARCHAR(255) DEFAULT NULL,
              snapshot_csv LONGTEXT DEFAULT NULL,
              download_token_hash CHAR(64) DEFAULT NULL,
              download_token_expires_at DATETIME DEFAULT NULL,
              last_action VARCHAR(32) DEFAULT NULL,
              last_action_at DATETIME DEFAULT NULL,
              reason VARCHAR(255) DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_fourth_report (module_code, report_id),
              KEY idx_fourth_report_module_status (module_code, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createReportTable();

    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_fourth_batch_report' AND COLUMN_NAME = 'snapshot_csv'")
    int countSnapshotCsvColumn();

    @Update("ALTER TABLE nx_admin_fourth_batch_report ADD COLUMN snapshot_csv LONGTEXT NULL AFTER note")
    void addSnapshotCsvColumn();

    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_fourth_batch_report' AND COLUMN_NAME = 'download_token_hash'")
    int countDownloadTokenHashColumn();

    @Update("ALTER TABLE nx_admin_fourth_batch_report ADD COLUMN download_token_hash CHAR(64) NULL AFTER snapshot_csv")
    void addDownloadTokenHashColumn();

    @Select("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_fourth_batch_report' AND COLUMN_NAME = 'download_token_expires_at'")
    int countDownloadTokenExpiresAtColumn();

    @Update("ALTER TABLE nx_admin_fourth_batch_report ADD COLUMN download_token_expires_at DATETIME NULL AFTER download_token_hash")
    void addDownloadTokenExpiresAtColumn();

    @Insert("""
            INSERT INTO nx_admin_fourth_batch_report (
              module_code, report_id, report_name, report_type, cycle, file_format,
              scope_text, field_text, row_count, contains_pii, masking_policy, status, note, last_action_at, is_deleted
            ) VALUES (
              #{moduleCode}, #{reportId}, #{reportName}, #{reportType}, #{cycle}, #{fileFormat},
              #{scopeText}, #{fieldText}, #{rowCount}, #{containsPii}, #{maskingPolicy}, #{status}, #{note}, NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
              report_name = VALUES(report_name),
              report_type = VALUES(report_type),
              cycle = VALUES(cycle),
              file_format = VALUES(file_format),
              scope_text = VALUES(scope_text),
              field_text = VALUES(field_text),
              row_count = VALUES(row_count),
              contains_pii = VALUES(contains_pii),
              masking_policy = VALUES(masking_policy),
              status = IF(last_action IS NULL, VALUES(status), status),
              note = VALUES(note),
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertReportSeed(ReportSeed seed);

    @Update("UPDATE nx_admin_fourth_batch_report SET snapshot_csv = #{snapshotCsv}, updated_at = NOW() WHERE module_code = 'L5' AND report_id = #{reportId} AND is_deleted = 0")
    int updateSnapshotCsv(@Param("reportId") String reportId, @Param("snapshotCsv") String snapshotCsv);

    @Select("SELECT snapshot_csv FROM nx_admin_fourth_batch_report WHERE module_code = 'L5' AND report_id = #{reportId} AND is_deleted = 0 LIMIT 1")
    String findSnapshotCsv(@Param("reportId") String reportId);

    @Update("UPDATE nx_admin_fourth_batch_report SET download_token_hash = #{tokenHash}, download_token_expires_at = #{expiresAt}, updated_at = NOW() WHERE module_code = 'L5' AND report_id = #{reportId} AND status = 'READY' AND is_deleted = 0")
    int updateDownloadToken(@Param("reportId") String reportId, @Param("tokenHash") String tokenHash,
                            @Param("expiresAt") java.time.LocalDateTime expiresAt);

    @Select("SELECT COUNT(*) FROM nx_admin_fourth_batch_report WHERE module_code = 'L5' AND report_id = #{reportId} AND status = 'READY' AND download_token_hash = #{tokenHash} AND download_token_expires_at > #{now} AND is_deleted = 0")
    int countValidDownloadToken(@Param("reportId") String reportId, @Param("tokenHash") String tokenHash,
                                @Param("now") java.time.LocalDateTime now);

    @Select("SELECT COUNT(*) FROM nx_admin_fourth_batch_report WHERE module_code = 'L5' AND is_deleted = 0")
    long countTotalReports();

    @Select("""
            SELECT COUNT(*) FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5' AND contains_pii = 1 AND is_deleted = 0
            """)
    long countSensitiveReports();

    @Select("""
            SELECT COUNT(*) FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5' AND status IN ('PENDING_CONFIRM', 'PENDING_SPLIT_CONFIRM') AND is_deleted = 0
            """)
    long countPendingConfirm();

    @Select("""
            SELECT COUNT(*) FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5' AND status = 'READY'
               AND snapshot_csv IS NOT NULL AND LENGTH(snapshot_csv) > 0
               AND report_type IN ('KPI_SERIES', 'FUNNEL_COHORT', 'FINANCE_AGG', 'OPERATIONS_AGG', 'NETWORK_TREE', 'KYC_REGULATORY', 'REGULATORY')
               AND is_deleted = 0
            """)
    long countReadyReports();

    @Select("""
            SELECT COUNT(*) FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5' AND status = 'READY'
               AND (snapshot_csv IS NULL OR LENGTH(snapshot_csv) = 0)
               AND is_deleted = 0
            """)
    long countReadyReportsWithoutSnapshot();

    @Select("""
            SELECT COUNT(*) FROM nx_event_outbox
             WHERE is_deleted=0 AND analytics_event=1 AND schema_registered=1
               AND is_server_authoritative=1 AND event_name=#{eventName}
            """)
    long countA4Events(@Param("eventName") String eventName);

    @Select("""
            SELECT COUNT(DISTINCT COALESCE(
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.user_id')), ''),
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.anon_id')), ''),
                     event_id))
              FROM nx_event_outbox
             WHERE is_deleted=0 AND analytics_event=1 AND schema_registered=1
               AND is_server_authoritative=1 AND event_name=#{eventName}
            """)
    long countA4DistinctActors(@Param("eventName") String eventName);

    @Select("""
            SELECT COUNT(*) FROM nx_event_outbox
             WHERE is_deleted=0 AND analytics_event=1 AND schema_registered=1
               AND is_server_authoritative=1 AND family_key=#{familyKey}
            """)
    long countA4FamilyEvents(@Param("familyKey") String familyKey);

    @Select("""
            SELECT event_name AS eventName,
                   COALESCE(event_ts, created_at) AS eventTs,
                   COALESCE(
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.user_id')), ''),
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.anon_id')), '')
                   ) AS actorId,
                   COALESCE(NULLIF(cohort, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cohort'))) AS cohort,
                   COALESCE(NULLIF(phase, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.phase'))) AS phase,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.locale')), 'und') AS locale,
                   COALESCE(
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.ref')), ''),
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.source')), ''),
                     'direct'
                   ) AS refCode,
                   JSON_UNQUOTE(JSON_EXTRACT(payload, '$.latency_sec')) AS latencySec,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.quantity')), '1') AS quantity
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND analytics_event = 1
               AND schema_registered = 1
               AND event_name IN (
                 'auth.register_completed', 'device.first_yield_received', 'app.dau',
                 'store.viewed', 'checkout.completed', 'device.purchase_completed',
                 'referral.invite_sent', 'nova.push_sent', 'nova.push_clicked',
                 'referral.bound', 'commission.paid', 'genesis.purchased'
               )
               AND (
                 is_server_authoritative = 1
                 OR event_name IN ('store.viewed', 'nova.push_clicked')
               )
               AND COALESCE(event_ts, created_at) >= DATE_SUB(NOW(), INTERVAL 13 MONTH)
             ORDER BY COALESCE(event_ts, created_at) ASC, id ASC
            """)
    List<Map<String, Object>> selectL1EventFacts();

    @Select("""
            SELECT event_name AS eventName,
                   event_ts AS eventTs,
                   JSON_UNQUOTE(JSON_EXTRACT(payload, '$.user_id')) AS actorId,
                   COALESCE(NULLIF(cohort, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cohort'))) AS cohort,
                   COALESCE(NULLIF(phase, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.phase'))) AS phase,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.locale')), 'und') AS locale,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.ref')), 'direct') AS refCode
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND analytics_event = 1
               AND schema_registered = 1
               AND event_ts >= DATE_SUB(CURRENT_DATE, INTERVAL 400 DAY)
               AND event_name IN (
                 'auth.register_completed', 'kyc.express_verified', 'checkout.completed',
                 'wallet.reinvest', 'withdraw.submitted', 'app.dau',
                 'trial.claim_sheet_shown', 'trial.started', 'trial.redeemed'
               )
               AND (
                 is_server_authoritative = 1
                 OR event_name IN ('trial.claim_sheet_shown', 'trial.started', 'trial.redeemed')
               )
             ORDER BY event_ts ASC, id ASC
            """)
    List<Map<String, Object>> selectL2EventFacts();

    @Select("""
            SELECT event_name AS eventName,
                   COALESCE(event_ts, created_at) AS eventTs,
                   COALESCE(
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.user_id')), ''),
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.anon_id')), '')
                   ) AS actorId,
                   COALESCE(NULLIF(cohort, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cohort'))) AS cohort,
                   COALESCE(NULLIF(phase, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.phase'))) AS phase,
                   COALESCE(
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.ref')), ''),
                     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.source')), ''),
                     'direct'
                   ) AS refCode,
                   JSON_UNQUOTE(JSON_EXTRACT(payload, '$.latency_sec')) AS latencySec
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND analytics_event = 1
               AND schema_registered = 1
               AND event_name IN (
                 'auth.register_completed', 'kyc.express_verified', 'checkout.completed',
                 'wallet.reinvest', 'withdraw.submitted', 'device.first_yield_received',
                 'app.dau', 'store.viewed'
               )
               AND (is_server_authoritative = 1 OR event_name = 'store.viewed')
               AND COALESCE(event_ts, created_at) >= DATE_SUB(CURRENT_DATE, INTERVAL 13 MONTH)
             ORDER BY COALESCE(event_ts, created_at) ASC, id ASC
            """)
    List<Map<String, Object>> selectB3EventFacts();

    @Select("""
            SELECT event_id AS eventId,
                   event_name AS eventName,
                   COALESCE(event_ts, created_at) AS eventTs,
                   JSON_UNQUOTE(JSON_EXTRACT(payload, '$.user_id')) AS actorId,
                   COALESCE(NULLIF(phase, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.phase'))) AS phase,
                   COALESCE(NULLIF(cohort, ''), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cohort'))) AS cohort,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.locale')), 'und') AS locale,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.ref')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.source')), ''), 'direct') AS refCode,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.device_id')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.deviceId')), '')) AS deviceId,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.model')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.device_model')), '')) AS model,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.generation')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.device_generation')), '')) AS generation,
                   JSON_UNQUOTE(JSON_EXTRACT(payload, '$.status')) AS status,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.tier')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.layer')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.commission_type')), '')) AS tier,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.quest_id')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.questId')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.task_id')), '')) AS questKey,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.relationship_id')), ''),
                            CONCAT_WS(':',
                              NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.parent_id')), ''),
                              NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.user_id')), ''))) AS relationshipKey,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.v_rank')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.vrank')), '')) AS vRank,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.team_size')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.teamSize'))) AS teamSize,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.amount_usdt')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.amount')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.gmv_usdt')), '0') AS amountUsdt,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.amount_nex')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.nex_amount')), '0') AS amountNex,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.baseline_usdt')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.baselineUsdt')), '0') AS baselineUsdt,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.degradation_rate')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.degradationRate'))) AS degradationRate,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.queue_saturation')),
                            JSON_UNQUOTE(JSON_EXTRACT(payload, '$.queueSaturation'))) AS queueSaturation
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND analytics_event = 1
               AND schema_registered = 1
               AND event_name IN (
                 'device.purchase_completed', 'device.first_yield_received', 'device.locked', 'device.retired',
                 'earnings.credited', 'quest.dispatched', 'quest.completed', 'daily.checkin',
                 'referral.bound', 'referral.invite_sent', 'commission.paid', 'checkout.completed',
                 'auth.register_completed', 'store.viewed', 'app.dau', 'phase.transitioned', 'phase.dial_changed'
               )
               AND (is_server_authoritative = 1 OR event_name = 'store.viewed')
               AND COALESCE(event_ts, created_at) >= DATE_SUB(CURRENT_DATE, INTERVAL 400 DAY)
             ORDER BY COALESCE(event_ts, created_at) ASC, id ASC
            """)
    List<Map<String, Object>> selectL4EventFacts();

    @Select("""
            SELECT CASE
                     WHEN LENGTH(CAST(member_user_id AS CHAR)) <= 4 THEN '***'
                     ELSE CONCAT(LEFT(CAST(member_user_id AS CHAR), 2), '***', RIGHT(CAST(member_user_id AS CHAR), 2))
                   END AS memberUserIdPartial,
                   CASE
                     WHEN LENGTH(CAST(user_id AS CHAR)) <= 4 THEN '***'
                     ELSE CONCAT(LEFT(CAST(user_id AS CHAR), 2), '***', RIGHT(CAST(user_id AS CHAR), 2))
                   END AS sponsorUserIdPartial,
                   level AS treeDepth,
                   COALESCE(NULLIF(v_rank, ''), 'UNRANKED') AS vRank,
                   COALESCE(volume, 0) AS teamVolumeUsdt,
                   created_at AS joinedAt
              FROM nx_team_member
             WHERE is_deleted = 0
               AND level BETWEEN 1 AND #{depth}
               AND created_at >= CASE LOWER(#{period})
                 WHEN 'day' THEN CURRENT_DATE
                 WHEN 'month' THEN DATE_SUB(CURRENT_DATE, INTERVAL 1 MONTH)
                 ELSE DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY)
               END
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<Map<String, Object>> selectL4NetworkTreeRows(
            @Param("period") String period, @Param("depth") int depth, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0")
    long countUsers();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countUsersSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND status = #{status}")
    long countUsersByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM nx_user_profile WHERE is_deleted = 0")
    long countUserProfiles();

    @Select("SELECT COUNT(*) FROM nx_kyc_profile WHERE is_deleted = 0")
    long countKycProfiles();

    @Select("SELECT COUNT(*) FROM nx_kyc_profile WHERE is_deleted = 0 AND status = #{status}")
    long countKycProfilesByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM nx_order WHERE is_deleted = 0")
    long countOrders();

    @Select("SELECT COUNT(*) FROM nx_order WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countOrdersSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_admin_device_order WHERE is_deleted = 0")
    long countAdminDeviceOrders();

    @Select("SELECT COUNT(*) FROM nx_admin_device_order WHERE is_deleted = 0 AND ordered_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countAdminDeviceOrdersSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_device WHERE deleted = 0")
    long countDevices();

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0")
    long countUserDevices();

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0 AND status IN ('ONLINE','BUSY','ACTIVE','RUNNING')")
    long countActiveUserDevices();

    @Select("SELECT COUNT(*) FROM nx_compute_task WHERE is_deleted = 0")
    long countComputeTasks();

    @Select("SELECT COUNT(*) FROM nx_compute_task WHERE is_deleted = 0 AND status = 'COMPLETED'")
    long countCompletedComputeTasks();

    @Select("SELECT COUNT(*) FROM nx_admin_device_task WHERE is_deleted = 0")
    long countDeviceTaskCatalogItems();

    @Select("SELECT COUNT(*) FROM nx_team_member WHERE is_deleted = 0")
    long countTeamRelationships();

    @Select("SELECT COUNT(*) FROM nx_commission_event WHERE is_deleted = 0")
    long countCommissionEvents();

    @Select("SELECT COUNT(*) FROM nx_admin_phase_config WHERE is_deleted = 0 AND status = 'active'")
    long countConfiguredPhases();

    @Select("SELECT COUNT(*) FROM nx_tradein_application WHERE is_deleted = 0")
    long countTradeinApplications();

    @Select("SELECT COUNT(*) FROM nx_tradein_application WHERE is_deleted = 0 AND UPPER(status) = 'COMPLETED'")
    long countCompletedTradeins();

    @Select("SELECT COUNT(*) FROM nx_withdrawal_order WHERE is_deleted = 0")
    long countWithdrawals();

    @Select("SELECT COUNT(*) FROM nx_withdrawal_order WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countWithdrawalsSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_withdrawal_order WHERE is_deleted = 0 AND status = #{status}")
    long countWithdrawalsByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM nx_exchange_order WHERE is_deleted = 0")
    long countExchanges();

    @Select("SELECT COUNT(*) FROM nx_exchange_order WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countExchangesSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_staking_position WHERE is_deleted = 0")
    long countStakingPositions();

    @Select("SELECT COUNT(*) FROM nx_wallet_ledger WHERE is_deleted = 0")
    long countWalletLedgers();

    @Select("SELECT COUNT(*) FROM nx_wallet_ledger WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countWalletLedgersSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_wallet_bill WHERE deleted = 0")
    long countWalletBills();

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted = 0")
    long countSupportTickets();

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countSupportTicketsSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted = 0 AND status = #{status}")
    long countSupportTicketsByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted = 0")
    long countConversations();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countConversationsSince(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM nx_audit_log WHERE is_deleted = 0")
    long countAuditLogs();

    @Select("SELECT COUNT(*) FROM nx_audit_log WHERE is_deleted = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countAuditLogsSince(@Param("days") int days);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5'
               AND is_deleted = 0
             <if test='type != null and type != ""'>AND report_type = #{type}</if>
             <if test='statuses != null and statuses.size() > 0'>
               AND status IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>
                 #{status}
               </foreach>
             </if>
            </script>
            """)
    long countReports(@Param("type") String type, @Param("statuses") List<String> statuses);

    @Select("""
            <script>
            SELECT report_id AS reportId,
                   report_name AS name,
                   report_type AS type,
                   cycle,
                   file_format AS format,
                   scope_text AS scope,
                   field_text AS fields,
                   row_count AS rowCount,
                   contains_pii AS containsPii,
                   masking_policy AS maskingPolicy,
                   status,
                   note,
                   last_action AS lastAction,
                   last_action_at AS lastActionAt,
                   reason,
                   CASE WHEN snapshot_csv IS NOT NULL AND LENGTH(snapshot_csv) > 0 THEN TRUE ELSE FALSE END AS snapshotAvailable
              FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5'
               AND is_deleted = 0
             <if test='type != null and type != ""'>AND report_type = #{type}</if>
             <if test='statuses != null and statuses.size() > 0'>
               AND status IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>
                 #{status}
               </foreach>
             </if>
             ORDER BY FIELD(status, 'PENDING_SPLIT_CONFIRM', 'PENDING_CONFIRM', 'GENERATING', 'READY', 'EXPIRED', 'FAILED'),
                      updated_at DESC, id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<BiReportView> reports(@Param("type") String type, @Param("statuses") List<String> statuses,
                                @Param("pageSize") int pageSize, @Param("offset") int offset);

    @Select("""
            SELECT report_id AS reportId,
                   report_name AS name,
                   report_type AS type,
                   cycle,
                   file_format AS format,
                   scope_text AS scope,
                   field_text AS fields,
                   status,
                   row_count AS rowCount,
                   contains_pii AS containsPii,
                   masking_policy AS maskingPolicy
              FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5'
               AND is_deleted = 0
               AND UPPER(report_type) IN ('REGULATORY', 'REGULATORY_TEMPLATE', 'REGULATORY_EXPORT')
             ORDER BY updated_at DESC, id DESC
            """)
    List<Map<String, Object>> regulatoryTemplates();

    @Select("""
            SELECT report_id AS reportId,
                   report_name AS name,
                   report_type AS type,
                   cycle,
                   file_format AS format,
                   scope_text AS scope,
                   field_text AS fields,
                   row_count AS rowCount,
                   contains_pii AS containsPii,
                   masking_policy AS maskingPolicy,
                   status,
                   note,
                   last_action AS lastAction,
                   last_action_at AS lastActionAt,
                   reason,
                   CASE WHEN snapshot_csv IS NOT NULL AND LENGTH(snapshot_csv) > 0 THEN TRUE ELSE FALSE END AS snapshotAvailable
              FROM nx_admin_fourth_batch_report
             WHERE module_code = 'L5'
               AND report_id = #{reportId}
               AND is_deleted = 0
             LIMIT 1
            """)
    BiReportView findReport(@Param("reportId") String reportId);

    @Update("""
            UPDATE nx_admin_fourth_batch_report
               SET status = #{nextStatus},
                   last_action = #{action},
                   last_action_at = NOW(),
                   reason = #{reason},
                   updated_at = NOW()
             WHERE module_code = 'L5' AND report_id = #{reportId} AND status = #{expectedStatus} AND is_deleted = 0
            """)
    int updateActionIfStatus(@Param("reportId") String reportId, @Param("action") String action,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("nextStatus") String nextStatus, @Param("reason") String reason);

    @Select("""
            SELECT id,
                   view_name AS name,
                   cohort,
                   phase,
                   ref_code AS ref,
                   granularity,
                   comparison,
                   updated_at AS updatedAt
              FROM nx_admin_funnel_view
             WHERE admin_id = #{adminId} AND view_name = #{name} AND is_deleted = 0
             LIMIT 1
            """)
    Map<String, Object> findB3View(@Param("adminId") long adminId, @Param("name") String name);

    @Select("""
            SELECT view_name AS name,
                   cohort,
                   phase,
                   ref_code AS ref,
                   granularity,
                   comparison,
                   updated_at AS updatedAt
              FROM nx_admin_funnel_view
             WHERE admin_id = #{adminId} AND is_deleted = 0
             ORDER BY updated_at DESC, id DESC
             LIMIT 20
            """)
    List<Map<String, Object>> selectB3Views(@Param("adminId") long adminId);

    @Insert("""
            INSERT INTO nx_admin_funnel_view(
              admin_id, view_name, cohort, phase, ref_code, granularity, comparison,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{adminId}, #{name}, #{cohort}, #{phase}, #{ref}, #{granularity}, #{comparison},
              NOW(), NOW(), 0
            )
            """)
    int insertB3View(
            @Param("adminId") long adminId,
            @Param("name") String name,
            @Param("cohort") String cohort,
            @Param("phase") String phase,
            @Param("ref") String ref,
            @Param("granularity") String granularity,
            @Param("comparison") String comparison);

    record ReportSeed(
            String moduleCode,
            String reportId,
            String reportName,
            String reportType,
            String cycle,
            String fileFormat,
            String scopeText,
            String fieldText,
            Long rowCount,
            Boolean containsPii,
            String maskingPolicy,
            String status,
            String note) {
    }

}
