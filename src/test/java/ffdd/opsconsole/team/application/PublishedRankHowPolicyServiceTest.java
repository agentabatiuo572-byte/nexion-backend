package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublishedRankHowPolicyServiceTest {
    @Test
    void returnsPublishedStructuredPolicyForRequestedLocale() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.rank_how.published")).thenReturn(Optional.of("""
                {"version":"r3","status":"PUBLISHED","locales":{"en":{"hero":"Rank policy","sections":[{"id":"qualification","title":"Qualification","body":"Server evaluates each step.","order":1}]},"vi":{"hero":"Chinh sach","sections":[{"id":"qualification","title":"Dieu kien","body":"May chu danh gia.","order":1}]}}}
                """));
        var result = service(config, new MockEnvironment()).publicPolicy("vi");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("version", "r3").containsEntry("locale", "vi");
        assertThat(result.getData().get("sections")).asList().hasSize(1);
    }

    @Test
    void developmentPublishesTheSameProductionShapedContractConsumedByTheFormalApp() {
        PlatformConfigFacade config = publishedConfig();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        var result = service(config, environment)
                .publicPolicy("en");

        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
    }

    @Test
    void isolatedTestProfileRetainsRunScopedSandboxProvenance() {
        PlatformConfigFacade config = publishedConfig();
        var environment = new MockEnvironment()
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-team-001");
        environment.setActiveProfiles("test");

        var result = service(config, environment)
                .publicPolicy("en");

        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-team-001");
    }

    @Test
    void isolatedTestProfileFailsClosedWithoutAValidRunId() {
        PlatformConfigFacade config = publishedConfig();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        var result = service(config, environment)
                .publicPolicy("en");

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("RANK_HOW_POLICY_UNAVAILABLE");
    }

    @Test
    void missingPolicyFailsClosedInsteadOfFallingBackToLocalNarrative() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.rank_how.published")).thenReturn(Optional.empty());
        var result = service(config, new MockEnvironment()).publicPolicy("en");
        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("RANK_HOW_POLICY_UNAVAILABLE");
    }

    @Test
    void adminUpdateRejectsUnstructuredSections() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        var service = service(config, new MockEnvironment());
        var result = service.update("r3", "PUBLISHED", java.util.Map.of("en", java.util.Map.of("hero", "x", "sections", java.util.List.of(java.util.Map.of("title", "missing id")))),0L,"Publish reviewed rank policy");
        assertThat(result.getCode()).isEqualTo(422);
        verifyNoInteractions(config);
    }

    @Test
    void adminUpdateRejectsDuplicateIdsAndUnsafeOrders() {
        PlatformConfigFacade config=mock(PlatformConfigFacade.class);
        var service=service(config,new MockEnvironment());
        var duplicate=java.util.List.of(
                java.util.Map.of("id","same","title","A","body","A","order",1),
                java.util.Map.of("id","same","title","B","body","B","order",2));
        assertThat(service.update("r4","PUBLISHED",java.util.Map.of("en",java.util.Map.of("hero","h","sections",duplicate)),0L,"Publish reviewed rank policy").getCode()).isEqualTo(422);
        var unsafe=java.util.List.of(java.util.Map.of("id","x","title","A","body","A","order",1.5));
        assertThat(service.update("r4","PUBLISHED",java.util.Map.of("en",java.util.Map.of("hero","h","sections",unsafe)),0L,"Publish reviewed rank policy").getCode()).isEqualTo(422);
        verifyNoInteractions(config);
    }

    private PlatformConfigFacade publishedConfig() {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.rank_how.published")).thenReturn(Optional.of("""
                {"version":"r3","status":"PUBLISHED","locales":{"en":{"hero":"Rank policy","sections":[{"id":"qualification","title":"Qualification","body":"Server evaluates each step.","order":1}]}}}
                """));
        return config;
    }

    @Test
    void publicPolicyProjectsOnlyAllowlistedRuntimeRuleFacts() {
        PlatformConfigFacade config = publishedConfig();
        VRankPromotionEngine promotionEngine = mock(VRankPromotionEngine.class);
        LeadershipPoolConfigGuard leadershipGuard = mock(LeadershipPoolConfigGuard.class);
        when(promotionEngine.permanentProtectionEnabled()).thenReturn(true);
        when(promotionEngine.qualifiedReferralSelfBuyUsd()).thenReturn(new BigDecimal("125.50"));
        when(leadershipGuard.isConfigured()).thenReturn(true);

        var result = new PublishedRankHowPolicyService(
                config, new MockEnvironment(), mock(AuditLogService.class), promotionEngine, leadershipGuard)
                .publicPolicy("en");

        assertThat(result.getCode()).isZero();
        assertThat((java.util.Map<String, Object>) result.getData().get("rules"))
                .containsOnlyKeys("permanentProtection", "qualifiedReferralSelfBuyUSD", "leadershipConfigured")
                .containsEntry("permanentProtection", true)
                .containsEntry("qualifiedReferralSelfBuyUSD", new BigDecimal("125.50"))
                .containsEntry("leadershipConfigured", true);
    }

    @Test
    void unavailableThresholdAndLeadershipConfigRemainSafePublicFacts() {
        PlatformConfigFacade config = publishedConfig();
        VRankPromotionEngine promotionEngine = mock(VRankPromotionEngine.class);
        LeadershipPoolConfigGuard leadershipGuard = mock(LeadershipPoolConfigGuard.class);
        when(promotionEngine.permanentProtectionEnabled()).thenReturn(false);
        when(promotionEngine.qualifiedReferralSelfBuyUsd()).thenReturn(null);
        when(leadershipGuard.isConfigured()).thenReturn(false);

        var result = new PublishedRankHowPolicyService(
                config, new MockEnvironment(), mock(AuditLogService.class), promotionEngine, leadershipGuard)
                .publicPolicy("en");

        assertThat((java.util.Map<String, Object>) result.getData().get("rules"))
                .containsEntry("permanentProtection", false)
                .containsEntry("qualifiedReferralSelfBuyUSD", null)
                .containsEntry("leadershipConfigured", false);
    }

    private PublishedRankHowPolicyService service(PlatformConfigFacade config, MockEnvironment environment) {
        VRankPromotionEngine promotionEngine = mock(VRankPromotionEngine.class);
        LeadershipPoolConfigGuard leadershipGuard = mock(LeadershipPoolConfigGuard.class);
        when(promotionEngine.permanentProtectionEnabled()).thenReturn(false);
        when(promotionEngine.qualifiedReferralSelfBuyUsd()).thenReturn(null);
        when(leadershipGuard.isConfigured()).thenReturn(false);
        return new PublishedRankHowPolicyService(
                config, environment, mock(AuditLogService.class), promotionEngine, leadershipGuard);
    }

    @Test
    void ruleReadFailureDoesNotPretendProtectionIsDisabled() {
        var engine = mock(VRankPromotionEngine.class);
        when(engine.permanentProtectionEnabled()).thenThrow(new IllegalStateException("unavailable"));
        var result = new PublishedRankHowPolicyService(publishedConfig(), new MockEnvironment(),
                mock(AuditLogService.class), engine, mock(LeadershipPoolConfigGuard.class)).publicPolicy("en");
        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getData()).isNull();
    }

    @Test
    void publicPolicyDoesNotExposeExtraStoredFields() {
        var config = mock(PlatformConfigFacade.class);
        when(config.activeValue("team.rank_how.published")).thenReturn(Optional.of("""
                {"version":"r3","status":"PUBLISHED","locales":{"en":{"hero":"Rank policy","internalNote":"private","sections":[{"id":"qualification","title":"Qualification","body":"Server rules","order":1,"internalNote":"private"}]}}}
                """));
        var result = service(config, new MockEnvironment()).publicPolicy("en");
        assertThat(result.getData()).doesNotContainKey("internalNote");
        assertThat(((java.util.Map<?, ?>) ((java.util.List<?>) result.getData().get("sections")).get(0))
                .containsKey("internalNote")).isFalse();
    }
}
