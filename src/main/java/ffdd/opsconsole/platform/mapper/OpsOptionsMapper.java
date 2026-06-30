package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface OpsOptionsMapper extends BaseMapper<AdminRoleOptionEntity> {
    @Select("""
            SELECT COALESCE(NULLIF(dc_location, ''), 'UNASSIGNED') AS dcLocation
              FROM nx_user_device
             WHERE is_deleted = 0
             GROUP BY COALESCE(NULLIF(dc_location, ''), 'UNASSIGNED')
             ORDER BY dcLocation
             LIMIT 100
            """)
    List<String> datacenters();

    @Select("""
            SELECT
                COALESCE(NULLIF(a.nickname, ''), NULLIF(a.username, ''), CAST(a.id AS CHAR)) AS label,
                CAST(a.id AS CHAR) AS value
              FROM nx_support_agent_profile p
              JOIN nx_admin a ON a.id = p.admin_id AND a.is_deleted = 0
             WHERE p.is_deleted = 0
               AND p.enabled = 1
               AND a.status = 1
             ORDER BY label ASC
             LIMIT 200
            """)
    List<OptionRow> supportAgents();

    @Select("""
            SELECT
                CONCAT(COALESCE(NULLIF(a.nickname, ''), NULLIF(a.username, ''), CAST(a.id AS CHAR)), ' · ', p.position) AS label,
                CAST(a.id AS CHAR) AS value
              FROM nx_support_agent_profile p
              JOIN nx_admin a ON a.id = p.admin_id AND a.is_deleted = 0
             WHERE p.is_deleted = 0
               AND p.enabled = 1
               AND p.transferable = 1
               AND a.status = 1
             ORDER BY p.busy ASC, p.updated_at DESC, a.id ASC
             LIMIT 200
            """)
    List<OptionRow> transferTargets();

    @Select("""
            SELECT
                CONCAT(COALESCE(NULLIF(level, ''), NULLIF(title, ''), article_code), ' · ', article_code) AS label,
                content AS value
              FROM nx_help_article
             WHERE is_deleted = 0
               AND format = 'session_reply_template'
               AND status = 1
             ORDER BY sort_order ASC, updated_at DESC, id DESC
             LIMIT 200
            """)
    List<OptionRow> sessionReplyTemplates();

    @Select("""
            SELECT DISTINCT queue AS label, queue AS value
              FROM nx_support_sla_rule
             WHERE is_deleted = 0
               AND queue IS NOT NULL
               AND queue <> ''
             ORDER BY queue ASC
            """)
    List<OptionRow> supportSlaQueues();

    @Select("""
            SELECT DISTINCT escalation AS label, escalation AS value
              FROM nx_support_sla_rule
             WHERE is_deleted = 0
               AND escalation IS NOT NULL
               AND escalation <> ''
             ORDER BY escalation ASC
            """)
    List<OptionRow> supportSlaEscalations();

    @Select("""
            SELECT
                category,
                first_response_mins AS firstResponseMins,
                resolution_hours AS resolutionHours,
                queue,
                escalation
              FROM nx_support_sla_rule
             WHERE is_deleted = 0
             ORDER BY category ASC, id ASC
            """)
    List<SupportSlaOptionRow> supportSlaRules();

    record OptionRow(String label, String value) {
    }

    record SupportSlaOptionRow(
            String category,
            Integer firstResponseMins,
            Integer resolutionHours,
            String queue,
            String escalation
    ) {
    }
}
