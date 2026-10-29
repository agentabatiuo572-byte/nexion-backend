package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionProof;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Select;

/** Internal fact sampler only. Private HTTP reads must independently apply their current authorized scope. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportAnalyticsFactMapper {
    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime nowUtc();

    // First-payment history includes former/deleted production customers.
    @Select("SELECT id FROM nx_user WHERE sandbox=0 ORDER BY id")
    List<Long> productionCustomers();

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
}
