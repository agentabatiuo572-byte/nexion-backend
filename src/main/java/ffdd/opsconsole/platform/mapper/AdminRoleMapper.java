package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminRoleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** A6 角色管理 CRUD mapper。与 OpsOptionsMapper(下拉用 AdminRoleOptionEntity) 同表不同实体，互不交叉。 */
public interface AdminRoleMapper extends BaseMapper<AdminRoleEntity> {

    @Select("SELECT COUNT(*) FROM nx_admin_role WHERE role_code = #{roleCode} AND is_deleted = 0")
    boolean existsByRoleCode(@Param("roleCode") String roleCode);

    @Select("SELECT * FROM nx_admin_role WHERE role_code = #{roleCode} LIMIT 1 FOR UPDATE")
    AdminRoleEntity selectByRoleCodeForUpdate(@Param("roleCode") String roleCode);

    @Select("SELECT * FROM nx_admin_role WHERE id = #{roleId} AND is_deleted = 0 FOR UPDATE")
    AdminRoleEntity selectActiveForUpdate(@Param("roleId") Long roleId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM nx_audit_operation_ticket
                 WHERE operation_id=#{operationId} AND is_deleted=0 AND source_domain='A'
                   AND CASE WHEN JSON_VALID(command_json) THEN
                       JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.domain'))='A'
                       AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.op')) IN (#{op},#{alternateOp})
                       AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.params.roleId'))=#{roleId}
                     ELSE FALSE END
            )
            """)
    boolean matchesA6Proposal(@Param("operationId") String operationId, @Param("roleId") String roleId,
                              @Param("op") String op, @Param("alternateOp") String alternateOp);

    /** Only committed A6 writes for the exact command key and target may prove a legacy receipt. */
    @Select("""
            SELECT DISTINCT actorId FROM (
              SELECT actor_id AS actorId
                FROM nx_audit_log
               WHERE is_deleted=0 AND resource_type='A6_PLATFORM_ROLE' AND result='SUCCESS' AND actor_type='ADMIN'
                 AND resource_id=#{roleId} AND action IN (#{action},#{alternateAction})
                 AND BINARY JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.idempotencyKey'))=BINARY #{key}
              UNION ALL
              SELECT CASE WHEN JSON_TYPE(JSON_EXTRACT(cold_payload,'$.actorId')) IN ('INTEGER','UNSIGNED INTEGER')
                          THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorId')) AS SIGNED) END AS actorId
                FROM nx_audit_log_archive
               WHERE content_sha256 IS NOT NULL
                 AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType'))='A6_PLATFORM_ROLE'
                 AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.result'))='SUCCESS'
                 AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorType'))='ADMIN'
                 AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId'))=#{roleId}
                 AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')) IN (#{action},#{alternateAction})
                 AND BINARY JSON_UNQUOTE(JSON_EXTRACT(
                       JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.detail')),'$.idempotencyKey'))=BINARY #{key}
            ) receiptActors
            LIMIT 2
            """)
    List<Long> findA6WriteActors(@Param("roleId") String roleId, @Param("key") String key,
                                @Param("action") String action, @Param("alternateAction") String alternateAction);
}
