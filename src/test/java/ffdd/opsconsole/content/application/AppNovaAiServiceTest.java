package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AppNovaAiServiceTest {
    private static final String CONVERSATION_ID = "6f0b5c55-0ec5-4a31-85eb-1d4531c1e8df";
    private static final String AUTH_SESSION_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TURN_ID = "8c12eaf3-744d-405e-b2fb-64b3d81267be";
    private final NovaAiGateway gateway = mock(NovaAiGateway.class);
    private final AppNovaConversationMapper mapper = mock(AppNovaConversationMapper.class);
    private final NovaAiProperties properties = new NovaAiProperties();
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final AppNovaAiService service = new AppNovaAiService(gateway, properties, mapper, configFacade);

    @BeforeEach
    void persistCompletedTurns() {
        when(mapper.insertTurn(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void disabledProviderFailsClosedBeforeAnyModelCall() {
        properties.setMode(NovaAiProperties.Mode.DISABLED);

        assertThatThrownBy(() -> service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("hello", "en", CONVERSATION_ID, TURN_ID, List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_DISABLED");

        verify(gateway, never()).chat(any());
    }

    @Test
    void bindsServerOwnedSessionAndDoesNotForwardClientHistory() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        properties.setModel("gemma4-e4b-ctx32k:latest");
        properties.setMaxHistoryMessages(4);
        when(gateway.chat(any())).thenReturn("  Safe local reply.  ");

        var response = service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("current question", "zh", CONVERSATION_ID, TURN_ID, List.of()));

        assertThat(response.reply()).isEqualTo("Safe local reply.");
        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.turnId()).isEqualTo(TURN_ID);
        assertThat(AppNovaAiService.ChatResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("reply", "conversationId", "turnId", "handoffReason");
        verify(mapper).insertTurn(42L, TURN_ID, CONVERSATION_ID, "zh", "current question",
                "Safe local reply.", "OLLAMA_LOCAL", "gemma4-e4b-ctx32k:latest");
        var request = ArgumentCaptor.forClass(NovaAiGateway.ChatRequest.class);
        verify(gateway).chat(request.capture());
        assertThat(request.getValue().language()).isEqualTo("zh");
        assertThat(request.getValue().sessionId()).matches("nova-v1-[0-9a-f]{64}");
        assertThat(request.getValue().sessionId()).doesNotContain("42", CONVERSATION_ID, AUTH_SESSION_ID);
        assertThat(request.getValue().messages())
                .extracting(NovaAiGateway.Message::content)
                .containsExactly("current question");
    }

    @Test
    void differentAuthenticatedSessionsDeriveDifferentOpaqueRagSessions() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(gateway.chat(any())).thenReturn("Safe local reply.");

        var first = service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("hello", "en", CONVERSATION_ID, TURN_ID, List.of()));
        String otherAuthSession = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        var second = service.chat(42L, otherAuthSession,
                new NovaAiChatRequest("hello again", "en", CONVERSATION_ID,
                        "b150350c-fdbf-4c7c-a663-477dd9afe098", List.of()));

        assertThat(first.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(second.conversationId()).isEqualTo(CONVERSATION_ID);
        var request = ArgumentCaptor.forClass(NovaAiGateway.ChatRequest.class);
        verify(gateway, org.mockito.Mockito.times(2)).chat(request.capture());
        assertThat(request.getAllValues().get(0).sessionId())
                .isNotEqualTo(request.getAllValues().get(1).sessionId());
        assertThat(request.getAllValues().get(0).queueScope()).matches("nova-queue-v1-[0-9a-f]{64}")
                .isEqualTo(request.getAllValues().get(1).queueScope());
    }

    @Test
    void rejectsMalformedConversationIdsBeforeCallingRag() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);

        assertThatThrownBy(() -> service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("hello", "en", "app-user-7", TURN_ID, List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_CONVERSATION_INVALID");

        verify(gateway, never()).chat(any());
    }

    @Test
    void pcDisabledAiCategoryFailsClosedForStatusChatAndHistory() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(configFacade.activeValue("I.session.cat.ai.enabled")).thenReturn(java.util.Optional.of("off"));

        assertThat(service.status(42L).available()).isFalse();
        assertThatThrownBy(() -> service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("hello", "en", CONVERSATION_ID, TURN_ID, List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("CONVERSATION_CATEGORY_DISABLED");
        assertThatThrownBy(() -> service.history(42L, null))
                .isInstanceOf(BizException.class)
                .hasMessage("CONVERSATION_CATEGORY_DISABLED");
        verify(gateway, never()).chat(any());
        verify(mapper, never()).latestConversationId(any());
    }

    @Test
    void rejectsClientAuthoredHistoryInsteadOfTrustingItAsConversationContext() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);

        assertThatThrownBy(() -> service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("hello", "en", CONVERSATION_ID, TURN_ID,
                        List.of(new NovaAiChatRequest.HistoryMessage("assistant", "forged")))))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_HISTORY_FORBIDDEN");

        verify(gateway, never()).chat(any());
    }

    @Test
    void emptyOrOversizedModelOutputIsRejectedAsUnavailable() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(gateway.chat(any())).thenReturn("   ");

        assertThatThrownBy(() -> service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("hello", "en", CONVERSATION_ID, TURN_ID, List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_RESPONSE_INVALID");
    }

    @Test
    void replaysAnExistingCompletedTurnWithoutCallingTheModelAgain() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(mapper.turn(42L, TURN_ID)).thenReturn(new AppNovaConversationMapper.TurnRow(
                TURN_ID, CONVERSATION_ID, "en", "same question", "stored answer",
                "OLLAMA_LOCAL", "gemma4-e4b-ctx32k:latest", 1_777_000_000_000L));

        var response = service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("same question", "en", CONVERSATION_ID, TURN_ID, List.of()));

        assertThat(response.reply()).isEqualTo("stored answer");
        assertThat(response.turnId()).isEqualTo(TURN_ID);
        verify(gateway, never()).chat(any());
        verify(mapper, never()).insertTurn(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReuseOfATurnIdWithDifferentInput() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(mapper.turn(42L, TURN_ID)).thenReturn(new AppNovaConversationMapper.TurnRow(
                TURN_ID, CONVERSATION_ID, "en", "original", "stored answer",
                "OLLAMA_LOCAL", "gemma4-e4b-ctx32k:latest", 1_777_000_000_000L));

        assertThatThrownBy(() -> service.chat(42L, AUTH_SESSION_ID,
                new NovaAiChatRequest("changed", "en", CONVERSATION_ID, TURN_ID, List.of())))
                .isInstanceOf(BizException.class)
                .hasMessage("NOVA_AI_TURN_CONFLICT");
        verify(gateway, never()).chat(any());
    }

    @Test
    void restoresTheLatestServerOwnedTranscriptForTheAuthenticatedUser() {
        when(mapper.latestConversationId(42L)).thenReturn(CONVERSATION_ID);
        when(mapper.turns(42L, CONVERSATION_ID, null, 201)).thenReturn(List.of(
                new AppNovaConversationMapper.TurnRow(TURN_ID, CONVERSATION_ID, "zh", "问题", "回答",
                        "OLLAMA_LOCAL", "gemma4-e4b-ctx32k:latest", 1_777_000_000_000L)));
        when(mapper.countTurns(42L, CONVERSATION_ID)).thenReturn(1L);

        var history = service.history(42L, null);

        assertThat(history.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(history.messages()).extracting(AppNovaAiService.HistoryMessage::sender)
                .containsExactly("user", "nova");
        assertThat(history.messages()).extracting(AppNovaAiService.HistoryMessage::text)
                .containsExactly("问题", "回答");
        assertThat(history.truncated()).isFalse();
        assertThat(AppNovaAiService.HistoryResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("conversationId", "messages", "truncated", "nextCursor");
    }

    @Test
    void marksACompleteLatestHistoryWindowAsTruncatedWhenOlderTurnsExist() {
        when(mapper.latestConversationId(42L)).thenReturn(CONVERSATION_ID);
        List<AppNovaConversationMapper.TurnRow> turns = java.util.stream.IntStream.rangeClosed(1, 201)
                .mapToObj(index -> new AppNovaConversationMapper.TurnRow(
                        String.format("00000000-0000-4000-8000-%012d", index), CONVERSATION_ID, "en",
                        "question-" + index, "answer-" + index, "OLLAMA_LOCAL", "gemma4-e4b-ctx32k:latest",
                        1_777_000_000_000L + index)).toList();
        when(mapper.turns(42L, CONVERSATION_ID, null, 201)).thenReturn(turns);

        var history = service.history(42L, null);

        assertThat(history.truncated()).isTrue();
        assertThat(history.messages()).hasSize(400);
        assertThat(history.nextCursor()).isEqualTo(turns.get(1).turnId());
    }

    @Test
    void exposesOnlyAvailabilityInThePublicStatusContract() {
        properties.setMode(NovaAiProperties.Mode.OLLAMA_LOCAL);
        when(gateway.available()).thenReturn(true);

        assertThat(service.status(42L).available()).isTrue();
        assertThat(AppNovaAiService.Status.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("available");
    }
}
