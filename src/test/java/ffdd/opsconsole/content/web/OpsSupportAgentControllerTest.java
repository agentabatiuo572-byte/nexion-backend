package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.dto.SupportAgentAssignmentRequest;
import ffdd.opsconsole.content.dto.SupportAgentProfileUpdateRequest;
import ffdd.opsconsole.content.dto.SupportAgentSeatAssignmentRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsSupportAgentControllerTest {
    private final OpsSupportAgentService service = mock(OpsSupportAgentService.class);
    private final ProductionSupportPathGuard productionPathGuard = mock(ProductionSupportPathGuard.class);
    private final OpsSupportAgentController controller = new OpsSupportAgentController(service, productionPathGuard);

    @Test
    void isolatedProfileBlocksSeatMutationBeforeTheOfficialAgentService() {
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(409, "SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireOpsWriteAllowed();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.updateProfile(7L, "key", null))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        verify(service, never()).updateProfile(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void everyM1SeatWriteRejectsAWriteAuthorityHolderWhoIsNotASupportSupervisor() {
        when(service.canManageSupportSeats()).thenReturn(false);
        SupportAgentProfileUpdateRequest profile = new SupportAgentProfileUpdateRequest(
                "通用客服", List.of("support"), List.of(), 8, true, true, false, "risk", "routine profile update");
        SupportAgentSeatAssignmentRequest seat = new SupportAgentSeatAssignmentRequest(
                "通用客服", List.of("support"), List.of(), 8, true, true, false, List.of(), "risk", "routine seat update");
        SupportAgentAssignmentRequest assignment = new SupportAgentAssignmentRequest(1001L, "risk", "routine user binding");

        assertThat(controller.updateProfile(7L, "idem-profile", profile).getCode()).isEqualTo(403);
        assertThat(controller.assignSeat(7L, "idem-seat", seat).getCode()).isEqualTo(403);
        assertThat(controller.assignAdvisorUser(7L, "idem-assign", assignment).getCode()).isEqualTo(403);
        assertThat(controller.deactivateAdvisorAssignment(7L, 9L, "idem-remove", assignment).getCode()).isEqualTo(403);

        verify(service, never()).updateProfile(7L, "idem-profile", profile);
        verify(service, never()).assignSeat(7L, "idem-seat", seat);
        verify(service, never()).assignAdvisorUser(7L, "idem-assign", assignment);
        verify(service, never()).deactivateAdvisorAssignment(7L, 9L, "idem-remove", assignment);
    }
}
