package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.ConversationCustomerProfile;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 客户档案标注(nx_customer_tag / nx_customer_note)注解 SQL;无 entity,
 * 参照 {@code nx_conversation_transfer} 的轻量范式(直接 @Insert/@Delete)。
 * 由 {@code @MapperScan("ffdd.opsconsole.**.mapper")} 自动注册。
 */
public interface CustomerProfileMapper {

    @Select("SELECT tag FROM nx_customer_tag WHERE user_id=#{userId} AND is_deleted=0 ORDER BY id ASC")
    List<String> findCustomTags(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_customer_tag (user_id, tag, last_operator, is_deleted, created_at, updated_at)
            VALUES (#{userId}, #{tag}, #{operator}, 0, #{now}, #{now})
            """)
    int addCustomTag(@Param("userId") Long userId, @Param("tag") String tag,
                     @Param("operator") String operator, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM nx_customer_tag WHERE user_id=#{userId} AND tag=#{tag}")
    int removeCustomTag(@Param("userId") Long userId, @Param("tag") String tag);

    // 列别名 ts/text 对齐 CustomerNote record 构造器参数(id BIGINT→String, ts 毫秒 epoch)
    @Select("""
            SELECT id, UNIX_TIMESTAMP(created_at) * 1000 AS ts, author, content AS text
              FROM nx_customer_note
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY created_at ASC, id ASC
            """)
    List<ConversationCustomerProfile.CustomerNote> findNotes(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_customer_note (user_id, author, content, last_operator, is_deleted, created_at, updated_at)
            VALUES (#{userId}, #{author}, #{content}, #{operator}, 0, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertNote(CustomerNoteRow row);

    @Update("UPDATE nx_customer_note SET is_deleted=1, last_operator=#{operator}, updated_at=#{now} WHERE id=#{noteId} AND is_deleted=0")
    int softDeleteNote(@Param("noteId") Long noteId, @Param("operator") String operator, @Param("now") LocalDateTime now);
}
