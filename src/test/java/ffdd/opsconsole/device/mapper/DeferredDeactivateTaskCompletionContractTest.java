package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeferredDeactivateTaskCompletionContractTest {
    @Test
    void productionCompletionConsumesPendingFlagAndForcesRuntimeOffline() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/mapper/AppTaskAssignmentMapper.java"));
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/device/application/AppTaskAssignmentService.java"));
        assertThat(mapper).contains("pending_deactivate=1", "status='DEACTIVATED'", "USER_DEACTIVATED");
        assertThat(service).contains("\"PRODUCTION\".equals(sourceEnvironment)")
                .contains("deactivatePendingDevice")
                .contains("markRuntimeDeactivated")
                .contains("\"device.deactivated\"")
                .contains("USER_DEVICE_DEFERRED_DEACTIVATED")
                .contains("recordRequiredForTrustedActor");
    }

    @Test
    void fleetProjectionCarriesPendingDeactivateForRefreshAndRelogin() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"));
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/shared/canonical/AppCanonicalBoundaryService.java"));
        assertThat(mapper).contains("d.pending_deactivate AS pendingDeactivate");
        assertThat(service).contains("pendingDeactivate");
    }
}
