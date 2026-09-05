package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NovaHumanHandoffServiceTest {
    private static final long USER_ID = 42L;
    private static final String CONVERSATION_ID = "6f0b5c55-0ec5-4a31-85eb-1d4531c1e8df";
    private static final String TRIGGER_ID = "8c12eaf3-744d-405e-b2fb-64b3d81267be";

    private final AppNovaConversationMapper mapper = org.mockito.Mockito.mock(AppNovaConversationMapper.class);
    private final AppSupportService supportService = org.mockito.Mockito.mock(AppSupportService.class);
    private final NovaHumanHandoffService service = new NovaHumanHandoffService(mapper, supportService);

    @Test
    void rejectsAnonymousConfirmationBeforeAnyNovaOrSupportLookup() {
        ApiResult<ContentConversationDetail> result = service.confirm(null, "browser-key", request());

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("USER_SUBJECT_REQUIRED");
        verify(mapper, never()).turn(any(), any());
        verify(supportService, never()).startConversation(any(), any(), any());
    }

    @Test
    void hidesAnotherUsersTurnAndNeverCreatesAConversation() {
        when(mapper.turn(USER_ID, TRIGGER_ID)).thenReturn(null);

        ApiResult<ContentConversationDetail> result = service.confirm(USER_ID, "browser-key", request());

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("NOVA_HANDOFF_TURN_NOT_FOUND");
        verify(mapper).turn(USER_ID, TRIGGER_ID);
        verify(supportService, never()).startConversation(any(), any(), any());
    }

    @Test
    void anchorsTheTranscriptToTheConfirmedTurnAndCopiesAtMostFiveRedactedUserQuestions() {
        seedTrustedTrigger();
        when(mapper.turns(USER_ID, CONVERSATION_ID, TRIGGER_ID, 4)).thenReturn(List.of(
                turn("8c12eaf3-744d-405e-b2fb-64b3d81267a1", "first safe question", "assistant output must not copy"),
                turn("8c12eaf3-744d-405e-b2fb-64b3d81267a2", "password: fake-secret", "assistant answer with otp 654321"),
                turn("8c12eaf3-744d-405e-b2fb-64b3d81267a3", "third safe question", "assistant output three"),
                turn("8c12eaf3-744d-405e-b2fb-64b3d81267a4", "fourth safe question", "assistant output four")));
        when(supportService.startConversation(eq(USER_ID), any(), any())).thenReturn(ApiResult.ok(null));

        service.confirm(USER_ID, "browser-key", request());

        ArgumentCaptor<AppSupportService.StartConversationRequest> opening =
                ArgumentCaptor.forClass(AppSupportService.StartConversationRequest.class);
        verify(mapper).turn(USER_ID, TRIGGER_ID);
        verify(mapper).turns(USER_ID, CONVERSATION_ID, TRIGGER_ID, 4);
        verify(supportService).startConversation(eq(USER_ID), eq("nova-handoff:" + CONVERSATION_ID + ":" + TRIGGER_ID),
                opening.capture());
        assertThat(opening.getValue().conversationType()).isEqualTo("SUPPORT");
        assertThat(opening.getValue().openingText()).contains("Nova → human / USER_REQUEST")
                .contains("first safe question", "third safe question", "fourth safe question", "我要人工客服")
                .doesNotContain("fake-secret", "654321", "assistant output", "assistant answer");
        assertThat(opening.getValue().openingText().lines().count()).isLessThanOrEqualTo(6);
    }

    @Test
    void retriesUseTheSameServerDerivedIdempotencyKeyForTheFixedTurn() {
        seedTrustedTrigger();
        when(mapper.turns(USER_ID, CONVERSATION_ID, TRIGGER_ID, 4)).thenReturn(List.of());
        when(supportService.startConversation(eq(USER_ID), any(), any())).thenReturn(ApiResult.ok(null));

        service.confirm(USER_ID, "browser-key", request());
        service.confirm(USER_ID, "browser-key", request());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(supportService, org.mockito.Mockito.times(2)).startConversation(eq(USER_ID), keys.capture(), any());
        assertThat(keys.getAllValues()).containsExactly(
                "nova-handoff:" + CONVERSATION_ID + ":" + TRIGGER_ID,
                "nova-handoff:" + CONVERSATION_ID + ":" + TRIGGER_ID);
    }

    private void seedTrustedTrigger() {
        when(mapper.turn(USER_ID, TRIGGER_ID)).thenReturn(turn(TRIGGER_ID, "我要人工客服", "model output is not copied"));
    }

    private NovaHumanHandoffService.NovaHandoffConfirmRequest request() {
        return new NovaHumanHandoffService.NovaHandoffConfirmRequest(CONVERSATION_ID, TRIGGER_ID);
    }

    private AppNovaConversationMapper.TurnRow turn(String turnId, String userMessage, String assistantReply) {
        return new AppNovaConversationMapper.TurnRow(turnId, CONVERSATION_ID, "zh", userMessage, assistantReply,
                "OLLAMA_LOCAL", "test-model", 1_777_000_000_000L);
    }
}
