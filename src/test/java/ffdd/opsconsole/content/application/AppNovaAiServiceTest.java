package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppNovaAiServiceTest {
    private final NovaAiGateway gateway = mock(NovaAiGateway.class);
    private final NovaAiProperties properties = new NovaAiProperties();
    private final AppNovaAiService service = new AppNovaAiService(gateway, properties);

    @Test
    void disabledProviderFailsClosedBeforeAnyModelCall() {
        properties.setMode(NovaAiProperties.Mode.DISABLED);

        assertThatThrownBy(() -> service.chat(42L,
                new NovaAiChatRequest("hello", "en", List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_DISABLED");

        verify(gateway, never()).chat(any());
    }

    @Test
    void bindsServerOwnedSessionAndDoesNotForwardClientHistory() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setModel("gemma4-e4b-ctx32k:latest");
        properties.setMaxHistoryMessages(4);
        var history = new ArrayList<NovaAiChatRequest.HistoryMessage>();
        for (int i = 0; i < 7; i++) {
            history.add(new NovaAiChatRequest.HistoryMessage(i % 2 == 0 ? "user" : "assistant", "turn-" + i));
        }
        when(gateway.chat(any())).thenReturn("  Safe local reply.  ");

        var response = service.chat(42L, new NovaAiChatRequest("current question", "zh", history));

        assertThat(response.reply()).isEqualTo("Safe local reply.");
        assertThat(response.provider()).isEqualTo("OLLAMA_LOCAL");
        assertThat(response.model()).isEqualTo("gemma4-e4b-ctx32k:latest");
        var request = ArgumentCaptor.forClass(NovaAiGateway.ChatRequest.class);
        verify(gateway).chat(request.capture());
        assertThat(request.getValue().language()).isEqualTo("zh");
        assertThat(request.getValue().sessionId()).isEqualTo("app-user-42");
        assertThat(request.getValue().messages())
                .extracting(NovaAiGateway.Message::content)
                .containsExactly("current question");
    }

    @Test
    void emptyOrOversizedModelOutputIsRejectedAsUnavailable() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(gateway.chat(any())).thenReturn("   ");

        assertThatThrownBy(() -> service.chat(42L,
                new NovaAiChatRequest("hello", "en", List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_RESPONSE_INVALID");
    }
}
