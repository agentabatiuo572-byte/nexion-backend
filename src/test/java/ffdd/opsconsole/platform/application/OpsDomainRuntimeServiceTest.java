package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationRequest;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationResponse;
import ffdd.opsconsole.platform.dto.OpsDomainRuntimeContract;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsDomainRuntimeServiceTest {
    private final OpsDomainRuntimeService service = new OpsDomainRuntimeService();

    @Test
    void exposesContractsForAllTwelveDomains() {
        ApiResult<List<OpsDomainRuntimeContract>> result = service.contracts();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).hasSize(12);
        assertThat(result.getData()).extracting(OpsDomainRuntimeContract::adminApiPrefix)
                .contains("/api/admin/treasury", "/api/admin/finance", "/api/admin/content");
    }

    @Test
    void treasuryContractCarriesB1CoverageRedline() {
        ApiResult<OpsDomainRuntimeContract> result = service.contract("treasury");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().redlines()).anyMatch(redline -> redline.contains("COVERAGE_BELOW_REDLINE"));
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("ReserveCoverage");
            assertThat(api.b1RedlineTriggered()).isTrue();
            assertThat(api.errorCodes()).contains("COVERAGE_BELOW_REDLINE");
        });
    }

    @Test
    void platformContractIncludesA4EventCenterDedicatedApi() {
        ApiResult<OpsDomainRuntimeContract> result = service.contract("platform");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("A4EventCenter");
            assertThat(api.path()).isEqualTo("/api/admin/platform/events");
            assertThat(api.writePermission()).isEqualTo("PERM_SYSTEM_WRITE");
        });
    }

    @Test
    void contentContractIncludesM4KnowledgeApi() {
        ApiResult<OpsDomainRuntimeContract> result = service.contract("content");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("SupportKnowledge");
            assertThat(api.path()).isEqualTo("/api/admin/content/knowledge/overview");
            assertThat(api.writePermission()).isEqualTo("PERM_CONTENT_WRITE");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("SupportSlaRule");
            assertThat(api.path()).isEqualTo("/api/admin/content/knowledge/sla/{category}");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("CopyAbOverview");
            assertThat(api.path()).isEqualTo("/api/admin/content/copy-ab/overview");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("CopyExperiment");
            assertThat(api.path()).isEqualTo("/api/admin/content/copy-ab/experiments/{experimentId}/{action}");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("NotificationCampaign");
            assertThat(api.path()).isEqualTo("/api/admin/content/campaigns/overview");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("NotificationCapRule");
            assertThat(api.path()).isEqualTo("/api/admin/content/campaigns/caps/{tier}");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("TrustDisclosureOverview");
            assertThat(api.path()).isEqualTo("/api/admin/content/trust-disclosure/overview");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("DisclosureGateAction");
            assertThat(api.path()).isEqualTo("/api/admin/content/trust-disclosure/disclosures/gated-actions");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("I18nLearningOverview");
            assertThat(api.path()).isEqualTo("/api/admin/content/i18n-learning/overview");
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("LearningCourse");
            assertThat(api.path()).isEqualTo("/api/admin/content/i18n-learning/courses/{courseId}");
            assertThat(api.b1RedlineTriggered()).isTrue();
        });
    }

    @Test
    void writeValidationRequiresIdempotencyAndReason() {
        OpsDomainCommandValidationRequest request =
                new OpsDomainCommandValidationRequest("ADJUST_LEDGER", "TreasuryLedger", null, null, null, null, "ops correction");

        ApiResult<OpsDomainCommandValidationResponse> result = service.validateCommand("treasury", " ", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());

        ApiResult<OpsDomainCommandValidationResponse> noReason =
                service.validateCommand("treasury", "idem-1", new OpsDomainCommandValidationRequest(
                        "ADJUST_LEDGER", "TreasuryLedger", null, null, null, null, " "));
        assertThat(noReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void treasuryCoverageBelowB1RedlineReturns422() {
        OpsDomainCommandValidationRequest request =
                new OpsDomainCommandValidationRequest("UPDATE_COVERAGE", "ReserveCoverage", null, null, 1.01D, 1.05D, "reserve drift");

        ApiResult<OpsDomainCommandValidationResponse> result = service.validateCommand("treasury", "idem-b1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void illegalWithdrawalTransitionReturns409() {
        OpsDomainCommandValidationRequest request =
                new OpsDomainCommandValidationRequest("REVIEW_WITHDRAWAL", "Withdrawal", "APPROVED", "SUCCEEDED", null, null, "manual jump");

        ApiResult<OpsDomainCommandValidationResponse> result = service.validateCommand("finance", "idem-d1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void deviceRestoreCorrectionAllowsRecycledToOfflineOnly() {
        OpsDomainCommandValidationRequest allowed =
                new OpsDomainCommandValidationRequest("RESTORE_DEVICE", "Device", "RECYCLED", "OFFLINE", null, null, "wrong recycle");

        ApiResult<OpsDomainCommandValidationResponse> result = service.validateCommand("devices", "idem-e3b", allowed);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().enforcedRules()).contains("Idempotency-Key", "Confirm-with-Reason", "A2 audit");

        OpsDomainCommandValidationRequest rejected =
                new OpsDomainCommandValidationRequest("RESTORE_DEVICE", "Device", "RECYCLED", "ACTIVE", null, null, "wrong recycle");
        assertThat(service.validateCommand("devices", "idem-e3b-2", rejected).getCode())
                .isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateLogCorrectionsAreAttachedToOwnerContracts() {
        assertThat(service.contract("markets").getData().updateCorrections()).contains("G3_WEEKLY_CURVE");
        assertThat(service.contract("growth").getData().updateCorrections())
                .contains("D5_H1_WITHDRAW_NEX_GATE", "H5_CHECKIN_NEX_REWARD");
        assertThat(service.contract("content").getData().updateCorrections()).contains("I9_CROSS_AGENT_TRANSFER");
        assertThat(service.contract("emergency").getData().updateCorrections()).contains("J1_GATE_SHRINK");
    }

    @Test
    void marketContractIncludesRepurchaseProductApiWithB1Redline() {
        ApiResult<OpsDomainRuntimeContract> result = service.contract("markets");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("RepurchaseProduct");
            assertThat(api.path()).isEqualTo("/api/admin/market/nex/repurchase");
            assertThat(api.b1RedlineTriggered()).isTrue();
        });
    }

    @Test
    void marketContractIncludesGenesisEconomyApisWithB1Redline() {
        ApiResult<OpsDomainRuntimeContract> result = service.contract("markets");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("GenesisEconomy");
            assertThat(api.path()).isEqualTo("/api/admin/market/nex/genesis");
            assertThat(api.b1RedlineTriggered()).isTrue();
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("GenesisDividendBatch");
            assertThat(api.path()).isEqualTo("/api/admin/market/nex/genesis/dividend-batches/{batchNo}/rerun");
        });
    }

    @Test
    void marketContractIncludesStakingPoolApisWithB1Redline() {
        ApiResult<OpsDomainRuntimeContract> result = service.contract("markets");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("StakingPools");
            assertThat(api.path()).isEqualTo("/api/admin/market/staking");
            assertThat(api.b1RedlineTriggered()).isTrue();
        });
        assertThat(result.getData().apiFamilies()).anySatisfy(api -> {
            assertThat(api.resource()).isEqualTo("StakingPoolKillStatus");
            assertThat(api.path()).isEqualTo("/api/admin/market/staking/pools/{tierKey}/kill-status");
        });
    }

    @Test
    void sunsetCommandsStayReadonly() {
        OpsDomainCommandValidationRequest request =
                new OpsDomainCommandValidationRequest("ENABLE_PREMIUM", "PremiumSubscription", null, null, null, null, "legacy request");

        ApiResult<OpsDomainCommandValidationResponse> result = service.validateCommand("growth", "idem-old", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus());
        assertThat(result.getMessage()).isEqualTo("SUNSET_CAPABILITY_READONLY");
    }
}
