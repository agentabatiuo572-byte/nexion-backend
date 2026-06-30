package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AdminRoleRelationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminRoleRelationMapper extends BaseMapper<AdminRoleRelationEntity> {
    @Update("""
            UPDATE nx_admin_role_relation
            SET is_deleted = 1, updated_at = NOW()
            WHERE admin_id = #{adminId}
              AND role_id NOT IN (
                  SELECT id
                  FROM nx_admin_role
                  WHERE role_code = #{roleCode}
                    AND status = 1
                    AND is_deleted = 0
              )
            """)
    int disableOtherPrimaryRoles(@Param("adminId") Long adminId, @Param("roleCode") String roleCode);

    @Insert("""
            INSERT INTO nx_admin_role_relation (admin_id, role_id)
            SELECT #{adminId}, id
            FROM nx_admin_role
            WHERE role_code = #{roleCode}
              AND status = 1
              AND is_deleted = 0
            LIMIT 1
            ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW()
            """)
    int ensurePrimaryRole(@Param("adminId") Long adminId, @Param("roleCode") String roleCode);

    @Select("""
            SELECT r.role_code
              FROM nx_admin_role_relation rr
              JOIN nx_admin_role r
                ON r.id = rr.role_id
               AND r.status = 1
               AND r.is_deleted = 0
             WHERE rr.admin_id = #{adminId}
               AND rr.is_deleted = 0
             ORDER BY rr.updated_at DESC, rr.id DESC
             LIMIT 1
            """)
    String activeRoleCode(@Param("adminId") Long adminId);
}
