package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.platform.application.OpsArchitectureService;
import ffdd.opsconsole.platform.dto.OpsArchitectureOverview;
import org.junit.jupiter.api.Test;

class OpsArchitectureControllerTest {
    private final OpsArchitectureController controller =
            new OpsArchitectureController(new OpsArchitectureService());

    @Test
    void overviewReturnsModularMonolithContract() {
        ApiResult<OpsArchitectureOverview> result = controller.overview();

        assertThat(result.getCode()).isZero();
        OpsArchitectureOverview overview = result.getData();
        assertThat(overview.deploymentMode()).isEqualTo("MODULAR_MONOLITH");
        assertThat(overview.springBootApplications()).containsExactly("nexion-backend");
        assertThat(overview.domainCount()).isEqualTo(12);
        assertThat(overview.domains()).hasSize(12);
        assertThat(overview.deprecatedCapabilities())
                .contains("PremiumSubscription", "NexV2Vault", "PointsReward");
        assertThat(overview.boundaryRules())
                .contains(
                        "Controller -> same-domain ApplicationService only",
                        "Cross-domain calls -> public Facade or DomainEvent only",
                        "No Controller -> Mapper/Entity access");
    }
}
