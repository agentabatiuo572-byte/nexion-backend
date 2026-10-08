package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportRandom.Recipient;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

// Preview, operation and composite-key result statements have no single BaseMapper CRUD entity.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportRandomMapper {
    @Insert("INSERT IGNORE INTO nx_support_random_operation(actor_id,operation_id,preview_id,request_hash,created_at,updated_at) VALUES(#{actor},#{key},#{preview},#{hash},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")
    int insertOperation(@Param("actor") Long actor,@Param("key") String key,@Param("preview") String preview,@Param("hash") String hash);
    @Select("SELECT preview_id previewId,request_hash requestHash,status FROM nx_support_random_operation WHERE actor_id=#{actor} AND operation_id=#{key}")
    Map<String,Object> loadOperation(@Param("actor") Long actor,@Param("key") String key);
    @Update("UPDATE nx_support_random_operation SET status='COMPLETED',updated_at=UTC_TIMESTAMP(6) WHERE actor_id=#{actor} AND operation_id=#{key}")
    int complete(@Param("actor") Long actor,@Param("key") String key);
    @Select("SELECT customer_id customerId,status,assignment_id assignmentId,agent_admin_id agentAdminId,outcome FROM nx_support_random_result WHERE actor_id=#{actor} AND operation_id=#{key} ORDER BY customer_id")
    List<Recipient> results(@Param("actor") Long actor,@Param("key") String key);
    @Insert("INSERT INTO nx_support_random_preview(id,actor_id,rules_version,customers_json,excluded_json,created_at,expires_at) VALUES(#{id},#{actor},#{version},#{customers},#{excluded},UTC_TIMESTAMP(6),#{expires})")
    int insertPreview(@Param("id") String id,@Param("actor") Long actor,@Param("version") Long version,
            @Param("customers") String customers,@Param("excluded") String excluded,@Param("expires") LocalDateTime expires);
    @Select("SELECT rules_version rulesVersion,customers_json customers,expires_at expiresAt,(expires_at>UTC_TIMESTAMP(6)) valid FROM nx_support_random_preview WHERE id=#{id} AND actor_id=#{actor}")
    Map<String,Object> loadPreview(@Param("id") String id,@Param("actor") Long actor);
    @Select("SELECT customer_id customerId,status,assignment_id assignmentId,agent_admin_id agentAdminId,outcome FROM nx_support_random_result WHERE actor_id=#{actor} AND operation_id=#{operation} AND customer_id=#{customer}")
    Recipient loadResult(@Param("actor") Long actor,@Param("operation") String operation,@Param("customer") Long customer);
    @Insert("INSERT INTO nx_support_random_result(operation_id,actor_id,preview_id,customer_id,status,assignment_id,agent_admin_id,outcome,created_at) VALUES(#{operation},#{actor},#{preview},#{result.customerId},#{result.status},#{result.assignmentId},#{result.agentAdminId},#{result.outcome},UTC_TIMESTAMP(6))")
    int insertResult(@Param("actor") Long actor,@Param("operation") String operation,@Param("preview") String preview,@Param("result") Recipient result);
    @Select("<script>SELECT p.customer_id id,p.version poolVersion,p.reason FROM nx_support_binding_pool p JOIN nx_user u ON u.id=p.customer_id AND u.is_deleted=0 WHERE 1=1 <if test='reason!=null'>AND p.reason=#{reason}</if> <if test='keyword!=null'>AND (u.nickname LIKE CONCAT('%',#{keyword},'%') OR CAST(u.id AS CHAR)=#{keyword})</if> ORDER BY p.customer_id</script>")
    List<Map<String,Object>> pool(@Param("keyword") String keyword,@Param("reason") String reason);
}
