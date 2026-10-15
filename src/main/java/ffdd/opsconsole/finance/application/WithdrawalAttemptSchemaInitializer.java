package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Checks withdrawal schema and creates the ambiguity fence before context refresh. */
@Component
@RequiredArgsConstructor
public class WithdrawalAttemptSchemaInitializer implements SmartInitializingSingleton {
    static final List<String> REQUIRED_ORDER_COLUMNS = List.of(
            "id", "user_id", "withdrawal_no", "asset", "chain", "amount", "fee", "target_address", "status",
            "d2_idempotency_key", "d2_version", "d2_hold_until", "d5_payout_due_at", "d2_penalty_fee_rate",
            "d2_gross_fee", "d2_nex_burned", "d2_network_fee_rate", "d2_network_fee_min", "d2_network_fee_max",
            "d2_network_fee", "d2_nex_fee_offset_rate", "d2_fee_waived", "d2_actual_fee", "d2_net_receive",
            "d5_policy_version", "d5_use_nex_fee_offset", "d2_lifecycle_owner", "d2_freeze_period",
            "d2_routing_priority", "d2_k3_risk_route", "d2_k4_risk_score", "d2_k4_model_version", "d2_k4_as_of",
            "d2_k4_band_low_max", "d2_k4_band_high_min", "d2_k4_auto_escalate_score", "failure_reason",
            "d2_previous_status", "terminal_reason", "retriable", "created_at", "updated_at", "is_deleted");
    private static final String MIGRATION_GUIDANCE = "apply scripts/apply_startup_schema_migrations.ps1 "
            + "(including scripts/migrations/20261015_withdrawal_order_schema_readiness.sql) before startup";
    private static final String QUOTE_EXPIRY_MIGRATION_GUIDANCE = "apply "
            + "scripts/migrations/20260916_bank_beneficiary_verification.sql before startup";
    private final AppWithdrawalMapper mapper;

    @Override
    public void afterSingletonsInstantiated() {
        Set<String> columns;
        try {
            columns = Set.copyOf(mapper.withdrawalOrderColumns());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("WITHDRAWAL_ORDER_SCHEMA_CHECK_FAILED: " + MIGRATION_GUIDANCE, failure);
        }
        List<String> missing = REQUIRED_ORDER_COLUMNS.stream().filter(column -> !columns.contains(column)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("WITHDRAWAL_ORDER_SCHEMA_MIGRATION_REQUIRED: nx_withdrawal_order missing "
                    + String.join(", ", missing) + "; " + MIGRATION_GUIDANCE);
        }
        int quoteExpiryColumns;
        try {
            quoteExpiryColumns = mapper.withdrawalQuoteExpiryColumnCount();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("WITHDRAWAL_QUOTE_EXPIRY_SCHEMA_CHECK_FAILED: "
                    + QUOTE_EXPIRY_MIGRATION_GUIDANCE, failure);
        }
        if (quoteExpiryColumns != 2) {
            throw new IllegalStateException("WITHDRAWAL_QUOTE_EXPIRY_SCHEMA_MIGRATION_REQUIRED: "
                    + "nx_bank_payout_quote_expiry requires quote_no and expired_at; " + QUOTE_EXPIRY_MIGRATION_GUIDANCE);
        }
        mapper.createWithdrawalAttemptTable();
        if (mapper.withdrawalAttemptStatusCheckCount() != 1) {
            throw new IllegalStateException("WITHDRAWAL_ATTEMPT_STATUS_CHECK_MISSING: apply "
                    + "scripts/migrations/20260816_withdrawal_attempt_authority.sql before startup");
        }
    }
}
