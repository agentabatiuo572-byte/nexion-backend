package ffdd.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.auth.domain.AdminRoleRelation;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminRoleRelationMapper extends BaseMapper<AdminRoleRelation> {
    @Select("SELECT id, admin_id, role_id, created_at, updated_at, is_deleted FROM admin_role_relation WHERE admin_id = #{adminId}")
    List<AdminRoleRelation> selectAllByAdminId(@Param("adminId") Long adminId);

    @Select("SELECT role_id FROM admin_role_relation WHERE admin_id = #{adminId} AND is_deleted = 0")
    List<Long> selectActiveRoleIdsByAdminId(@Param("adminId") Long adminId);

    @Select("SELECT COUNT(1) FROM admin_role_relation WHERE role_id = #{roleId} AND is_deleted = 0")
    long countActiveByRoleId(@Param("roleId") Long roleId);

    @Select("""
            SELECT COALESCE(NULLIF(a.nickname, ''), a.username)
            FROM admin_role_relation rr
            JOIN admin a ON a.id = rr.admin_id
            WHERE rr.role_id = #{roleId}
              AND rr.is_deleted = 0
              AND a.is_deleted = 0
            ORDER BY a.id
            """)
    List<String> selectActiveAdminNamesByRoleId(@Param("roleId") Long roleId);

    @Update("UPDATE admin_role_relation SET is_deleted = #{isDeleted}, updated_at = NOW() WHERE id = #{id}")
    int updateDeletedById(@Param("id") Long id, @Param("isDeleted") Integer isDeleted);

    @Insert("""
            INSERT INTO admin_role_relation (admin_id, role_id, created_at, updated_at, is_deleted)
            VALUES (#{adminId}, #{roleId}, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW()
            """)
    int upsertActive(@Param("adminId") Long adminId, @Param("roleId") Long roleId);
}
