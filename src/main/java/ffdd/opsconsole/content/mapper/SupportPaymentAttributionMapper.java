package ffdd.opsconsole.content.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Dedicated immutable projection and single-table observations under explicit anchors. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPaymentAttributionMapper {
    String MEMBER_COLUMNS="id,agent_admin_id agentAdminId,group_id groupId,starts_at startsAt,ends_at endsAt,version,operation_id operationId";
    String GROUP_COLUMNS="id,supervisor_admin_id supervisorAdminId,status,version";
    String EVIDENCE_COLUMNS="fact_id factId,customer_id customerId,kind,source,ledger_id ledgerId,source_business_id sourceBusinessId,order_no orderNo,order_type orderType,original_fact_id originalFactId,currency,amount,succeeded_at succeededAt,source_business_zone sourceBusinessZone,success_time_field successTimeField,fractional_second_digits fractionalSecondDigits,source_partition sourcePartition,capture_db_utc captureDbUtc,agent_admin_id agentAdminId,group_id groupId,owner_admin_id ownerAdminId,agent_status agentStatus,group_status groupStatus,owner_status ownerStatus,capture_mode captureMode,source_fact_json sourceFactJson,attribution_evidence_json evidenceJson";

    @Select("SELECT id,sandbox,status,is_deleted isDeleted FROM nx_user WHERE id=#{id} FOR UPDATE")
    Customer lockCustomer(@Param("id") long id);
    @Select("SELECT id,user_id userId,agent_admin_id agentAdminId,status,starts_at startsAt,ends_at endsAt,is_deleted isDeleted,version,source,operation_id operationId,segment_root_id segmentRootId,depth,parent_assignment_id parentAssignmentId,rule_version ruleVersion FROM nx_support_agent_user_assignment WHERE user_id=#{id} ORDER BY id FOR SHARE")
    List<Binding> assignments(@Param("id") long customer);
    @Select("SELECT id,customer_id customerId,group_id groupId,starts_at startsAt,ends_at endsAt,version,operation_id operationId FROM nx_support_customer_route_history WHERE customer_id=#{id} ORDER BY id FOR SHARE")
    List<Route> routes(@Param("id") long customer);
    // RR snapshots plan anchors only. They never become attribution evidence.
    @Select("SELECT "+MEMBER_COLUMNS+" FROM nx_support_group_member_history WHERE agent_admin_id=#{id} AND ends_at IS NULL ORDER BY id")
    List<Member> planMembers(@Param("id") long agent);
    @Select("SELECT "+GROUP_COLUMNS+" FROM nx_support_group WHERE id=#{id}")
    Group planGroup(@Param("id") long group);
    @Select("SELECT id,status,version,is_deleted isDeleted FROM nx_admin WHERE id=#{id} FOR SHARE")
    Admin lockAdmin(@Param("id") long id);
    @Select("SELECT "+GROUP_COLUMNS+" FROM nx_support_group WHERE id=#{id} FOR SHARE")
    Group lockGroup(@Param("id") long id);
    @Select("SELECT "+MEMBER_COLUMNS+" FROM nx_support_group_member_history WHERE agent_admin_id=#{id} ORDER BY id FOR SHARE")
    List<Member> members(@Param("id") long agent);
    @Select("SELECT id,group_id groupId,supervisor_admin_id supervisorAdminId,starts_at startsAt,ends_at endsAt,version,operation_id operationId FROM nx_support_group_owner_history WHERE group_id=#{id} ORDER BY id FOR SHARE")
    List<Owner> owners(@Param("id") long group);
    @Select("SELECT id,admin_id adminId,qualification_kind qualificationKind,state,starts_at startsAt,ends_at endsAt,version,operation_id operationId FROM nx_support_account_qualification_history WHERE admin_id=#{id} ORDER BY qualification_kind,id FOR SHARE")
    List<Qualification> qualifications(@Param("id") long admin);
    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime databaseUtc();
    @Select("SELECT "+EVIDENCE_COLUMNS+" FROM nx_support_payment_attribution WHERE fact_id=#{factId} FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    StoredRow findEvidence(@Param("factId") String factId);
    @Insert("""
        INSERT INTO nx_support_payment_attribution
        (fact_id,customer_id,kind,source,ledger_id,source_business_id,order_no,order_type,original_fact_id,
         currency,amount,succeeded_at,source_business_zone,success_time_field,fractional_second_digits,source_partition,capture_db_utc,
         agent_admin_id,group_id,owner_admin_id,agent_status,group_status,owner_status,capture_mode,
         capture_schema_version,source_fact_json,attribution_evidence_json)
        VALUES (#{factId},#{customerId},#{kind},#{source},#{ledgerId},#{sourceBusinessId},#{orderNo},#{orderType},#{originalFactId},
         #{currency},#{amount},#{succeededAt},#{sourceBusinessZone},#{successTimeField},#{fractionalSecondDigits},#{sourcePartition},#{captureDbUtc},
         #{agentAdminId},#{groupId},#{ownerAdminId},#{agentStatus},#{groupStatus},#{ownerStatus},#{captureMode},
         'support-payment-attribution-v1',#{sourceFactJson},#{evidenceJson})
        """)
    int insert(StoredRow row);

    record Customer(Long id,Integer sandbox,String status,Integer isDeleted) { }
    record Admin(Long id,Integer status,Long version,Integer isDeleted) { }
    record Group(Long id,Long supervisorAdminId,String status,Long version) { }
    record Binding(Long id,Long userId,Long agentAdminId,String status,LocalDateTime startsAt,LocalDateTime endsAt,
        Integer isDeleted,Long version,String source,String operationId,Long segmentRootId,Integer depth,Long parentAssignmentId,Long ruleVersion) { }
    record Member(Long id,Long agentAdminId,Long groupId,LocalDateTime startsAt,LocalDateTime endsAt,Long version,String operationId) { }
    record Route(Long id,Long customerId,Long groupId,LocalDateTime startsAt,LocalDateTime endsAt,Long version,String operationId) { }
    record Owner(Long id,Long groupId,Long supervisorAdminId,LocalDateTime startsAt,LocalDateTime endsAt,Long version,String operationId) { }
    record Qualification(Long id,Long adminId,String qualificationKind,String state,LocalDateTime startsAt,LocalDateTime endsAt,Long version,String operationId) { }
    record StoredRow(String factId,long customerId,String kind,String source,long ledgerId,String sourceBusinessId,
        String orderNo,String orderType,String originalFactId,String currency,BigDecimal amount,LocalDateTime succeededAt,
        String sourceBusinessZone,String successTimeField,int fractionalSecondDigits,String sourcePartition,LocalDateTime captureDbUtc,
        Long agentAdminId,Long groupId,Long ownerAdminId,String agentStatus,String groupStatus,String ownerStatus,
        String captureMode,String sourceFactJson,String evidenceJson) { }
}
