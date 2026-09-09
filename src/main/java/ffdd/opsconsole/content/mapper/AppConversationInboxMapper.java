package ffdd.opsconsole.content.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Personal inbox preference; never mutates a conversation, message or read receipt. */
@Mapper
// Statement-only SQL: the dismissal key is (user_id, conversation_no), and its
// monotonic message boundary is not a single-id BaseMapper CRUD entity.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppConversationInboxMapper {
    @Select("""
            SELECT conversation_no AS conversationNo, through_message_id AS throughMessageId
              FROM nx_app_conversation_dismissal WHERE user_id=#{userId}
            """)
    List<Dismissal> list(@Param("userId") Long userId);

    @Select("""
            SELECT conversation_no AS conversationNo, through_message_id AS throughMessageId
              FROM nx_app_conversation_dismissal WHERE user_id=#{userId} AND conversation_no=#{conversationNo}
            """)
    Dismissal find(@Param("userId") Long userId, @Param("conversationNo") String conversationNo);

    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
               WHERE c.user_id=#{userId} AND c.conversation_no=#{conversationNo} AND c.is_deleted=0
                 AND c.conversation_type IN ('advisor','support')
                 AND m.id=#{throughMessageId} AND m.is_deleted=0 AND m.sender_type IN ('user','agent'))
            """)
    boolean publicMessageExists(@Param("userId") Long userId, @Param("conversationNo") String conversationNo,
                                @Param("throughMessageId") Long throughMessageId);

    @Insert("""
            INSERT INTO nx_app_conversation_dismissal (user_id, conversation_no, through_message_id, updated_at)
            SELECT c.user_id, c.conversation_no, m.id, NOW(3)
              FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
             WHERE c.user_id=#{userId} AND c.conversation_no=#{conversationNo} AND c.is_deleted=0
               AND c.conversation_type IN ('advisor','support')
               AND m.id=#{throughMessageId} AND m.is_deleted=0 AND m.sender_type IN ('user','agent')
            ON DUPLICATE KEY UPDATE
              updated_at=IF(VALUES(through_message_id)>through_message_id,NOW(3),nx_app_conversation_dismissal.updated_at),
              through_message_id=GREATEST(through_message_id, VALUES(through_message_id))
            """)
    int dismiss(@Param("userId") Long userId, @Param("conversationNo") String conversationNo,
                @Param("throughMessageId") Long throughMessageId);

    record Dismissal(String conversationNo, Long throughMessageId) {}
}
