package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.infrastructure.BiReportEntity;
import java.util.List;
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

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_bi_dashboard_payload (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              module_code VARCHAR(16) NOT NULL,
              section_key VARCHAR(64) NOT NULL,
              payload_json LONGTEXT NOT NULL,
              sort_order INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_bi_dashboard_payload (module_code, section_key),
              KEY idx_bi_dashboard_payload_module (module_code, sort_order)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createDashboardPayloadTable();

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_bi_dashboard_payload
             WHERE module_code = #{moduleCode}
               AND is_deleted = 0
            """)
    long countDashboardPayloads(@Param("moduleCode") String moduleCode);

    @Insert("""
            INSERT INTO nx_admin_bi_dashboard_payload (
              module_code, section_key, payload_json, sort_order, is_deleted
            ) VALUES (
              #{moduleCode}, #{sectionKey}, #{payloadJson}, #{sortOrder}, 0
            )
            ON DUPLICATE KEY UPDATE
              payload_json = VALUES(payload_json),
              sort_order = VALUES(sort_order),
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertDashboardPayload(@Param("moduleCode") String moduleCode,
                               @Param("sectionKey") String sectionKey,
                               @Param("payloadJson") String payloadJson,
                               @Param("sortOrder") int sortOrder);

    @Select("""
            SELECT section_key AS sectionKey,
                   payload_json AS payloadJson
              FROM nx_admin_bi_dashboard_payload
             WHERE module_code = #{moduleCode}
               AND is_deleted = 0
             ORDER BY sort_order ASC, id ASC
            """)
    List<DashboardPayloadRow> dashboardPayloads(@Param("moduleCode") String moduleCode);

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

    record DashboardPayloadRow(String sectionKey, String payloadJson) {
    }

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
