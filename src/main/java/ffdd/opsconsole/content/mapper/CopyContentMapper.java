package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.CopyContentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CopyContentMapper extends BaseMapper<CopyContentEntity> {

    /** Counts other copies that the public position reader would treat as live. */
    @Select("""
            SELECT COUNT(1)
              FROM nx_content_copy c
              JOIN nx_content_copy_version v
                ON v.copy_key = c.copy_key
               AND v.version = c.current_version
               AND v.is_deleted = 0
               AND v.status = 'PUBLISHED'
             WHERE c.is_deleted = 0
               AND c.copy_position = #{positionKey}
               AND c.copy_key <> #{copyKey}
               AND c.status IN ('PUBLISHED', 'DRAFT_SAVED')
            """)
    long countOtherLiveCopiesAtPosition(@Param("copyKey") String copyKey,
                                        @Param("positionKey") String positionKey);
}
