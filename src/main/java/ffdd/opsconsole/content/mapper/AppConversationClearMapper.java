package ffdd.opsconsole.content.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

public interface AppConversationClearMapper {
    record PeerRow(String conversationNo, String peerKey, String ownerAgentId, String ownerAgentName,
            Long version, long throughMessageId, long lastMessageId, boolean userScopeCleared, boolean archived) {}
    record Marker(String peerKey, long throughMessageId) {}

    String PEER_COLUMNS = "c.conversation_no AS conversationNo," + AppConversationVisibilitySql.PEER
            + " AS peerKey,c.owner_agent_id AS ownerAgentId,c.owner_agent_name AS ownerAgentName,c.version,"
            + AppConversationVisibilitySql.THROUGH + " AS throughMessageId,COALESCE((SELECT MAX(m.id) "
            + "FROM nx_conversation_message m WHERE m.conversation_no=c.conversation_no AND m.is_deleted=0 "
            + "AND m.sender_type IN ('user','agent')),0) AS lastMessageId,"
            + AppConversationVisibilitySql.CLEARED + " AS userScopeCleared,c.archived";

    @Select("SELECT " + PEER_COLUMNS + " FROM nx_conversation c WHERE c.user_id=#{userId} "
            + "AND c.is_deleted=0 AND c.conversation_no=#{conversationNo}")
    PeerRow peer(@Param("userId") Long userId, @Param("conversationNo") String conversationNo);

    @Select("SELECT " + PEER_COLUMNS + " FROM nx_conversation c WHERE c.user_id=#{userId} AND c.is_deleted=0 "
            + "AND c.conversation_type IN ('advisor','support') AND " + AppConversationVisibilitySql.PEER
            + "=#{peerKey} ORDER BY c.id FOR UPDATE")
    List<PeerRow> lockPeer(@Param("userId") Long userId, @Param("peerKey") String peerKey);

    @Select("SELECT peer_key AS peerKey,through_message_id AS throughMessageId "
            + "FROM nx_app_conversation_user_clear WHERE user_id=#{userId} ORDER BY peer_key")
    List<Marker> markers(@Param("userId") Long userId);

    @Select("SELECT conversation_no FROM nx_app_conversation_user_clear_member "
            + "WHERE user_id=#{userId} AND peer_key=#{peerKey} ORDER BY conversation_no")
    List<String> clearedConversations(@Param("userId") Long userId, @Param("peerKey") String peerKey);

    @Insert("INSERT INTO nx_app_conversation_user_clear(user_id,peer_key,through_message_id) "
            + "VALUES(#{userId},#{peerKey},#{throughMessageId}) ON DUPLICATE KEY UPDATE "
            + "through_message_id=GREATEST(through_message_id,VALUES(through_message_id))")
    int advancePeer(@Param("userId") Long userId, @Param("peerKey") String peerKey, @Param("throughMessageId") long throughMessageId);

    @Insert("INSERT INTO nx_app_conversation_user_clear_member(user_id,peer_key,conversation_no,through_message_id) "
            + "VALUES(#{userId},#{peerKey},#{conversationNo},#{throughMessageId}) ON DUPLICATE KEY UPDATE "
            + "through_message_id=GREATEST(through_message_id,VALUES(through_message_id))")
    int advanceMember(@Param("userId") Long userId, @Param("peerKey") String peerKey,
            @Param("conversationNo") String conversationNo, @Param("throughMessageId") long throughMessageId);

    String APP_FILTER = " FROM nx_conversation c WHERE c.user_id=#{userId} AND c.is_deleted=0 "
            + "AND c.conversation_type IN ('advisor','support') AND c.archived=0 "
            + "<if test='status != null and status != &quot;&quot;'>AND c.status=UPPER(#{status})</if> "
            + "AND (NOT " + AppConversationVisibilitySql.CLEARED + " OR EXISTS (SELECT 1 FROM nx_conversation_message visible "
            + "WHERE visible.conversation_no=c.conversation_no AND visible.is_deleted=0 "
            + "AND visible.sender_type IN ('user','agent') AND visible.id &gt; " + AppConversationVisibilitySql.THROUGH + ")) ";
    @Select("<script>SELECT COUNT(*)" + APP_FILTER + "</script>")
    long countVisible(@Param("userId") Long userId, @Param("status") String status);

    @Select("<script>SELECT c.conversation_no" + APP_FILTER
            + "<if test='beforeId != null'>AND c.id &lt; #{beforeId}</if> "
            + "<choose><when test='stable'>ORDER BY c.id DESC</when>"
            + "<otherwise>ORDER BY COALESCE(c.last_message_at,c.updated_at,c.created_at) DESC,c.id DESC</otherwise></choose> "
            + "LIMIT #{limit} OFFSET #{offset}</script>")
    List<String> visiblePage(@Param("userId") Long userId, @Param("status") String status,
            @Param("beforeId") Long beforeId, @Param("stable") boolean stable, @Param("limit") long limit, @Param("offset") long offset);
}
