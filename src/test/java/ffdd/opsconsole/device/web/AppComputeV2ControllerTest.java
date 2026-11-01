package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ffdd.opsconsole.device.application.ComputeV2ReadService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppComputeV2ControllerTest {
    private final ComputeV2ReadService service=mock(ComputeV2ReadService.class);
    private final AppComputeV2Controller controller=new AppComputeV2Controller(service);

    @Test void userSubjectIsRequiredBeforeServiceReads() {
        for (Authentication auth : new Authentication[]{null,authentication("ADMIN","10"),authentication("USER","0"),authentication("USER","invalid")}) {
            assertThatThrownBy(() -> controller.assignments(auth)).isInstanceOfSatisfying(BizException.class,ex -> assertThat(ex.getCode()).isEqualTo(403));
        }
        verifyNoInteractions(service);
    }

    @Test void assignmentsUnavailableIsActualHttp404SoTheClientMayUseV1() throws Exception {
        when(service.assignments(10L)).thenThrow(new BizException(404,"COMPUTE_V2_NOT_AVAILABLE"));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build();
        mvc.perform(get("/api/tasks/v2/assignments").principal(authentication("USER","10")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("COMPUTE_V2_NOT_AVAILABLE"));
    }

    @Test void authenticatedIdentityAndOpaqueCursorAreForwardedWithoutCallerUserIdOrScope() {
        when(service.settlements(10L,"cursor",20)).thenReturn(Map.of("records",java.util.List.of()));
        controller.settlements("cursor","20",authentication("USER","10"));
        controller.settlement("batch",authentication("USER","10"));
        verify(service).settlements(10L,"cursor",20); verify(service).settlement(10L,"batch");
        for (String limit : new String[]{"-1","1.5","100","invalid"}) assertThatThrownBy(() -> controller.settlements(null,limit,authentication("USER","10"))).hasMessage("COMPUTE_V2_PAGE_INVALID");
    }
    private Authentication authentication(String type,String id) {
        var auth=mock(Authentication.class); when(auth.isAuthenticated()).thenReturn(true); when(auth.getPrincipal()).thenReturn(id);
        when(auth.getDetails()).thenReturn(Map.of("subjectType",type)); return auth;
    }
}
