package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SupportUnboundReceiptMapper extends BaseMapper<Object> {
    /** Inserts the receipt at most once per customer thread (threads are reused, the App shows its own waiting hint); returns 0 when it was already sent. */
    @Insert("""
            INSERT INTO nx_conversation_message
              (conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at,updated_at,is_deleted)
            SELECT c.id,c.conversation_no,NULL,'system','系统',#{content},#{now},#{now},0
              FROM nx_conversation c
             WHERE c.conversation_no=#{conversationNo} AND c.is_deleted=0 AND c.status='OPEN'
               AND NOT EXISTS (SELECT 1 FROM nx_conversation_message m
                                WHERE m.conversation_no=#{conversationNo} AND m.is_deleted=0 AND m.sender_type='system' AND m.content=#{content})
            """)
    int insertOnce(@Param("conversationNo") String conversationNo, @Param("content") String content, @Param("now") LocalDateTime now);

    @Select("SELECT conversation_no FROM nx_conversation WHERE user_id=#{customer} AND conversation_type='advisor' AND is_deleted=0 ORDER BY id DESC LIMIT 1")
    String latestAdvisorConversationNo(@Param("customer") Long customer);
}
