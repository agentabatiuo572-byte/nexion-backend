package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Select;

/** Internal public-aggregate source only; caller must authorize before entry. No private ReadScope bypass or writer. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportLeaderboardMapper {
    @Select("SELECT UTC_TIMESTAMP(6)") LocalDateTime nowUtc();
    @Select("""
        SELECT a.id,a.nickname,a.status,a.is_deleted isDeleted,a.version,
          p.enabled profileEnabled,p.is_deleted profileDeleted,p.version profileVersion,p.seat_type profileSeatType,
          s.avatar_asset_id avatarAssetId,s.avatar_version avatarVersion,
          v.state avatarState,v.attached_admin_id avatarAdminId,
          EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
            WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1
              AND r.role_code IN ('SUPPORT','SUPER_ADMIN')) compatibleRole
        FROM nx_admin a LEFT JOIN nx_support_agent_profile p ON p.admin_id=a.id
          LEFT JOIN nx_admin_account_state s ON s.admin_id=a.id AND s.is_deleted=0
          LEFT JOIN nx_support_admin_avatar_asset v ON v.id=s.avatar_asset_id
        ORDER BY a.id
        """)
    List<Account> accounts();
    @Select("""
        SELECT id,admin_id agentId,state,version,starts_at startsAt,ends_at endsAt
        FROM nx_support_account_qualification_history WHERE qualification_kind='SERVICE' ORDER BY admin_id,starts_at,id
        """)
    List<QualificationInterval> qualifications();
    @Select("""
        SELECT id,agent_admin_id agentId,group_id groupId,version,starts_at startsAt,ends_at endsAt
        FROM nx_support_group_member_history ORDER BY agent_admin_id,starts_at,id
        """)
    List<MemberInterval> memberships();
    @Select("SELECT id,name,status,supervisor_admin_id supervisorId,version,created_at createdAt,updated_at updatedAt FROM nx_support_group ORDER BY id")
    List<Group> groups();
    @Select("""
        SELECT a.id,a.user_id customerId,a.agent_admin_id agentId,a.version,a.starts_at startsAt,a.ends_at endsAt
        FROM nx_support_agent_user_assignment a JOIN nx_user u ON u.id=a.user_id
        WHERE a.status='ACTIVE' AND a.is_deleted=0 AND u.sandbox=0 AND u.is_deleted=0 ORDER BY a.user_id,a.id
        """)
    List<Binding> currentBindings();
    // Lifetime first selection includes former/deleted customers; current binding is a different set.
    @Select("SELECT id FROM nx_user WHERE sandbox=0 ORDER BY id") List<Long> productionCustomers();
    @Select("""
        SELECT e.fact_id factId,e.customer_id customerId,e.kind,e.source,e.ledger_id ledgerId,
          e.source_business_id sourceBusinessId,e.order_no orderNo,e.order_type orderType,e.original_fact_id originalFactId,
          e.currency,e.amount,e.succeeded_at succeededAt,e.source_business_zone sourceBusinessZone,
          e.success_time_field successTimeField,e.fractional_second_digits fractionalSecondDigits,
          e.capture_mode captureMode,e.capture_schema_version captureSchemaVersion,
          e.agent_admin_id agentAdminId,e.group_id groupId,e.owner_admin_id ownerAdminId,
          e.agent_status agentStatus,e.group_status groupStatus,e.owner_status ownerStatus
        FROM nx_support_payment_attribution e JOIN nx_user u ON u.id=e.customer_id
        WHERE u.sandbox=0 ORDER BY e.fact_id
        """)
    List<AttributionRow> attributions();
    @Select("""
        SELECT e.fact_id factId,e.source_partition sourcePartition,e.capture_db_utc captureDbUtc,
          e.source_fact_json sourceFactJson,e.attribution_evidence_json attributionEvidenceJson
        FROM nx_support_payment_attribution e JOIN nx_user u ON u.id=e.customer_id
        WHERE u.sandbox=0 ORDER BY e.fact_id
        """)
    List<AttributionProof> attributionProofs();

    record Account(Long id,String nickname,Integer status,Integer isDeleted,Long version,
                   Integer profileEnabled,Integer profileDeleted,Long profileVersion,String profileSeatType,
                   String avatarAssetId,Long avatarVersion,String avatarState,Long avatarAdminId,Integer compatibleRole) { }
    record QualificationInterval(Long id,Long agentId,String state,Long version,LocalDateTime startsAt,LocalDateTime endsAt) { }
    record MemberInterval(Long id,Long agentId,Long groupId,Long version,LocalDateTime startsAt,LocalDateTime endsAt) { }
    record Group(Long id,String name,String status,Long supervisorId,Long version,LocalDateTime createdAt,LocalDateTime updatedAt) { }
    record Binding(Long id,Long customerId,Long agentId,Long version,LocalDateTime startsAt,LocalDateTime endsAt) { }
    record AttributionProof(String factId,String sourcePartition,LocalDateTime captureDbUtc,String sourceFactJson,String attributionEvidenceJson) { }
}
