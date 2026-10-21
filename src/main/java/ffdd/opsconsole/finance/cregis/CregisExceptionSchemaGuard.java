package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisExceptionSchemaMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CregisExceptionSchemaGuard implements ApplicationRunner {
    private final CregisProperties properties;
    private final CregisExceptionSchemaMapper mapper;

    @Override public void run(ApplicationArguments arguments) {
        if (properties.getMode() != CregisProperties.Mode.PROVIDER) return;
        validate(mapper.columns(), mapper.indexes());
    }

    static void validate(List<Map<String,Object>> columns, List<Map<String,Object>> indexes) {
        Map<String,String> required = Map.ofEntries(
            Map.entry("nx_cregis_deposit_event", "transfer_from_address origin_log_index"),
            Map.entry("nx_cregis_exception_source", "project_id txid log_index cid address gross_amount state operation_kind operation_ref alias_of_log_index version"),
            Map.entry("nx_cregis_ownership_challenge", "challenge_id project_id source_type source_id action target_user_id message evidence_hash expires_at consumed_ref created_by"),
            Map.entry("nx_cregis_exception_claim", "id case_no project_id cid txid log_index target_user_id challenge_id evidence_hash state version maker_id checker_id"),
            Map.entry("nx_cregis_exception_refund_policy", "project_id mode fee_usdt version"),
            Map.entry("nx_cregis_exception_refund_policy_case", "id project_id expected_policy_version mode fee_usdt maker_id checker_id state version"),
            Map.entry("nx_cregis_exception_refund", "id case_no project_id source_type source_id txid log_index gross_amount fee_amount net_amount fee_policy_version return_address evidence_hash state version business_key dispatch_intent_at provider_cid"),
            Map.entry("nx_cregis_exception_refund_callback", "payload_hash case_no provider_cid provider_status txid"),
            Map.entry("nx_cregis_exception_refund_ledger", "id case_no event_type amount_usdt evidence_json"),
            Map.entry("nx_cregis_reorg_resolution", "incident_id event_id project_id cid user_id net_amount held_amount recovered_amount outstanding_debt status version"),
            Map.entry("nx_cregis_reorg_proposal", "id incident_id action expected_version evidence_hash maker_id checker_id status version pending_incident"),
            Map.entry("nx_cregis_reorg_operation", "operation_no incident_id resolution_version phase original_fact_id attribution_effect snapshot_json proof_json events_json")
        );
        Set<String> available = new HashSet<>();
        for (Map<String,Object> row : columns) {
            String table = String.valueOf(row.get("tableName")), column = String.valueOf(row.get("columnName"));
            available.add(table + "." + column);
            if (Set.of("gross_amount", "fee_amount", "net_amount", "fee_usdt", "amount_usdt", "held_amount", "recovered_amount", "outstanding_debt").contains(column)
                && (!"decimal".equals(row.get("dataType")) || !(row.get("numericPrecision") instanceof Number precision)
                    || precision.intValue() != 18 || !(row.get("numericScale") instanceof Number scale) || scale.intValue() != 6)) fail();
        }
        required.forEach((table, names) -> {
            for (String name : names.split(" ")) if (!available.contains(table + "." + name)) fail();
        });
        Set<String> unique = new HashSet<>();
        for (Map<String,Object> row : indexes) unique.add(row.get("tableName") + ":" + row.get("indexColumns"));
        for (String key : List.of("nx_cregis_exception_source:project_id,txid,log_index", "nx_cregis_ownership_challenge:challenge_id",
            "nx_cregis_exception_claim:case_no", "nx_cregis_exception_claim:challenge_id", "nx_cregis_exception_refund_policy:project_id",
            "nx_cregis_exception_refund:case_no", "nx_cregis_exception_refund:business_key", "nx_cregis_exception_refund_callback:payload_hash",
            "nx_cregis_exception_refund_ledger:case_no,event_type", "nx_cregis_reorg_resolution:incident_id",
            "nx_cregis_reorg_proposal:pending_incident", "nx_cregis_reorg_operation:incident_id,resolution_version")) {
            if (!unique.contains(key)) fail();
        }
    }
    private static void fail() { throw new IllegalStateException("CREGIS_EXCEPTION_SCHEMA_MIGRATION_REQUIRED"); }
}
