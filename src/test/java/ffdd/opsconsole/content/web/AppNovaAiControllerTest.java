package ffdd.opsconsole.content.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppNovaAiService;
import ffdd.opsconsole.content.application.NovaHumanHandoffService;
import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AppNovaAiControllerTest {
    private static final String AUTH_SESSION_ID =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CONVERSATION_ID = "6f0b5c55-0ec5-4a31-85eb-1d4531c1e8df";
    private static final String TURN_ID = "8c12eaf3-744d-405e-b2fb-64b3d81267be";

    private final AppNovaAiService service = mock(AppNovaAiService.class);
    private final AppNovaAiController controller = new AppNovaAiController(service, mock(NovaHumanHandoffService.class));

    @Test
    void passesAuthenticatedUserAndServerSessionToTheConversationService() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("42");
        when(authentication.getDetails()).thenReturn(Map.of(
                "subjectType", "USER",
                "sessionId", AUTH_SESSION_ID));
        var request = new NovaAiChatRequest("NexGrid 保证赚钱吗？", "zh", CONVERSATION_ID, TURN_ID, List.of());
        when(service.chat(42L, AUTH_SESSION_ID, request)).thenReturn(
                new AppNovaAiService.ChatResponse("不保证。", CONVERSATION_ID, TURN_ID, ""));

        var result = controller.chat(request, authentication);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversationId()).isEqualTo(CONVERSATION_ID);
        verify(service).chat(42L, AUTH_SESSION_ID, request);
    }

    @Test
    void doesNotAcceptAnAdminIdentityAsAnAppUserSession() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("42");
        when(authentication.getDetails()).thenReturn(Map.of(
                "subjectType", "ADMIN",
                "sessionId", AUTH_SESSION_ID));
        var request = new NovaAiChatRequest("hello", "en", CONVERSATION_ID, TURN_ID, List.of());
        when(service.chat(null, null, request)).thenThrow(new IllegalStateException("rejected by service"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.chat(request, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rejected by service");
        verify(service).chat(null, null, request);
    }

    @Test
    void returnsTheAuthenticatedUsersLatestNovaHistory() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("42");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER", "sessionId", AUTH_SESSION_ID));
        var history = new AppNovaAiService.HistoryResponse(CONVERSATION_ID, List.of(), false, null);
        when(service.history(42L, null, null)).thenReturn(history);

        var result = controller.history(null, null, authentication);

        assertThat(result.getData()).isSameAs(history);
        verify(service).history(42L, null, null);
    }

    @Test
    void publicDtosDoNotSerializeInternalRuntimeOrPersistenceMetadata() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String status = mapper.writeValueAsString(new AppNovaAiService.Status(true));
        String chat = mapper.writeValueAsString(
                new AppNovaAiService.ChatResponse("回答", CONVERSATION_ID, TURN_ID, ""));
        String history = mapper.writeValueAsString(
                new AppNovaAiService.HistoryResponse(CONVERSATION_ID, List.of(), false, null));

        assertThat(status).isEqualTo("{\"available\":true}");
        assertThat(chat).containsOnlyOnce("\"reply\"")
                .contains("\"conversationId\"", "\"turnId\"");
        assertThat(history).contains("\"conversationId\"", "\"messages\"", "\"truncated\"");
        assertThat(status + chat + history).doesNotContain(
                "provider", "model", "privacy", "source", "sourceEnvironment", "runId", "serverCanonical");
    }
}
