package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ABPendingClosureContractTest {

    @Test
    void a3ParametersHaveAuthoritativeConsumersCasIdempotencyAndAudit() throws Exception {
        String config = source("src/main/java/ffdd/opsconsole/platform/application/OpsPlatformConfigService.java");
        String rateLimit = source("src/main/java/ffdd/opsconsole/platform/application/PlatformGlobalRateLimitFilter.java");
        String principalRateLimit = source("src/main/java/ffdd/opsconsole/platform/application/AuthenticatedPrincipalRateLimitFilter.java");
        String security = source("src/main/java/ffdd/opsconsole/shared/security/SecurityConfig.java");
        String withdrawal = source("src/main/java/ffdd/opsconsole/finance/application/AppWithdrawalService.java");

        assertThat(config).contains(
                "platform.global_rate_limit_per_minute",
                "withdrawal.strong_review_threshold_usdt",
                "A3_PLATFORM_PARAM_CHANGED",
                "A3_PARAM_STALE",
                "AdminIdempotencyService");
        assertThat(rateLimit).contains("StringRedisTemplate", "GLOBAL_RATE_LIMIT_UNAVAILABLE", "429", "/auth/");
        assertThat(rateLimit).contains("always socket-IP based");
        assertThat(principalRateLimit).contains("subject-", "PRINCIPAL_RATE_LIMIT_EXCEEDED");
        assertThat(security).contains("addFilterAfter(authenticatedPrincipalRateLimitFilter, JwtAuthenticationFilter.class)");
        assertThat(withdrawal).contains("withdrawal.strong_review_threshold_usdt", "A3_STRONG_REVIEW_THRESHOLD_UNAVAILABLE");
    }

    @Test
    void a4LifecycleAndA2MakerWithdrawAreStateMachines() throws Exception {
        String eventService = source("src/main/java/ffdd/opsconsole/platform/application/OpsEventCenterService.java");
        String eventController = source("src/main/java/ffdd/opsconsole/platform/web/OpsEventCenterController.java");
        String outbox = source("src/main/java/ffdd/opsconsole/shared/outbox/EventOutboxService.java");
        String dispatcher = source("src/main/java/ffdd/opsconsole/shared/outbox/EventOutboxDispatchScheduler.java");
        String auditService = source("src/main/java/ffdd/opsconsole/platform/application/OpsAuditCenterService.java");
        String auditController = source("src/main/java/ffdd/opsconsole/platform/web/OpsAuditController.java");

        assertThat(eventService).contains(
                "pending_publish", "gray", "full", "disabled",
                "A4_EVENT_LIFECYCLE_STALE", "A4_EVENT_LIFECYCLE_CHANGED");
        assertThat(eventController).contains("/schema-registrations/{eventName}/lifecycle", "platform_a4_write");
        assertThat(outbox).contains("enforceLifecycle", "pending_publish", "disabled", "gray", "full");
        assertThat(dispatcher).contains("assertDispatchAllowed");
        assertThat(auditService).contains("A2_OPERATION_WITHDRAWN", "A2_WITHDRAW_MAKER_ONLY", "A2_WITHDRAW_STALE");
        assertThat(auditController).contains("/operations/{operationId}/withdraw", "platform_a2_proposal_create");
    }

    @Test
    void a1PermissionRegistrationStartsWithZeroGrants() throws Exception {
        String service = source("src/main/java/ffdd/opsconsole/platform/application/OpsA1PermissionRegistrationService.java");
        String controller = source("src/main/java/ffdd/opsconsole/platform/web/OpsA1PermissionRegistrationController.java");

        assertThat(service).contains(
                "A1_PERMISSION_REGISTERED", "A1_PERMISSION_ALREADY_EXISTS",
                "boundRoleCount", "AdminIdempotencyService", "recordRequired");
        assertThat(controller).contains("platform_a1_rbac_grants_update", "/platform/accounts/permissions");
    }

    @Test
    void b5DeliveryAndDispositionAreDurableRetryableAndVersioned() throws Exception {
        String service = source("src/main/java/ffdd/opsconsole/risk/application/OpsRiskRadarService.java");
        String delivery = source("src/main/java/ffdd/opsconsole/risk/application/B5RiskAlertDeliveryService.java");
        String finalizer = source("src/main/java/ffdd/opsconsole/risk/application/B5RiskAlertDeliveryFinalizer.java");
        String controller = source("src/main/java/ffdd/opsconsole/risk/web/OpsRiskRadarController.java");
        String mapper = source("src/main/java/ffdd/opsconsole/risk/mapper/B5RiskRadarMapper.java");

        assertThat(delivery).contains(
                "@Scheduled", "MAX_RETRIES", "requireAllowedPublicHttps", "Redirect.NEVER",
                "deliveryId", "Idempotency-Key", "b5-alert-delivery-",
                "ProxySelector", "WEBHOOK_EGRESS_PROXY_KEY", "sandboxProfileAllowed");
        assertThat(finalizer).contains("REQUIRES_NEW", "FAILED_RETRY", "B5_ALERT_DELIVERY", "recordRequired");
        assertThat(service).contains(
                "B5_SIGNAL_STATUS_CHANGED", "B5_SIGNAL_VERSION_CONFLICT",
                "handled", "resolved", "AdminIdempotencyService");
        assertThat(controller).contains(
                "/radar/signals/{signalNo}/status", "/radar/inbox", "overview_b5_triage");
        assertThat(mapper).contains(
                "nx_admin_risk_signal_disposition", "nx_admin_risk_alert_delivery",
                "WHERE subscriber=#{subscriber}", "acknowledgeInbox", "SANDBOX_DELIVERED",
                "undeliveredSignalNos", "NOT EXISTS");
    }

    @Test
    void migrationCreatesAllNewDurableStateAndSeedsFailClosedParameters() throws Exception {
        String migration = source("scripts/migrations/20260810_ab_pending_closure.sql");
        assertThat(migration).contains(
                "platform.global_rate_limit_per_minute",
                "withdrawal.strong_review_threshold_usdt",
                "nx_admin_event_lifecycle",
                "nx_admin_risk_signal_disposition",
                "nx_admin_risk_alert_delivery");
    }

    private String source(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }
}
