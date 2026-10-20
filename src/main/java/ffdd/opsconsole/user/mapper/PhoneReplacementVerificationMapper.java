package ffdd.opsconsole.user.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Immutable verification snapshots, no generic update/delete contract.
public interface PhoneReplacementVerificationMapper {
    record Row(String evidenceRef,long userId,String sourceEnvironment,String runId,String requestId,
               String targetInstallationId,long bindingVersion,String supportRef,long verifiedBy,long verifiedAt,
               String reason,String idempotencyKey,String requestHash) { }
    String COLUMNS="evidence_ref evidenceRef,user_id userId,source_environment sourceEnvironment,run_id runId,"
            +"request_id requestId,target_installation_id targetInstallationId,binding_version bindingVersion,"
            +"support_ref supportRef,verified_by verifiedBy,verified_at verifiedAt,reason,idempotency_key idempotencyKey,request_hash requestHash";
    @Select("SELECT "+COLUMNS+" FROM nx_phone_replacement_verification WHERE evidence_ref=#{ref}")
    Row find(String ref);
    @Select("SELECT "+COLUMNS+" FROM nx_phone_replacement_verification WHERE verified_by=#{actor} AND idempotency_key=#{key}")
    Row byKey(@Param("actor") long actor,@Param("key") String key);
    @Select("SELECT "+COLUMNS+" FROM nx_phone_replacement_verification WHERE verified_by=#{actor} AND idempotency_key=#{key} FOR UPDATE")
    Row currentByKey(@Param("actor") long actor,@Param("key") String key);
    @Insert("INSERT IGNORE INTO nx_phone_replacement_verification(evidence_ref,user_id,source_environment,run_id,request_id,"
            +"target_installation_id,binding_version,support_ref,verified_by,verified_at,reason,idempotency_key,request_hash) "
            +"VALUES(#{evidenceRef},#{userId},#{sourceEnvironment},#{runId},#{requestId},#{targetInstallationId},"
            +"#{bindingVersion},#{supportRef},#{verifiedBy},#{verifiedAt},#{reason},#{idempotencyKey},#{requestHash})")
    int insert(Row row);
}
