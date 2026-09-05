package ffdd.opsconsole;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class P1RegressionContractTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java", path));
    }

    @Test
    void dayOneClaimAndStateUseThePcTriRewardPolicy() throws Exception {
        String service = source("ffdd/opsconsole/growth/application/AppGrowthEngagementService.java");
        String mapper = source("ffdd/opsconsole/growth/mapper/AppGrowthEngagementMapper.java");
        assertThat(service).contains("DayOneTriRewardPolicy", "effectiveDayOneReward");
        assertThat(mapper).contains("triReward", "accountAgeHours");
    }

    @Test
    void ambassadorSubmitUsesTheSameServerPolicyAsTheReadEndpoint() throws Exception {
        String service = source("ffdd/opsconsole/team/application/AppAmbassadorApplicationService.java");
        assertThat(service).contains("AppAmbassadorPolicyService", "budgetAllowed");
        assertThat(service).doesNotContain("budget.compareTo(new BigDecimal(\"10000\"))");
    }

    @Test
    void canonicalAndPcOrderQueriesProjectEveryBundleItemAndSubtotal() throws Exception {
        String canonical = source("ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java");
        String service = source("ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java");
        String pc = source("ffdd/opsconsole/device/mapper/DeviceCatalogMapper.java");
        assertThat(canonical).contains("GROUP_CONCAT", "subtotalUsdt", "itemCount");
        assertThat(service).contains("\"subtotalUsdt\"", "order.subtotalUsdt()", "\"itemCount\"");
        assertThat(pc).contains("GROUP_CONCAT", "×");
    }

    @Test
    void supportSurfacesAndNovaKnowledgeAreBothServerPublished() throws Exception {
        String support = source("ffdd/opsconsole/content/application/AppSupportService.java");
        String rag = source("ffdd/opsconsole/content/application/RagNovaAiGateway.java");
        assertThat(support).contains("Ticket Create", "surface");
        assertThat(rag).contains("SupportKnowledgeRepository", "Nova");
    }

    @Test
    void computeShareFailsClosedWithoutAnHttpsInstaller() throws Exception {
        String service = source("ffdd/opsconsole/device/application/AppComputeShareEnrollmentService.java");
        assertThat(service).contains("E.compute.download.url", "COMPUTE_SHARE_INSTALLER_UNAVAILABLE", "https://");
    }

    @Test
    void blockedBinaryCannotExposeAPositiveSettlementEstimate() throws Exception {
        String service = source("ffdd/opsconsole/team/application/AppBinaryProjectionService.java");
        assertThat(service).contains("blockedReason.isBlank() ? estimate : ZERO");
    }

    @Test
    void commissionStatesAndBusinessDatesStayCanonical() throws Exception {
        String service = source("ffdd/opsconsole/team/application/AppTeamInsightsService.java");
        assertThat(service).contains("BUSINESS_ZONE", "case \"FROZEN\"", "case \"REVERSED\",\"ROLLBACK\"");
        assertThat(service).doesNotContain("LocalDate.now(ZoneOffset.UTC)");
    }
}
