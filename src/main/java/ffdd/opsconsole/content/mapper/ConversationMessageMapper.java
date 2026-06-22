package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.infrastructure.ConversationMessageEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
    @Select("""
            SELECT
              id,
              conversation_id AS conversationId,
              conversation_no AS conversationNo,
              sender_id AS senderId,
              sender_type AS senderType,
              sender_name AS senderName,
              content,
              created_at AS createdAt
            FROM nx_conversation_message
            WHERE is_deleted=0 AND conversation_no=#{conversationNo}
            ORDER BY created_at ASC,id ASC
            """)
    List<ContentConversationMessageView> listByConversationNo(@Param("conversationNo") String conversationNo);
}
