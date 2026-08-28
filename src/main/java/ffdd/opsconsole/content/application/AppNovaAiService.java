package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppNovaAiService {
    private static final String UUID_V4 = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String AUTH_SESSION_ID = "[0-9a-f]{64}";
    private final NovaAiGateway gateway;
    private final NovaAiProperties properties;
    private final AppNovaConversationMapper mapper;
    private final AtomicInteger activeRequests = new AtomicInteger();

    public Status status(Long userId) {
        requireUser(userId);
        boolean enabled = properties.getMode() == NovaAiProperties.Mode.OLLAMA_LOCAL;
        return new Status(enabled && gateway.available());
    }

    public ChatResponse chat(Long userId, String authenticatedSessionId, NovaAiChatRequest request) {
        requireUser(userId);
        requireAuthenticatedSession(authenticatedSessionId);
        requireEnabled();
        validate(request);
        String turnId = request.turnId().toLowerCase();
        String conversationId = request.conversationId().toLowerCase();
        String message = request.message().trim();
        AppNovaConversationMapper.TurnRow existing = mapper.turn(userId, turnId);
        if (existing != null) return replay(existing, conversationId, request.language(), message);
        if (!tryAcquire()) throw new BizException(429, "NOVA_AI_BUSY");
        try {
            List<NovaAiGateway.Message> messages = List.of(
                    new NovaAiGateway.Message("user", message));
            String reply = gateway.chat(new NovaAiGateway.ChatRequest(
                    properties.getModel(), request.language(),
                    deriveRagSessionId(userId, authenticatedSessionId, conversationId),
                    List.copyOf(messages),
                    Math.max(64, Math.min(2_048, properties.getMaxOutputTokens()))));
            if (reply == null || reply.isBlank()
                    || reply.length() > Math.max(256, Math.min(16_000, properties.getMaxOutputChars()))) {
                throw new BizException(502, "NOVA_AI_RESPONSE_INVALID");
            }
            String answer = reply.trim();
            try {
                if (mapper.insertTurn(userId, turnId, conversationId, request.language(), message,
                        answer, "OLLAMA_LOCAL", properties.getModel()) != 1) {
                    throw new BizException(503, "NOVA_AI_HISTORY_WRITE_FAILED");
                }
            } catch (DuplicateKeyException duplicate) {
                AppNovaConversationMapper.TurnRow winner = mapper.turn(userId, turnId);
                if (winner == null) throw new BizException(503, "NOVA_AI_HISTORY_WRITE_FAILED");
                return replay(winner, conversationId, request.language(), message);
            }
            return response(answer, conversationId, turnId);
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private boolean tryAcquire() {
        int limit = Math.max(1, Math.min(4, properties.getMaxConcurrentRequests()));
        while (true) {
            int current = activeRequests.get();
            if (current >= limit) return false;
            if (activeRequests.compareAndSet(current, current + 1)) return true;
        }
    }

    private void requireEnabled() {
        if (properties.getMode() != NovaAiProperties.Mode.OLLAMA_LOCAL) {
            throw new BizException(503, "NOVA_AI_DISABLED");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_SUBJECT_REQUIRED");
    }

    private void requireAuthenticatedSession(String authenticatedSessionId) {
        if (authenticatedSessionId == null || !authenticatedSessionId.matches(AUTH_SESSION_ID)) {
            throw new BizException(403, "USER_SESSION_REQUIRED");
        }
    }

    private void validate(NovaAiChatRequest request) {
        int maxInput = Math.max(1, Math.min(2_000, properties.getMaxInputChars()));
        if (request == null || request.message() == null || request.message().trim().isEmpty()
                || request.message().length() > maxInput || !List.of("en", "zh", "vi").contains(request.language())) {
            throw new BizException(400, "NOVA_AI_INPUT_INVALID");
        }
        if (request.conversationId() == null || !request.conversationId().matches(UUID_V4)) {
            throw new BizException(400, "NOVA_AI_CONVERSATION_INVALID");
        }
        if (request.turnId() == null || !request.turnId().matches(UUID_V4)) {
            throw new BizException(400, "NOVA_AI_TURN_INVALID");
        }
        List<NovaAiChatRequest.HistoryMessage> history = request.history() == null ? List.of() : request.history();
        if (!history.isEmpty()) {
            throw new BizException(400, "NOVA_AI_HISTORY_FORBIDDEN");
        }
    }

    public HistoryResponse history(Long userId, String requestedConversationId) {
        requireUser(userId);
        String conversationId = requestedConversationId;
        if (conversationId != null && !conversationId.isBlank()) {
            if (!conversationId.matches(UUID_V4)) throw new BizException(400, "NOVA_AI_CONVERSATION_INVALID");
            conversationId = conversationId.toLowerCase();
        } else {
            conversationId = mapper.latestConversationId(userId);
        }
        if (conversationId == null || conversationId.isBlank()) {
            return historyResponse(null, List.of());
        }
        List<HistoryMessage> messages = mapper.turns(userId, conversationId).stream()
                .flatMap(turn -> List.of(
                        new HistoryMessage(turn.turnId() + ":user", "user", turn.userMessage(),
                                time(turn.createdAtEpochMs())),
                        new HistoryMessage(turn.turnId() + ":nova", "nova", turn.assistantReply(),
                                time(turn.createdAtEpochMs()) + 1L)).stream())
                .toList();
        return historyResponse(conversationId, messages);
    }

    private ChatResponse replay(AppNovaConversationMapper.TurnRow turn, String conversationId,
                                String language, String message) {
        if (!conversationId.equalsIgnoreCase(turn.conversationId())
                || !language.equals(turn.language()) || !message.equals(turn.userMessage())) {
            throw new BizException(409, "NOVA_AI_TURN_CONFLICT");
        }
        return response(turn.assistantReply(), turn.conversationId().toLowerCase(), turn.turnId().toLowerCase());
    }

    private ChatResponse response(String reply, String conversationId, String turnId) {
        return new ChatResponse(reply, conversationId, turnId);
    }

    private HistoryResponse historyResponse(String conversationId, List<HistoryMessage> messages) {
        return new HistoryResponse(conversationId, List.copyOf(messages));
    }

    private long time(Long epochMs) {
        return epochMs == null || epochMs <= 0 ? 1L : epochMs;
    }

    private String deriveRagSessionId(Long userId, String authenticatedSessionId, String conversationId) {
        try {
            String scope = authenticatedSessionId + "\u0000" + userId + "\u0000" + conversationId.toLowerCase();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return "nova-v1-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Status(boolean available) {}

    public record ChatResponse(String reply, String conversationId, String turnId) {}

    public record HistoryMessage(String id, String sender, String text, long ts) {}

    public record HistoryResponse(String conversationId, List<HistoryMessage> messages) {}
}
