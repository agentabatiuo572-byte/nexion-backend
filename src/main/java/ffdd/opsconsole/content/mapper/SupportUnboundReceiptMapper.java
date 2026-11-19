package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SupportUnboundReceiptMapper extends BaseMapper<Object> {
    /**
     * Inserts the receipt at most once per customer thread (threads are reused, the App shows its own waiting hint).
     * BUG-519: the row carries a stable identity in {@code origin} instead of relying on the wording, which now
     * follows the customer's language; a receipt already stored before that change is still recognised by its
     * body (the then-configured legacy text or one of the built-in defaults) so an existing thread never gets a
     * second acknowledgement. Other system rows (session ended, agent switch, ...) are neither kind nor body
     * matches here. Returns 0 when the acknowledgement already exists.
     */
    @Insert("""
            INSERT INTO nx_conversation_message
              (conversation_id,conversation_no,sender_id,sender_type,sender_name,content,origin,created_at,updated_at,is_deleted)
            SELECT c.id,c.conversation_no,NULL,'system',#{senderName},#{content},#{origin},#{now},#{now},0
              FROM nx_conversation c
             WHERE c.conversation_no=#{conversationNo} AND c.is_deleted=0 AND c.status='OPEN'
               AND NOT EXISTS (SELECT 1 FROM nx_conversation_message m
                                WHERE m.conversation_no=#{conversationNo} AND m.is_deleted=0 AND m.sender_type='system'
                                  AND (m.origin=#{origin}
                                       OR (m.origin IS NULL
                                           AND (m.content=#{builtinZh} OR m.content=#{builtinEn}
                                                OR m.content=#{builtinVi} OR m.content=#{legacyText}))))
            """)
    int insertOnce(@Param("conversationNo") String conversationNo, @Param("senderName") String senderName,
                   @Param("content") String content, @Param("origin") String origin,
                   @Param("builtinZh") String builtinZh, @Param("builtinEn") String builtinEn,
                   @Param("builtinVi") String builtinVi, @Param("legacyText") String legacyText,
                   @Param("now") LocalDateTime now);

    /** BUG-519: the customer's effective interface language, normalized by the caller. */
    @Select("SELECT language FROM nx_user WHERE id=#{userId} AND is_deleted=0")
    String userLanguage(@Param("userId") Long userId);

    @Select("SELECT conversation_no FROM nx_conversation WHERE user_id=#{customer} AND conversation_type='advisor' AND is_deleted=0 ORDER BY id DESC LIMIT 1")
    String latestAdvisorConversationNo(@Param("customer") Long customer);
}
