package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.I18nMessageVersionEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface I18nMessageVersionMapper extends BaseMapper<I18nMessageVersionEntity> {
    @Insert("""
            INSERT INTO nx_i18n_message_version
                (message_key, version_no, zh_value, en_value, vi_value, status, created_at, updated_at, is_deleted)
            SELECT #{messageKey},
                   #{nextVersionNo},
                   #{zhValue},
                   #{enValue},
                   #{viValue},
                   'DRAFT',
                   #{updatedAt},
                   #{updatedAt},
                   0
              FROM nx_i18n_message_version expected
             WHERE expected.message_key = #{messageKey}
               AND expected.version_no = #{expectedVersionNo}
               AND expected.is_deleted = 0
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_i18n_message_version newer
                    WHERE newer.message_key = expected.message_key
                      AND newer.version_no > expected.version_no
               )
            """)
    int insertDraftCas(
            @Param("messageKey") String messageKey,
            @Param("expectedVersionNo") int expectedVersionNo,
            @Param("nextVersionNo") int nextVersionNo,
            @Param("zhValue") String zhValue,
            @Param("enValue") String enValue,
            @Param("viValue") String viValue,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE nx_i18n_message_version
               SET is_deleted = 1,
                   updated_at = #{updatedAt}
             WHERE message_key = #{messageKey}
               AND version_no = #{expectedVersionNo}
               AND status = 'DRAFT'
               AND is_deleted = 0
            """)
    int retireDraftCas(
            @Param("messageKey") String messageKey,
            @Param("expectedVersionNo") int expectedVersionNo,
            @Param("updatedAt") LocalDateTime updatedAt);
}
