package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NovaHumanHandoffService {
    private final AppNovaConversationMapper mapper;
    private final AppSupportService supportService;
    private static final String UUID = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    public record NovaHandoffConfirmRequest(String conversationId, String turnId) {}

    public ApiResult<ContentConversationDetail> confirm(Long userId, String browserKey, NovaHandoffConfirmRequest request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (browserKey == null || browserKey.isBlank() || browserKey.length() > 128) return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (request == null || request.conversationId() == null || !request.conversationId().matches(UUID)
                || request.turnId() == null || !request.turnId().matches(UUID)) return ApiResult.fail(400, "NOVA_HANDOFF_INPUT_INVALID");
        String conversationId = request.conversationId().toLowerCase(Locale.ROOT);
        var trigger = mapper.turn(userId, request.turnId().toLowerCase(Locale.ROOT));
        if (trigger == null || !conversationId.equalsIgnoreCase(trigger.conversationId())) return ApiResult.fail(404, "NOVA_HANDOFF_TURN_NOT_FOUND");
        // Confirmation itself is an explicit request for a human. Classification only supplies routing context.
        String reason = NovaHumanHandoffPolicy.reason(trigger.userMessage(), "");
        if (reason.isBlank()) reason = "USER_REQUEST";
        StringBuilder context = new StringBuilder("Nova → human / ").append(reason).append("\n");
        for (var turn : mapper.turns(userId, conversationId, request.turnId().toLowerCase(Locale.ROOT), 4)) {
            context.append(NovaHumanHandoffPolicy.redact(turn.userMessage())).append("\n");
        }
        context.append(NovaHumanHandoffPolicy.redact(trigger.userMessage()));
        String openingText = context.substring(0, Math.min(context.length(), 2000));
        // Same fixed trigger always recovers the same durable SUPPORT conversation, even after reload/later AI turns.
        String idempotencyKey = "nova-handoff:" + conversationId + ":" + trigger.turnId().toLowerCase(Locale.ROOT);
        return supportService.startConversation(userId, idempotencyKey,
                new AppSupportService.StartConversationRequest("SUPPORT", openingText));
    }
}
