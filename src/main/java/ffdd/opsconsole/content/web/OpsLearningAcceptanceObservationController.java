package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.LearningAcceptanceSandboxGate;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.content.domain.LearningSandboxObservationWindow;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Acceptance-only, run-scoped observer. The explicit zero deltas are a contract, never an aggregate inference. */
@RestController
@Profile({"dev", "test"})
@RequestMapping("/api/admin/content/learning-acceptance")
@RequiredArgsConstructor
public class OpsLearningAcceptanceObservationController {
    private final LearningAcceptanceSandboxGate gate;
    private final AppLearningMapper mapper;
    @Value("${nexion.learning.acceptance-run-id:}") private String configuredRunId;

    @GetMapping("/observation")
    @PreAuthorize("hasAuthority('content_i7_read')")
    public ApiResult<Map<String,Object>> observation(@RequestParam String runId) {
        gate.requireEnabled("SANDBOX");
        if (!StringUtils.hasText(configuredRunId) || !configuredRunId.trim().equals(runId)) {
            return ApiResult.fail(409, "LEARNING_ACCEPTANCE_RUN_ID_INVALID");
        }
        LearningSandboxObservationWindow window = mapper.sandboxObservationWindow(runId);
        if (!window.available()) {
            return ApiResult.ok(Map.of("runId", runId, "source", "mock", "sourceEnvironment", "SANDBOX",
                    "permanentLabel", "ACCEPTANCE SANDBOX • NON-PRODUCTION", "productionDelta", Map.of("status", "INSUFFICIENT"),
                    "progress", mapper.sandboxObservationProgress(runId), "rewards", mapper.sandboxObservationRewards(runId),
                    "idempotency", mapper.sandboxObservationIdempotency(runId)));
        }
        int progress = mapper.productionLearningProgressDelta(runId, window.fromAt(), window.toAt());
        int event = mapper.productionLearningEventDelta(runId, window.fromAt(), window.toAt());
        int reward = mapper.productionLearningRewardDelta(runId, window.fromAt(), window.toAt());
        int earningsRelease = mapper.productionLearningEarningsReleaseDelta(runId, window.fromAt(), window.toAt());
        int walletLedger = mapper.productionLearningWalletLedgerDelta(runId, window.fromAt(), window.toAt());
        int outbox = mapper.productionLearningOutboxDelta(runId, window.fromAt(), window.toAt());
        int adminIdempotency = mapper.productionLearningAdminIdempotencyDelta(runId, window.fromAt(), window.toAt());
        int catalogVersion = mapper.productionLearningCatalogVersionDelta(runId, window.fromAt(), window.toAt());
        int catalogAdminIdempotency = mapper.productionLearningCatalogAdminIdempotencyDelta(runId, window.fromAt(), window.toAt());
        int catalogAudit = mapper.productionLearningCatalogAuditDelta(runId, window.fromAt(), window.toAt());
        int catalogOutbox = mapper.productionLearningCatalogOutboxDelta(runId, window.fromAt(), window.toAt());
        String status = progress == 0 && event == 0 && reward == 0 && earningsRelease == 0 && walletLedger == 0 && outbox == 0 && adminIdempotency == 0
                && catalogVersion == 0 && catalogAdminIdempotency == 0 && catalogAudit == 0 && catalogOutbox == 0
                ? "VERIFIED_ZERO" : "VIOLATION";
        Map<String,Object> productionDelta = new java.util.LinkedHashMap<>();
        productionDelta.put("status", status); productionDelta.put("progress", progress); productionDelta.put("event", event);
        productionDelta.put("reward", reward); productionDelta.put("earningsRelease", earningsRelease); productionDelta.put("walletLedger", walletLedger);
        productionDelta.put("outbox", outbox); productionDelta.put("adminIdempotency", adminIdempotency);
        productionDelta.put("catalogVersion", catalogVersion); productionDelta.put("catalogAdminIdempotency", catalogAdminIdempotency);
        productionDelta.put("catalogAudit", catalogAudit); productionDelta.put("catalogOutbox", catalogOutbox);
        productionDelta.put("acceptanceUsers", window.userCount()); productionDelta.put("fromAt", window.fromAt()); productionDelta.put("toAt", window.toAt());
        return ApiResult.ok(Map.of("runId", runId, "source", "mock", "sourceEnvironment", "SANDBOX",
                "permanentLabel", "ACCEPTANCE SANDBOX • NON-PRODUCTION",
                "progress", mapper.sandboxObservationProgress(runId), "rewards", mapper.sandboxObservationRewards(runId),
                "idempotency", mapper.sandboxObservationIdempotency(runId), "productionDelta", productionDelta));
    }
}
