package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionProof;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.Map;
import ffdd.opsconsole.content.domain.SupportRules;

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

    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.CurrentCustomer> currentCustomers();
    @SelectProvider(type=SourceSql.class,method="sql") SupportAnalyticsMapper.ActivityCoverage activityCoverage();
    @SelectProvider(type=SourceSql.class,method="sql") SupportAnalyticsMapper.ActivityRules activityRules();
    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.ActivityRow> activityEvents(@Param("customerIds") Collection<Long> ids,@Param("observedThroughAt") LocalDateTime through);
    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.RootDisplayRow> rootDisplayRows(@Param("ids") Collection<Long> ids);
    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.CustomerTagRow> customerTagRows(@Param("ids") Collection<Long> ids);
    @SelectProvider(type=SourceSql.class,method="sql") SupportRules queryRules();
    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.ActivityIdentityRow> activityIdentityRows(@Param("ids") Collection<Long> ids,@Param("observedThroughAt") LocalDateTime through);
    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.TaskRow> taskRows(Map<String,Object> parameters);
    @SelectProvider(type=SourceSql.class,method="sql") List<SupportAnalyticsMapper.PendingReplyRow> pendingReplyRows(@Param("ids") Collection<Long> ids);

    /** Fixed internal all-production source projections. No HTTP input can choose a statement or remove a guard. */
    final class SourceSql {
        public static String sql(org.apache.ibatis.builder.annotation.ProviderContext context) {
            String name=context.getMapperMethod().getName();
            var method=java.util.Arrays.stream(SupportAnalyticsMapper.class.getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
            String sql=String.join(" ",method.getAnnotation(Select.class).value());
            return sql.replace(SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE,"").replace(SupportAnalyticsMapper.READ_SCOPE,"1=1");
        }
    }
}
