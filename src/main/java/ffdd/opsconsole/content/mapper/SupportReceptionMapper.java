package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

@SuppressWarnings("MybatisPlusBaseMapper") // Session and configuration rows have separate identities and guarded writes.
public interface SupportReceptionMapper {
    record Hold(Long customerId,Long assignmentId,Long adminId,String name,String role,String conversationNo,
                LocalDateTime startedAt,LocalDateTime lastSentAt,LocalDateTime autoEndAt,Integer endMinutes,Boolean active) {}
    String HOLD = """
        SELECT h.customer_id customerId,h.assignment_id assignmentId,h.holder_admin_id adminId,h.holder_name name,
          h.holder_role role,h.conversation_no conversationNo,h.started_at startedAt,h.last_sent_at lastSentAt,
          TIMESTAMPADD(MINUTE,p.session_end_minutes,h.last_sent_at) autoEndAt,p.session_end_minutes endMinutes,
          (h.ended_at IS NULL AND TIMESTAMPADD(MINUTE,p.session_end_minutes,h.last_sent_at)>UTC_TIMESTAMP(6)) active
        FROM nx_support_session_hold h JOIN nx_conversation_timeout_policy p ON p.policy_key='GLOBAL'
        """;
    @Select(HOLD + " WHERE h.customer_id=#{customer} FOR SHARE")
    Hold hold(long customer);

    @Select("<script>"+HOLD+" WHERE h.customer_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND h.ended_at IS NULL</script>")
    List<Hold> holds(@Param("ids") Collection<Long> ids);

    @Select("SELECT session_end_minutes FROM nx_conversation_timeout_policy WHERE policy_key='GLOBAL'")
    int endMinutes();

    @Select("""
        SELECT h.customer_id FROM nx_support_session_hold h JOIN nx_conversation_timeout_policy p ON p.policy_key='GLOBAL'
         WHERE h.ended_at IS NULL AND h.last_sent_at<=TIMESTAMPADD(MINUTE,-p.session_end_minutes,UTC_TIMESTAMP(6))
         ORDER BY h.last_sent_at LIMIT 100
        """)
    List<Long> due();

    @Insert("""
        INSERT INTO nx_support_session_hold(customer_id,assignment_id,holder_admin_id,holder_name,holder_role,
          conversation_no,started_at,last_sent_at,version)
        VALUES(#{customer},#{assignment},#{actor},#{name},#{role},#{no},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1)
        ON DUPLICATE KEY UPDATE started_at=IF(ended_at IS NULL,started_at,VALUES(started_at)),assignment_id=VALUES(assignment_id),
          holder_admin_id=VALUES(holder_admin_id),holder_name=VALUES(holder_name),holder_role=VALUES(holder_role),
          conversation_no=VALUES(conversation_no),last_sent_at=VALUES(last_sent_at),ended_at=NULL,end_reason=NULL,version=version+1
        """)
    void receive(@Param("customer") long customer,@Param("assignment") long assignment,@Param("actor") long actor,
                 @Param("name") String name,@Param("role") String role,@Param("no") String no);

    @Update("UPDATE nx_support_session_hold SET ended_at=COALESCE(#{at},UTC_TIMESTAMP(6)),end_reason=#{reason},version=version+1 WHERE customer_id=#{customer} AND ended_at IS NULL")
    int end(@Param("customer") long customer,@Param("at") LocalDateTime at,@Param("reason") String reason);

    @Select("""
        SELECT m.sender_id FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
        LEFT JOIN nx_support_human_message hm ON hm.message_id=m.id
        WHERE c.user_id=#{customer} AND c.is_deleted=0 AND c.conversation_type IN ('advisor','support')
          AND m.is_deleted=0 AND m.sender_type='agent' AND m.sender_id>0 AND m.origin IS NULL
          AND (hm.client_message_id IS NULL OR LEFT(hm.client_message_id,5)<>'bulk_')
        ORDER BY m.id DESC LIMIT 1 FOR SHARE
        """)
    Long lastHumanSender(long customer);

    @Insert("""
        INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_type,sender_name,content,support_kind,sender_role,created_at,updated_at)
        VALUES(#{id},#{no},'system',#{name},'客服已切换','AGENT_SWITCH',#{role},#{now},#{now})
        """)
    void agentSwitch(@Param("id") long id,@Param("no") String no,@Param("name") String name,
                     @Param("role") String role,@Param("now") LocalDateTime now);

    record Welcome(Long version,String zh,String en,String vi,LocalDateTime updatedAt,String updatedBy,String reason) {}
    @Select("SELECT version,zh,en,vi,updated_at updatedAt,updated_by updatedBy,reason FROM nx_support_welcome_config WHERE id=1")
    Welcome welcome();
    @Update("""
        UPDATE nx_support_welcome_config SET zh=#{zh},en=#{en},vi=#{vi},updated_at=UTC_TIMESTAMP(6),
          updated_by=#{actor},reason=#{reason},version=version+1 WHERE id=1 AND version=#{version}
        """)
    int saveWelcome(@Param("version") long version,@Param("zh") String zh,@Param("en") String en,@Param("vi") String vi,
                    @Param("actor") String actor,@Param("reason") String reason);

    @Select("SELECT id FROM nx_conversation WHERE conversation_no=#{no} FOR SHARE")
    Long existingWelcome(String no);
    @Insert("""
        INSERT INTO nx_conversation(conversation_no,user_id,conversation_type,status,owner_agent_id,owner_agent_name,
          unread_count,last_message,last_message_at,version,created_at,updated_at,is_deleted)
        VALUES(#{no},#{customer},'advisor','OPEN',#{actor},#{name},0,#{body},#{at},0,#{at},#{at},0)
        """)
    void welcomeConversation(@Param("no") String no,@Param("customer") long customer,@Param("actor") Long actor,
                             @Param("name") String name,@Param("body") String body,@Param("at") LocalDateTime at);
    @Insert("""
        INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content,origin,created_at,updated_at)
        SELECT id,conversation_no,#{actor},'agent',#{name},#{body},'WELCOME',#{at},#{at} FROM nx_conversation WHERE conversation_no=#{no}
        """)
    void welcomeMessage(@Param("no") String no,@Param("actor") Long actor,@Param("name") String name,@Param("body") String body,@Param("at") LocalDateTime at);
}
