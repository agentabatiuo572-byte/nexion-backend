package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SupportTicketNoticeMapper {
    @Select("SELECT conversation_no FROM nx_conversation WHERE user_id=#{customer} AND conversation_type='advisor' AND is_deleted=0 ORDER BY id DESC LIMIT 1")
    String latestAdvisorConversationNo(@Param("customer") Long customer);

    /** Appends to the segment whether or not it has ended; skips when the newest message is the same notice. */
    @Insert("""
            INSERT INTO nx_conversation_message
              (conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at,updated_at,is_deleted)
            SELECT c.id,c.conversation_no,NULL,'system','系统',#{content},#{now},#{now},0
              FROM nx_conversation c
             WHERE c.conversation_no=#{conversationNo} AND c.is_deleted=0
               AND NOT EXISTS (SELECT 1 FROM (SELECT sender_type,content FROM nx_conversation_message
                                               WHERE conversation_no=#{conversationNo} AND is_deleted=0
                                               ORDER BY id DESC LIMIT 1) newest
                                WHERE newest.sender_type='system' AND newest.content=#{content})
            """)
    int insertNotice(@Param("conversationNo") String conversationNo, @Param("content") String content, @Param("now") LocalDateTime now);
}
