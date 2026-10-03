package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.dto.AppPhoneRuntimeRequest;
import ffdd.opsconsole.device.dto.AppTaskClaimRequest;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.onboarding.application.PhoneInstallationService;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppTaskAssignmentControllerTest {
    private final AppTaskAssignmentService service = mock(AppTaskAssignmentService.class);
    private final PhoneInstallationService installations = mock(PhoneInstallationService.class);
    private final AppTaskAssignmentController controller = new AppTaskAssignmentController(service, installations);

    @Test
    void receiptPaginationRejectsNonIntegerQueryValuesAsA422BusinessError() {
        Authentication authentication = userAuthentication();

        assertThatThrownBy(() -> controller.receipts("abc", "20", null, authentication))
                .isInstanceOf(BizException.class)
                .hasMessage("TASK_RECEIPT_PAGE_INVALID");
        assertThatThrownBy(() -> controller.receipts("0", "99999999999", null, authentication))
                .isInstanceOf(BizException.class)
                .hasMessage("TASK_RECEIPT_PAGE_INVALID");
        verify(service, never()).receipts(7L, 0, 20, null);
    }

    @Test
    void receiptPaginationPassesTheOpaqueCursorWithoutAttemptingToParseIt() {
        Authentication authentication = userAuthentication();
        String cursor = "djJ8MjAyNi0wOC0xMFQxMTo1OXwxMDB8MTA1";

        controller.receipts("0", "20", cursor, authentication);

        verify(service).receipts(7L, 0, 20, cursor);
    }

    @Test
    void phoneRuntimeUsesTheAuthenticatedUserSubject() {
        var request = new AppPhoneRuntimeRequest("phone-a", 20, true, false);
        controller.phoneRuntime(request, userAuthentication());
        verify(service).phoneRuntime(7L, request);
        verify(installations).requireRuntime(any(), eq("phone-a"));

        Authentication admin = mock(Authentication.class);
        when(admin.isAuthenticated()).thenReturn(true);
        when(admin.getPrincipal()).thenReturn("7");
        when(admin.getDetails()).thenReturn(Map.of("subjectType", "ADMIN"));
        assertThat(controller.phoneRuntime(request, admin).getCode()).isEqualTo(403);
        verify(service, never()).phoneRuntime(null, request);
    }

    @Test
    void claimAndCompleteForwardTheInstallationHeaderFromHttpRequests() throws Exception {
        var auth = userAuthentication();
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/tasks/assignments/claim").principal(auth)
                        .header("Idempotency-Key", "claim-key").header("X-Phone-Installation-Id", "phone-a")
                        .contentType("application/json").content("{\"deviceId\":11}"))
                .andExpect(status().isOk());
        verify(installations).requireForDevice(auth, 11L, "phone-a");
        verify(service).claim(7L, "claim-key", new AppTaskClaimRequest(11L));
        mvc.perform(post("/api/tasks/assignments/phone-task/complete").principal(auth)
                        .header("Idempotency-Key", "complete-key").header("X-Phone-Installation-Id", "phone-a")
                        .contentType("application/json").content("{\"resultHash\":\"result\"}"))
                .andExpect(status().isOk());
        verify(installations).requireForTask(auth, "phone-task", "phone-a");
        verify(service).complete(7L, "phone-task", "complete-key",
                new AppTaskCompleteRequest("result", null, null, null, null, null));
    }

    @Test
    void invalidInstallationStopsClaimAndCompleteBeforeTheirBusinessMutation() {
        var auth = userAuthentication();
        doThrow(new BizException(409, "TASK_ASSIGNMENT_PHONE_BINDING_INVALID"))
                .when(installations).requireForDevice(auth, 11L, "phone-b");
        doThrow(new BizException(409, "TASK_ASSIGNMENT_PHONE_BINDING_INVALID"))
                .when(installations).requireForTask(auth, "phone-task", "phone-b");
        assertThatThrownBy(() -> controller.claim(new AppTaskClaimRequest(11L), "claim-key", "phone-b", auth))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        assertThatThrownBy(() -> controller.complete("phone-task", null, "complete-key", "phone-b", auth))
                .hasMessage("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        verifyNoInteractions(service);
    }

    private Authentication userAuthentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        return authentication;
    }
}
