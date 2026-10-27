package ffdd.opsconsole.content.mapper;

/** Shared only by APP projections. Management queries deliberately do not use this predicate. */
public final class AppConversationVisibilitySql {
    private AppConversationVisibilitySql() {}
    public static final String PEER = "CASE WHEN NULLIF(TRIM(c.owner_agent_id),'') IS NULL "
            + "THEN CONCAT('conversation:',c.conversation_no) ELSE CONCAT('agent:',c.owner_agent_id) END";
    public static final String THROUGH = "GREATEST(COALESCE((SELECT clear_peer.through_message_id "
            + "FROM nx_app_conversation_user_clear clear_peer WHERE clear_peer.user_id=c.user_id "
            + "AND clear_peer.peer_key=" + PEER + "),0),COALESCE((SELECT MAX(clear_cid.through_message_id) "
            + "FROM nx_app_conversation_user_clear_member clear_cid WHERE clear_cid.user_id=c.user_id "
            + "AND clear_cid.conversation_no=c.conversation_no),0))";
    public static final String CONVERSATION_THROUGH = "COALESCE((SELECT " + THROUGH
            + " FROM nx_conversation c WHERE c.conversation_no=#{conversationNo} AND c.is_deleted=0),0)";
    // A cleared empty conversation has a real boundary of zero, not an absent marker.
    public static final String CLEARED = "(EXISTS(SELECT 1 FROM nx_app_conversation_user_clear clear_peer "
            + "WHERE clear_peer.user_id=c.user_id AND clear_peer.peer_key=" + PEER + ") OR EXISTS(SELECT 1 "
            + "FROM nx_app_conversation_user_clear_member clear_cid WHERE clear_cid.user_id=c.user_id "
            + "AND clear_cid.conversation_no=c.conversation_no))";
}
