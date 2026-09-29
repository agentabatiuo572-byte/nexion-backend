package ffdd.opsconsole.content.mapper;

import java.util.Map;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportHumanMessageEntity;
import org.apache.ibatis.annotations.*;

public interface SupportHumanMessageMapper extends BaseMapper<SupportHumanMessageEntity> {
    @Select("SELECT id FROM nx_support_activity_coverage WHERE id=1 FOR SHARE")
    Long captureFence();
    @Select("SELECT h.message_id messageId,h.customer_id customerId,h.payload_hash payloadHash,m.conversation_no conversationNo FROM nx_support_human_message h JOIN nx_conversation_message m ON m.id=h.message_id WHERE h.actor_type=#{type} AND h.actor_id=#{actor} AND h.client_message_id=#{client} FOR SHARE")
    Map<String,Object> find(@Param("type") String type,@Param("actor") Long actor,@Param("client") String client);

    @Select("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=#{no} AND is_deleted=0 AND sender_type IN ('user','agent')")
    Long latest(String no);

    @Insert("""
        INSERT INTO nx_support_human_message(message_id,customer_id,assignment_id,actor_type,actor_id,
            client_message_id,kind,intent,attachment_id,committed_at,payload_hash)
        VALUES(#{message},#{customer},#{assignment},#{type},#{actor},#{client},#{kind},#{intent},#{attachment},
            (SELECT GREATEST(UTC_TIMESTAMP(6),TIMESTAMPADD(MICROSECOND,1,observed_through_at)) FROM nx_support_activity_coverage WHERE id=1),#{hash})
        """)
    void insertMetadata(@Param("message") Long message,@Param("customer") Long customer,@Param("assignment") Long assignment,
        @Param("type") String type,@Param("actor") Long actor,@Param("client") String client,
        @Param("kind") String kind,@Param("intent") String intent,@Param("attachment") String attachment,@Param("hash") String hash);
}
