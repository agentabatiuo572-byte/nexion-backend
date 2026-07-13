package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.DisclosureJurisdictionCatalogEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DisclosureJurisdictionCatalogMapper extends BaseMapper<DisclosureJurisdictionCatalogEntity> {
    @Select("""
            SELECT * FROM nx_disclosure_jurisdiction_catalog
             WHERE jurisdiction_code = UPPER(#{code})
             LIMIT 1 FOR UPDATE
            """)
    DisclosureJurisdictionCatalogEntity selectAnyForUpdate(@Param("code") String code);

    @Select("""
            SELECT id FROM nx_disclosure_jurisdiction_catalog
             WHERE is_deleted = 0
             ORDER BY id
             FOR UPDATE
            """)
    List<Long> selectAllIdsForUpdate();

    @Update("""
            UPDATE nx_disclosure_jurisdiction_catalog
               SET jurisdiction_name = #{name}, revision = revision + 1,
                   last_operator = #{operator}, updated_at = #{now}
             WHERE jurisdiction_code = UPPER(#{code}) AND is_deleted = 0
               AND revision = #{expectedRevision} AND status <> 'ARCHIVED'
            """)
    int updateNameOptimistically(@Param("code") String code, @Param("name") String name,
                                 @Param("expectedRevision") long expectedRevision,
                                 @Param("operator") String operator, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_disclosure_jurisdiction_catalog
               SET status = #{status}, revision = revision + 1,
                   last_operator = #{operator}, updated_at = #{now}
             WHERE jurisdiction_code = UPPER(#{code}) AND is_deleted = 0
               AND revision = #{expectedRevision}
            """)
    int updateStatusOptimistically(@Param("code") String code, @Param("status") String status,
                                   @Param("expectedRevision") long expectedRevision,
                                   @Param("operator") String operator, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_disclosure_jurisdiction_catalog
               SET is_deleted = 1, revision = revision + 1,
                   last_operator = #{operator}, updated_at = #{now}
             WHERE jurisdiction_code = UPPER(#{code}) AND is_deleted = 0
               AND status = 'ARCHIVED' AND revision = #{expectedRevision}
            """)
    int softDeleteOptimistically(@Param("code") String code, @Param("expectedRevision") long expectedRevision,
                                 @Param("operator") String operator, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM (
              SELECT version_label FROM nx_disclosure_draft WHERE jurisdiction_code = UPPER(#{code})
              UNION
              SELECT version_label FROM nx_disclosure_chapter WHERE jurisdiction_code = UPPER(#{code})
              UNION
              SELECT version_label FROM nx_disclosure_jurisdiction WHERE jurisdiction_code = UPPER(#{code})
              UNION
              SELECT required_version FROM nx_disclosure_ack_status WHERE jurisdiction_code = UPPER(#{code})
              UNION
              SELECT version_label FROM nx_disclosure_read_token WHERE jurisdiction_code = UPPER(#{code})
            ) refs
            """)
    long countVersionReferences(@Param("code") String code);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM nx_disclosure_jurisdiction
               WHERE jurisdiction_code = UPPER(#{code}) AND is_deleted = 0 AND status <> 'ARCHIVED'
            )
            """)
    boolean hasActiveMapping(@Param("code") String code);
}
