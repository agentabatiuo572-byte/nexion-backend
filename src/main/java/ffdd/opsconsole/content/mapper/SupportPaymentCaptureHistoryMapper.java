package ffdd.opsconsole.content.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Fixed financial projection; missing and malformed proof remains visible to its reader. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPaymentCaptureHistoryMapper {
    @Select("""
        <script>SELECT fact_id factId,customer_id customerId,kind,source,ledger_id ledgerId,
        source_business_id sourceBusinessId,order_no orderNo,order_type orderType,original_fact_id originalFactId,
        currency,amount,succeeded_at succeededAt,source_business_zone sourceBusinessZone,
        success_time_field successTimeField,fractional_second_digits fractionalSecondDigits,
        source_partition sourcePartition,capture_db_utc captureDbUtc,capture_mode captureMode,
        capture_schema_version captureSchemaVersion,source_fact_json sourceFactJson,
        JSON_EXTRACT(attribution_evidence_json,'$.beforeSource') beforeSourceJson,
        JSON_UNQUOTE(JSON_EXTRACT(attribution_evidence_json,'$.captureMode')) evidenceCaptureMode,
        JSON_UNQUOTE(JSON_EXTRACT(attribution_evidence_json,'$.schemaVersion')) evidenceSchemaVersion
        FROM nx_support_payment_attribution WHERE customer_id IN
        <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach>
        AND capture_mode='NEW_SUCCESS' ORDER BY customer_id,fact_id</script>
        """)
    List<Row> readNewFinancialProofs(@Param("customerIds") Collection<Long> customerIds);

    record Row(String factId, Long customerId, String kind, String source, Long ledgerId,
        String sourceBusinessId, String orderNo, String orderType, String originalFactId,
        String currency, BigDecimal amount, LocalDateTime succeededAt, String sourceBusinessZone,
        String successTimeField, Integer fractionalSecondDigits, String sourcePartition,
        LocalDateTime captureDbUtc, String captureMode, String captureSchemaVersion,
        String sourceFactJson, String beforeSourceJson, String evidenceCaptureMode, String evidenceSchemaVersion) { }
}
