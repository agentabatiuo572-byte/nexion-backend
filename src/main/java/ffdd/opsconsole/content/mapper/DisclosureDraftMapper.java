package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.DisclosureDraftEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DisclosureDraftMapper extends BaseMapper<DisclosureDraftEntity> {
    @Update("""
            UPDATE nx_disclosure_draft
               SET language_scope = #{row.languageScope}, effective_date = #{row.effectiveDate},
                   requires_reack = #{row.requiresReack}, zh_body = #{row.zhBody}, vi_body = #{row.viBody},
                   en_body = #{row.enBody}, status = #{row.status}, revision = revision + 1,
                   content_hash = #{newContentHash}, last_operator = #{row.lastOperator}, updated_at = #{now}
             WHERE id = #{row.id} AND revision = #{expectedRevision}
               AND content_hash = #{expectedContentHash} AND status = 'DRAFT' AND is_deleted = 0
            """)
    int updateDraftOptimistically(@Param("row") DisclosureDraftEntity row,
                                  @Param("expectedRevision") long expectedRevision,
                                  @Param("expectedContentHash") String expectedContentHash,
                                  @Param("newContentHash") String newContentHash,
                                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_disclosure_draft
               SET is_deleted = 1, updated_at = #{now}
             WHERE jurisdiction_code = UPPER(#{jurisdiction}) AND version_label = #{version}
               AND revision = #{expectedRevision} AND content_hash = #{expectedContentHash}
               AND status = 'DRAFT' AND is_deleted = 0
            """)
    int softDeleteDraftOptimistically(@Param("jurisdiction") String jurisdiction,
                                      @Param("version") String version,
                                      @Param("expectedRevision") long expectedRevision,
                                      @Param("expectedContentHash") String expectedContentHash,
                                      @Param("now") LocalDateTime now);
}
