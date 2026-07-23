package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.finance.dto.WithdrawalBatchReviewRequest;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import ffdd.opsconsole.finance.web.OpsFinanceController;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.expression.spel.standard.SpelExpressionParser;

class D2WithdrawalClosureContractTest {

    @Test
    void reviewRequestCarriesLifecycleAndFundsConfirmationFields() {
        assertThat(Arrays.stream(WithdrawalReviewRequest.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("reasonCode", "holdDays", "period", "owner", "reviewAt", "fundsVerified", "addressVerified");
    }

    @Test
    void controllerUsesExactActionAuthorizationAndExposesBatchEndpoint() throws Exception {
        Method review = OpsFinanceController.class.getMethod(
                "reviewWithdrawal", String.class, String.class, WithdrawalReviewRequest.class);
        String expression = review.getAnnotation(PreAuthorize.class).value();
        assertThat(expression).isEqualTo("@d2WithdrawalAuthorization.canReview(authentication,#request)");
        new SpelExpressionParser().parseExpression(expression);

        D2WithdrawalAuthorization authorization = new D2WithdrawalAuthorization();
        for (String action : List.of("APPROVE", "DELAY", "FREEZE", "UNFREEZE", "REJECT", "REFUND")) {
            String permission = "finance_d2_withdrawal_" + action.toLowerCase(java.util.Locale.ROOT);
            var authentication = new UsernamePasswordAuthenticationToken(
                    "tester", "n/a", List.of(new SimpleGrantedAuthority(permission)));
            assertThat(authorization.canReview(authentication,
                    new WithdrawalReviewRequest(action, "tester", "valid review reason")))
                    .as(action)
                    .isTrue();
        }
        var readOnly = new UsernamePasswordAuthenticationToken(
                "reader", "n/a", List.of(new SimpleGrantedAuthority("finance_d2_read")));
        assertThat(authorization.canReview(readOnly,
                new WithdrawalReviewRequest("APPROVE", "reader", "must be denied"))).isFalse();
        var wrongAction = new UsernamePasswordAuthenticationToken(
                "reviewer", "n/a", List.of(new SimpleGrantedAuthority("finance_d2_withdrawal_approve")));
        assertThat(authorization.canReview(wrongAction,
                new WithdrawalReviewRequest("FREEZE", "reviewer", "must be denied"))).isFalse();

        Method batch = OpsFinanceController.class.getMethod(
                "reviewWithdrawalsBatch", String.class, WithdrawalBatchReviewRequest.class);
        assertThat(batch.getAnnotation(PreAuthorize.class).value())
                .contains("finance_d2_withdrawal_batch");
    }

    @Test
    void d2ConsumesD5FactsThroughTheCanonicalLeastPrivilegeRoute() throws Exception {
        Method canonical = ffdd.opsconsole.finance.web.OpsWithdrawalLimitsController.class
                .getMethod("getLimits");
        assertThat(canonical.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('finance_d5_read')");

        Method legacy = OpsFinanceController.class.getMethod("withdrawalParams");
        assertThat(legacy.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('finance_d5_read')");
    }

    @Test
    void stateTransitionIsCompareAndSetInsteadOfBlindUpdate() throws Exception {
        Method transition = WithdrawalOrderMapper.class.getMethod(
                "transitionStatus", String.class, String.class, String.class, String.class);
        String sql = String.join(" ", transition.getAnnotation(Update.class).value());
        assertThat(sql)
                .contains("status = #{expectedStatus}")
                .contains("d2_version = d2_version + 1")
                .contains("WHERE withdrawal_no = #{withdrawalNo}");
    }

    @Test
    void d2HasCanonicalStateMachineAndExpiredHoldRelease() throws Exception {
        Class<?> stateMachine = Class.forName("ffdd.opsconsole.finance.application.D2WithdrawalStateMachine");
        Method canonical = stateMachine.getMethod("canonical", String.class);
        Method next = stateMachine.getMethod("next", String.class, String.class);
        assertThat(canonical.invoke(null, "REVIEWING")).isEqualTo("REVIEW_PENDING");
        assertThat(canonical.invoke(null, "DELAYED")).isEqualTo("EXTENDED_HOLD");
        assertThat(canonical.invoke(null, "DEAD")).isEqualTo("TX_ORPHANED");
        assertThat(next.invoke(null, "SENT", "FREEZE")).isNull();
        assertThat(next.invoke(null, "PROCESSING", "FREEZE")).isEqualTo("FROZEN");

        Method release = WithdrawalOrderMapper.class.getMethod("releaseExpiredHolds", java.time.LocalDateTime.class);
        String sql = String.join(" ", release.getAnnotation(Update.class).value());
        assertThat(sql)
                .contains("EXTENDED_HOLD")
                .contains("FROZEN")
                .contains("REVIEW_PENDING")
                .contains("d2_hold_until");
        assertThat(WithdrawalOrderMapper.class.getMethod("findExpiredLifecycleNos", java.time.LocalDateTime.class))
                .isNotNull();
        assertThat(WithdrawalOrderMapper.class.getMethod(
                "releaseExpiredLifecycle", String.class, String.class, String.class, String.class,
                java.time.LocalDateTime.class))
                .isNotNull();
    }

    @Test
    void d2CarriesCompleteD5FeeFactsAndLifecycleEvents() throws Exception {
        assertThat(Arrays.stream(ffdd.opsconsole.finance.domain.WithdrawalOrderView.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("nexFeeOffsetRate", "feeWaived", "nexBurned", "actualFee", "netReceive");

        String migration = Files.readString(Path.of("scripts/migrations/20260720_d2_withdrawal_closure.sql"));
        assertThat(migration)
                .contains("d2_fee_waived")
                .contains("d2_nex_fee_offset_rate")
                .contains("withdrawal.nex_fee_offset_rate")
                .contains("wallet.withdrawal.nex_fee_offset_rate")
                .contains("v_nex_fee_offset_rate")
                .contains("CREATE TRIGGER trg_withdrawal_d2_fee_snapshot_bi")
                .contains("MODIFY COLUMN d2_gross_fee")
                .contains("hold_days")
                .contains("review_at")
                .contains("lifecycle_owner")
                .contains("freeze_period");

        String mapperSource = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/mapper/WithdrawalOrderMapper.java"));
        assertThat(mapperSource)
                .contains("w.d2_gross_fee AS grossFee")
                .contains("w.d2_nex_burned AS nexBurned")
                .doesNotContain("COALESCE(w.d2_gross_fee")
                .doesNotContain("COALESCE(w.d2_nex_burned");
    }

    @Test
    void batchUsesBoundedDerivedItemKeysAndAllAdminEventsRegisterOperator() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/OpsFinanceService.java"));
        assertThat(service)
                .contains("sha256(\"D2_ITEM|")
                .doesNotContain("idempotencyKey + \"-\" + accepted.size()");

        String migration = Files.readString(Path.of("scripts/migrations/20260720_d2_withdrawal_closure.sql"));
        assertThat(migration)
                .contains("'operator','string',1")
                .contains("'withdraw.approved','withdraw.rejected','withdraw.delayed','withdraw.frozen','withdraw.unfrozen','withdraw.refunded','withdraw.review_due'");
    }

    @Test
    void migrationRegistersCompleteA4RiskContextAndSupportLeadRoles() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260720_d2_withdrawal_closure.sql"));
        assertThat(migration)
                .contains("address_hash")
                .contains("risk_score")
                .contains("RISK_LEAD")
                .contains("SUPPORT")
                .contains("d2_hold_until");
    }

    @Test
    void d5CanonicalAggregateWriteIsTransactionalVersionedIdempotentAndUsesRequiredAudit() throws Exception {
        Method update = ffdd.opsconsole.finance.application.OpsFinanceService.class.getMethod(
                "updateWithdrawalLimits", String.class,
                ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest.class);
        assertThat(update.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();

        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/OpsFinanceService.java"));
        assertThat(service)
                .contains("D5_WITHDRAWAL_LIMITS_UPDATE")
                .contains("CONFIG_VERSION_CONFLICT")
                .contains("idempotencyService.execute")
                .contains("auditRequired(\"D5_WITHDRAWAL_PARAM_CHANGED\"")
                .contains("admin.withdraw_limit_changed");

        Method controller = ffdd.opsconsole.finance.web.OpsWithdrawalLimitsController.class.getMethod(
                "updateLimits", String.class,
                ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest.class);
        assertThat(controller.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@d5WithdrawalAuthorization.canUpdateLimits(authentication,#request)");

        Method legacyPatch = OpsFinanceController.class.getMethod(
                "updateWithdrawalParam", String.class,
                ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest.class);
        assertThat(legacyPatch.getReturnType()).isEqualTo(org.springframework.http.ResponseEntity.class);
        assertThat(legacyPatch.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('finance_d5_read')");

        String controllerSource = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/web/OpsFinanceController.java"));
        assertThat(controllerSource)
                .contains("LEGACY_D5_WRITE_DISABLED")
                .contains("ResponseEntity.status(410)")
                .contains("/api/admin/withdraw/limits")
                .doesNotContain("return financeService.updateWithdrawalParam(idempotencyKey, request)");
    }
}
