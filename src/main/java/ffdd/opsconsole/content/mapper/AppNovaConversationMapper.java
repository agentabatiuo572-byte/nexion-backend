package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppNovaConversationMapper extends BaseMapper<Object> {
    @Select("""
            SELECT turn_id turnId, conversation_id conversationId, language,
                   user_message userMessage, assistant_reply assistantReply,
                   provider, model,
                   CAST(UNIX_TIMESTAMP(created_at) * 1000 AS UNSIGNED) createdAtEpochMs
              FROM nx_nova_conversation_turn
             WHERE user_id=#{userId} AND turn_id=#{turnId}
             LIMIT 1
            """)
    TurnRow turn(@Param("userId") Long userId, @Param("turnId") String turnId);

    @Select("""
            SELECT conversation_id
              FROM nx_nova_conversation_turn
             WHERE user_id=#{userId}
             ORDER BY id DESC
             LIMIT 1
            """)
    String latestConversationId(@Param("userId") Long userId);

    @Select("""
            SELECT turn_id turnId, conversation_id conversationId, language,
                   user_message userMessage, assistant_reply assistantReply,
                   provider, model,
                   CAST(UNIX_TIMESTAMP(created_at) * 1000 AS UNSIGNED) createdAtEpochMs
              FROM nx_nova_conversation_turn
             WHERE user_id=#{userId} AND conversation_id=#{conversationId}
             ORDER BY id ASC
             LIMIT 200
            """)
    List<TurnRow> turns(@Param("userId") Long userId, @Param("conversationId") String conversationId);

    @Insert("""
            INSERT INTO nx_nova_conversation_turn (
              user_id, turn_id, conversation_id, language,
              user_message, assistant_reply, provider, model, created_at
            ) VALUES (
              #{userId}, #{turnId}, #{conversationId}, #{language},
              #{userMessage}, #{assistantReply}, #{provider}, #{model}, NOW(3)
            )
            """)
    int insertTurn(@Param("userId") Long userId,
                   @Param("turnId") String turnId,
                   @Param("conversationId") String conversationId,
                   @Param("language") String language,
                   @Param("userMessage") String userMessage,
                   @Param("assistantReply") String assistantReply,
                   @Param("provider") String provider,
                   @Param("model") String model);

    record TurnRow(String turnId, String conversationId, String language,
                   String userMessage, String assistantReply, String provider,
                   String model, Long createdAtEpochMs) { }
}
