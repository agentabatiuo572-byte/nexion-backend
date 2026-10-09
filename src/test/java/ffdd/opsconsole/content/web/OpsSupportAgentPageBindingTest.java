package ffdd.opsconsole.content.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportGroupService;
import ffdd.opsconsole.content.dto.SupportAgentQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsSupportAgentPageBindingTest {
    final OpsSupportAgentService service = mock(OpsSupportAgentService.class);
    final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new OpsSupportAgentController(service, mock(ProductionSupportPathGuard.class), mock(SupportGroupService.class))).build();

    @Test void realSpringQueryBindingPreservesGroupedAndLegacyPagination() throws Exception {
        when(service.agents(any())).thenReturn(ApiResult.ok(null));
        mvc.perform(get("/api/admin/content/support-agents/page").param("pageNum", "2").param("pageSize", "20").param("groupId", "410")).andExpect(status().isOk());
        verify(service).agents(new SupportAgentQueryRequest(2L, 20L, 410L));
        mvc.perform(get("/api/admin/content/support-agents/page").param("pageNum", "1").param("pageSize", "10")).andExpect(status().isOk());
        verify(service).agents(new SupportAgentQueryRequest(1L, 10L));
        mvc.perform(get("/api/admin/content/support-agents/page")).andExpect(status().isOk());
        verify(service).agents(new SupportAgentQueryRequest(null, null));
    }

    @Test void invalidQueryNumbersCannotReachTheService() throws Exception {
        for (String parameter : new String[]{"pageNum", "pageSize", "groupId"}) {
            mvc.perform(get("/api/admin/content/support-agents/page").param(parameter, "invalid")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/content/support-agents/page").param(parameter, "9223372036854775808")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
