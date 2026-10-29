package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportConversationHistoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupportConversationHistoryControllerTest {
    @Test void onlyTheServerCursorIsBoundAndTheReadPermissionRemainsRequired() throws Exception {
        var service=mock(SupportConversationHistoryService.class);var controller=new SupportConversationHistoryController(service);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();var url="/api/admin/content/conversations/CV-1/messages";
        mvc.perform(get(url)).andExpect(status().isOk());mvc.perform(get(url).param("beforeMessageId","12")).andExpect(status().isOk());
        mvc.perform(get(url).param("beforeMessageId","not-an-id")).andExpect(status().isBadRequest());
        verify(service).page("CV-1",null);verify(service).page("CV-1",12L);verifyNoMoreInteractions(service);
        assertThat(SupportConversationHistoryController.class.getMethod("messages",String.class,Long.class).getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value()).isEqualTo("hasAuthority('service_m3_read')");
    }
}
