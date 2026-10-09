package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.shared.exception.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** Real MVC/advice status contract. Method-security/filter-chain and MySQL tests remain separate NOT_RUN gates. */
class SupportWorkbenchAnalyticsControllerTest {
    static final String PATH=ffdd.opsconsole.common.api.OpsAdminApi.ADMIN_PREFIX+"/content/support-workbench/analytics";
    record Fixture(MockMvc mvc,SupportAnalyticsPrivateQueryService analytics,ProductionSupportPathGuard guard) { }
    Fixture fixture() {
        var analytics=mock(SupportAnalyticsPrivateQueryService.class);var guard=mock(ProductionSupportPathGuard.class);
        var controller=new SupportWorkbenchController(mock(SupportWorkbenchService.class),mock(SupportMaintenanceService.class),guard,mock(SupportCustomerProfileService.class),analytics);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build();
        return new Fixture(mvc,analytics,guard);
    }
    @Test void versionConflictUsesActualHttp409AndContainsNoCurrentTokenOrEvidence() throws Exception {
        var f=fixture();when(f.analytics.query(anyMap())).thenThrow(new BizException(409,"SUPPORT_ANALYTICS_QUERY_CHANGED"));
        var result=f.mvc.perform(get(PATH).param("pageNum","2").param("expectedVersion","saq-v1:"+"0".repeat(64))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("SUPPORT_ANALYTICS_QUERY_CHANGED").doesNotContain("queryVersion","sourceIds","currentToken","evidence");
        verify(f.guard).requireOpsWriteAllowed();
    }
    @Test void invalidQueryAndRevokedScopeHaveTrueHttp422And403() throws Exception {
        var f=fixture();when(f.analytics.query(anyMap())).thenThrow(new BizException(422,"SUPPORT_ANALYTICS_VERSION_REQUIRED"));
        f.mvc.perform(get(PATH).param("pageNum","2")).andExpect(status().isUnprocessableEntity());
        doThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN")).when(f.analytics).query(anyMap());
        f.mvc.perform(get(PATH).param("expectedVersion","saq-v1:"+"0".repeat(64))).andExpect(status().isForbidden());
    }
    @Test void productionPathGuardRunsBeforeAnalyticsAndExistingRbacExpressionIsUnchanged() throws Exception {
        var f=fixture();doThrow(new BizException(503,"SUPPORT_PATH_UNAVAILABLE")).when(f.guard).requireOpsWriteAllowed();
        f.mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable());verifyNoInteractions(f.analytics);
        var method=SupportWorkbenchController.class.getMethod("analytics",org.springframework.util.MultiValueMap.class);
        assertThat(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value()).isEqualTo("hasAnyAuthority('service_m1_read','service_m3_read')");
    }
    @Test void duplicateAndScopeParametersReachStrictDtoValidationAs422() throws Exception {
        var f=fixture();when(f.analytics.query(anyMap())).thenAnswer(inv->{ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.fromParameters(inv.getArgument(0));return java.util.Map.of();});
        f.mvc.perform(get(PATH).param("view","CUSTOMERS","FINANCE")).andExpect(status().isUnprocessableEntity());
        f.mvc.perform(get(PATH).param("mode","ALL")).andExpect(status().isUnprocessableEntity());
    }
}
