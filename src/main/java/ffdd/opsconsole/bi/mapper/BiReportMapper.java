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

    @Insert("""
            INSERT INTO nx_admin_fourth_batch_report (
              module_code, report_id, report_name, report_type, cycle, file_format,
              scope_text, field_text, row_count, contains_pii, masking_policy, status, note, is_deleted
            ) VALUES (
              #{moduleCode}, #{reportId}, #{reportName}, #{reportType}, #{cycle}, #{fileFormat},
              #{scopeText}, #{fieldText}, #{rowCount}, #{containsPii}, #{maskingPolicy}, #{status}, #{note}, 0
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
             WHERE module_code = 'L5' AND status = 'READY' AND is_deleted = 0
            """)
    long countReadyReports();

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
                   reason
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
                   reason
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
             WHERE module_code = 'L5' AND report_id = #{reportId} AND is_deleted = 0
            """)
    int updateAction(@Param("reportId") String reportId, @Param("action") String action,
                     @Param("nextStatus") String nextStatus, @Param("reason") String reason);

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
