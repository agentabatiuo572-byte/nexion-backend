package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportAvatarAsset;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

// Statement-only asset/account references; no single entity exposes these guarded transitions as CRUD.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportAdminAvatarMapper {
    String FIELDS="id,uploader_id AS uploaderId,client_upload_id AS clientUploadId,idempotency_key AS idempotencyKey,request_hash AS requestHash,mime,byte_count AS byteCount,object_key AS objectKey,state,attached_admin_id AS attachedAdminId,expires_at AS expiresAt";
    @Select("SELECT "+FIELDS+" FROM nx_support_admin_avatar_asset WHERE id=#{id} FOR UPDATE")
    SupportAvatarAsset lock(String id);
    @Select("SELECT "+FIELDS+" FROM nx_support_admin_avatar_asset WHERE uploader_id=#{actor} AND (client_upload_id=#{client} OR idempotency_key=#{key}) FOR UPDATE")
    List<SupportAvatarAsset> prior(@Param("actor") Long actor,@Param("client") String client,@Param("key") String key);
    @Insert("INSERT INTO nx_support_admin_avatar_asset(id,uploader_id,client_upload_id,idempotency_key,request_hash,mime,byte_count,object_key,state,expires_at,created_at) VALUES(#{id},#{uploaderId},#{clientUploadId},#{idempotencyKey},#{requestHash},#{mime},#{byteCount},#{objectKey},'READY',#{expiresAt},UTC_TIMESTAMP(6))")
    void insertAsset(SupportAvatarAsset asset);
    @Update("UPDATE nx_support_admin_avatar_asset SET state='ATTACHED',attached_admin_id=#{admin} WHERE id=#{id} AND state='READY' AND expires_at>UTC_TIMESTAMP(6)")
    int attach(@Param("id") String id,@Param("admin") Long admin);
    @Update("UPDATE nx_support_admin_avatar_asset SET state='CANCELLED' WHERE id=#{id} AND state='READY'")
    int cancel(String id);
    @Insert("INSERT INTO nx_admin_account_state(admin_id,avatar_asset_id,avatar_version) VALUES(#{admin},#{id},1) ON DUPLICATE KEY UPDATE avatar_asset_id=#{id},avatar_version=avatar_version+1")
    int accountAvatar(@Param("admin") Long admin,@Param("id") String id);
    @Select("SELECT avatar_asset_id AS assetId,avatar_version AS version FROM nx_admin_account_state WHERE admin_id=#{admin} AND is_deleted=0")
    Map<String,Object> reference(Long admin);
    @Select("""
      SELECT a.id FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id AND p.is_deleted=0
      WHERE a.id=#{admin} AND a.is_deleted=0 FOR SHARE
      """)
    Long rosterAdminForShare(Long admin);
    @Select("""
      SELECT COUNT(*) FROM nx_user u WHERE u.id=#{customer} AND u.is_deleted=0 AND u.sandbox=0 AND (
       EXISTS(SELECT 1 FROM nx_support_agent_user_assignment s WHERE s.user_id=u.id AND s.agent_admin_id=#{admin} AND s.status='ACTIVE' AND s.is_deleted=0)
       OR EXISTS(SELECT 1 FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no AND m.conversation_id=c.id
        JOIN nx_support_human_message h ON h.message_id=m.id AND h.customer_id=u.id AND h.actor_type='ADMIN' AND h.actor_id=#{admin}
        WHERE c.user_id=u.id AND c.is_deleted=0 AND m.is_deleted=0 AND m.sender_type='agent' AND m.sender_id=h.actor_id))
      """)
    int appVisible(@Param("customer") Long customer,@Param("admin") Long admin);
}
