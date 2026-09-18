package ffdd.opsconsole.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.audit.infrastructure.AuditLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Checks original required A2 evidence; never creates an audit from an event payload. */
public interface C1AuditEvidenceMapper extends BaseMapper<AuditLogEntity> {
    @Select("""
            SELECT COUNT(*) FROM nx_audit_log
             WHERE biz_no = CONCAT('C1-VIEW-', #{eventId})
               AND action = 'ADMIN.USER_PROFILE_VIEWED' AND resource_type = 'USER_PROFILE'
               AND BINARY resource_id = BINARY #{resourceId} AND user_id = #{userId}
               AND BINARY actor_username = BINARY #{actor} AND actor_type = 'ADMIN'
               AND result = 'SUCCESS' AND is_deleted = 0
               AND BINARY JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.role')) = BINARY #{role}
               AND JSON_EXTRACT(detail_json, '$.cardsViewed') = CAST(#{cardsJson} AS JSON)
            """)
    int countProfileEvidence(@Param("eventId") String eventId, @Param("resourceId") String resourceId,
                             @Param("userId") long userId, @Param("actor") String actor,
                             @Param("role") String role, @Param("cardsJson") String cardsJson);

    @Select("""
            SELECT COUNT(*) FROM nx_audit_log
             WHERE action = 'ADMIN.USER_LIST_EXPORTED' AND resource_type = 'USER_PROFILE_EXPORT'
               AND BINARY resource_id = BINARY #{jobNo}
               AND BINARY actor_username = BINARY #{actor} AND actor_type = 'ADMIN'
               AND result = 'SUCCESS' AND is_deleted = 0
               AND BINARY JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.filterHash')) = BINARY #{filterHash}
               AND JSON_EXTRACT(detail_json, '$.rowCount') = #{rowCount}
               AND JSON_EXTRACT(detail_json, '$.masked') = TRUE
            """)
    int countExportEvidence(@Param("jobNo") String jobNo, @Param("actor") String actor,
                            @Param("filterHash") String filterHash, @Param("rowCount") long rowCount);
}
