package ffdd.opsconsole.device.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper") // Append-only command projection, without a generic CRUD entity.
public interface PhoneReplacementPolicyOperationMapper {
    record Row(String operationId, long actorId, String commandType, String idempotencyKey,
               String requestHash, String permissionCode, String status, long submittedAt, Long completedAt,
               String originalResultJson, String auditRef) { }
    String COLUMNS = "operation_id operationId,actor_id actorId,command_type commandType,idempotency_key idempotencyKey,"
            + "request_hash requestHash,permission_code permissionCode,status,submitted_at submittedAt,completed_at completedAt,"
            + "original_result_json originalResultJson,audit_ref auditRef";
    @Select("SELECT " + COLUMNS + " FROM nx_phone_policy_operation WHERE operation_id=#{id}")
    Row find(String id);
    @Select("SELECT " + COLUMNS + " FROM nx_phone_policy_operation WHERE actor_id=#{actor} AND command_type=#{type} AND idempotency_key=#{key}")
    Row findByKey(@Param("actor") long actor, @Param("type") String type, @Param("key") String key);
    @Select("SELECT " + COLUMNS + " FROM nx_phone_policy_operation WHERE actor_id=#{actor} AND command_type=#{type} AND idempotency_key=#{key} FOR UPDATE")
    Row lockByKey(@Param("actor") long actor, @Param("type") String type, @Param("key") String key);
    @Insert("INSERT INTO nx_phone_policy_operation(operation_id,actor_id,command_type,idempotency_key,request_hash,permission_code,status,submitted_at) "
            + "VALUES(#{operationId},#{actorId},#{commandType},#{idempotencyKey},#{requestHash},#{permissionCode},'PENDING',#{submittedAt})")
    int insert(Row row);
    @Update("UPDATE nx_phone_policy_operation SET status='SUCCEEDED',completed_at=#{at},original_result_json=#{result},audit_ref=#{audit} WHERE operation_id=#{id} AND status='PENDING'")
    int complete(@Param("id") String id, @Param("at") long at, @Param("result") String result, @Param("audit") String audit);
}
