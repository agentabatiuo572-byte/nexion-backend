package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportAttachmentEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SupportAttachmentMapper extends BaseMapper<SupportAttachmentEntity> {
    String COLUMNS = "id,customer_id customerId,uploader_type uploaderType,uploader_id uploaderId,assignment_id assignmentId,client_upload_id clientUploadId,request_hash requestHash,mime,bytes,width,height,object_key objectKey,state,expires_at expiresAt,message_id messageId";

    @Select("SELECT customer_id FROM nx_support_attachment WHERE id=#{id}")
    Long customer(String id);

    @Select("SELECT " + COLUMNS + " FROM nx_support_attachment WHERE id=#{id} FOR UPDATE")
    SupportAttachment find(String id);

    @Select("SELECT " + COLUMNS + " FROM nx_support_attachment WHERE uploader_type=#{type} AND uploader_id=#{actor} AND client_upload_id=#{client} FOR UPDATE")
    SupportAttachment findUpload(@Param("type") String type, @Param("actor") Long actor, @Param("client") String client);

    @Insert("""
        INSERT INTO nx_support_attachment(id,customer_id,uploader_type,uploader_id,assignment_id,client_upload_id,
          request_hash,mime,bytes,width,height,object_key,state,expires_at)
        VALUES(#{id},#{customerId},#{uploaderType},#{uploaderId},#{assignmentId},#{clientUploadId},#{requestHash},
          #{mime},#{bytes},#{width},#{height},#{objectKey},#{state},#{expiresAt})
        """)
    int insertAttachment(SupportAttachment row);

    @Update("UPDATE nx_support_attachment SET state='ATTACHED',message_id=#{message} WHERE id=#{id} AND state='READY' AND expires_at>UTC_TIMESTAMP(6)")
    int attach(@Param("id") String id, @Param("message") Long message);

    @Update("UPDATE nx_support_attachment SET state=#{state} WHERE id=#{id} AND state='READY'")
    int retire(@Param("id") String id, @Param("state") String state);

    @Select("SELECT id FROM nx_support_attachment WHERE state='READY' AND expires_at<=UTC_TIMESTAMP(6) ORDER BY customer_id,id LIMIT 100")
    List<String> expired();

    @Select("SELECT COUNT(*) FROM nx_support_attachment WHERE object_key=#{key} AND (state='ATTACHED' OR (state='READY' AND expires_at>UTC_TIMESTAMP(6)))")
    long liveObjectReferences(String key);

    @Select("SELECT attachment_id FROM nx_support_attachment_command WHERE actor_type=#{type} AND actor_id=#{actor} AND operation=#{operation} AND command_key=#{key}")
    String command(@Param("type") String type, @Param("actor") Long actor, @Param("operation") String operation, @Param("key") String key);

    @Insert("INSERT INTO nx_support_attachment_command(actor_type,actor_id,operation,command_key,attachment_id) VALUES(#{type},#{actor},#{operation},#{key},#{id})")
    int commandInsert(@Param("type") String type, @Param("actor") Long actor, @Param("operation") String operation, @Param("key") String key, @Param("id") String id);
}
