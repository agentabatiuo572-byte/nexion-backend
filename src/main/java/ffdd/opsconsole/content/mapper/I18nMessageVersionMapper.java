package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.I18nMessageVersionEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface I18nMessageVersionMapper extends BaseMapper<I18nMessageVersionEntity> {
    @Update("""
            UPDATE nx_i18n_message_version
               SET is_deleted = 1,
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = 'DRAFT'
               AND is_deleted = 0
            """)
    int retireDraftCas(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
