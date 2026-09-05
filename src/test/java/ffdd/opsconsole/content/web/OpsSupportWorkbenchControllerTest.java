package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsSupportWorkbenchControllerTest {
    private final OpsUserService users = mock(OpsUserService.class);
    private final OpsSupportAgentService agents = mock(OpsSupportAgentService.class);
    private final OpsSupportWorkbenchController controller = new OpsSupportWorkbenchController(mock(OpsDeviceService.class), users, agents);

    @Test
    void usersRouteUsesTheScopedPhoneSearchAndKeepsCanonicalPagination() {
        var query = UserQueryRequest.basic("3775", null, null, 2, 8, null);
        when(agents.canManageSupportSeats()).thenReturn(true);
        when(users.supportProfilePage(query)).thenReturn(ApiResult.ok(new PageResult<>(17, 2, 8, List.of())));
        var result = controller.advisorUsers(query);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isEqualTo(17);
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        verify(users, never()).profilePage(any());
    }

    @Test
    void advisorRouteRequiresWriteAuthorityAndPropagatesValidationFailure() throws Exception {
        assertThat(OpsSupportWorkbenchController.class.getMethod("advisorUsers", UserQueryRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('service_m1_write')");
        when(agents.canManageSupportSeats()).thenReturn(true);
        var query = UserQueryRequest.basic("3775", null, null, 0, 8, null);
        when(users.supportProfilePage(query)).thenReturn(ApiResult.fail(422, "C1_PAGE_NUM_INVALID"));
        assertThat(controller.advisorUsers(query).getMessage()).isEqualTo("C1_PAGE_NUM_INVALID");
    }

    @Test
    void rejectsNonSupervisorBeforeAnyPhoneLookup() {
        var query = UserQueryRequest.basic("13800138000", null, null, 1, 8, null);
        assertThat(controller.advisorUsers(query).getCode()).isEqualTo(403);
        verifyNoInteractions(users);
    }

    @Test
    void ordinaryWorkbenchRetainsRawPhoneRestriction() {
        var query = UserQueryRequest.basic("13800138000", null, null, 1, 8, null);
        when(users.profilePage(query)).thenReturn(ApiResult.fail(422, "C1_RAW_PHONE_SEARCH_FORBIDDEN"));
        assertThat(controller.users(query).getMessage()).isEqualTo("C1_RAW_PHONE_SEARCH_FORBIDDEN");
        verify(users, never()).supportProfilePage(any());
    }

    @Test
    void bindingCandidatesExposeOnlyMaskedIdentity() throws Exception {
        var query = UserQueryRequest.basic("8000", null, null, 1, 8, null);
        UserAccountView row = mock(UserAccountView.class);
        when(row.userId()).thenReturn(739L);
        when(row.userNo()).thenReturn("U00000001");
        when(row.nickname()).thenReturn("Test User");
        when(row.phoneMasked()).thenReturn("138****8000");
        when(agents.canManageSupportSeats()).thenReturn(true);
        when(users.supportProfilePage(query)).thenReturn(ApiResult.ok(new PageResult<>(1, 1, 8, List.of(row))));
        var result = controller.advisorUsers(query);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result.getData().getRecords().get(0));
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("userId").asLong()).isEqualTo(739L);
        assertThat(json.get("phoneMasked").asText()).isEqualTo("138****8000");
        assertThat(json.has("walletUsdt")).isFalse();
        assertThat(json.has("phone")).isFalse();
    }
}
