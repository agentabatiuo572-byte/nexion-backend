package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.content.mapper.AppNovaConversationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppNovaAiService {
    private static final int HISTORY_TURN_WINDOW = 200;
    private static final String UUID_V4 = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String AUTH_SESSION_ID = "[0-9a-f]{64}";
    private final NovaAiGateway gateway;
    private final NovaAiProperties properties;
    private final AppNovaConversationMapper mapper;
    private final PlatformConfigFacade configFacade;
    private final AtomicInteger activeRequests = new AtomicInteger();
    // Entries exist only for admitted requests (at most the global capacity).
    // These are fail-fast guards, not unbounded queues or long-held DB locks.
    private final Map<String, ActiveTurn> activeTurns = new HashMap<>();
    private final Map<String, ActiveTurn> activeConversations = new HashMap<>();

    public Status status(Long userId) {
        requireUser(userId);
        boolean enabled = ConversationCategoryPolicy.enabled(configFacade, "ai")
                && properties.getMode() == NovaAiProperties.Mode.OLLAMA_LOCAL;
        return new Status(enabled && gateway.available());
    }

    public ChatResponse chat(Long userId, String authenticatedSessionId, NovaAiChatRequest request) {
        requireUser(userId);
        requireAuthenticatedSession(authenticatedSessionId);
        requireCategoryEnabled();
        requireEnabled();
        validate(request);
        String turnId = request.turnId().toLowerCase();
        String conversationId = request.conversationId().toLowerCase();
        String message = request.message().trim();
        AppNovaConversationMapper.TurnRow existing = mapper.turn(userId, turnId);
        if (existing != null) return replay(existing, conversationId, request.language(), message);
        ActiveTurn active = reserve(userId, conversationId, turnId, request.language(), message);
        try {
            // A previous request may have committed between the first read and
            // reservation. Recheck after reservation before invoking the model.
            existing = mapper.turn(userId, turnId);
            if (existing != null) return replay(existing, conversationId, request.language(), message);
            List<NovaAiGateway.Message> messages = List.of(
                    new NovaAiGateway.Message("user", message));
            String reply = gateway.chat(new NovaAiGateway.ChatRequest(
                    properties.getModel(), request.language(),
                    deriveRagSessionId(userId, authenticatedSessionId, conversationId),
                    List.copyOf(messages),
                    Math.max(64, Math.min(2_048, properties.getMaxOutputTokens())), turnId,
                    deriveRagQueueScope(userId, conversationId)));
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
            release(active);
        }
    }

    private synchronized ActiveTurn reserve(Long userId, String conversationId, String turnId,
                                             String language, String message) {
        String turnKey = userId + ":" + turnId;
        String conversationKey = userId + ":" + conversationId;
        ActiveTurn existing = activeTurns.get(turnKey);
        if (existing != null) {
            if (!existing.conversationKey().equals(conversationKey)
                    || !existing.language().equals(language) || !existing.message().equals(message)) {
                throw new BizException(409, "NOVA_AI_TURN_CONFLICT");
            }
            throw new BizException(429, "NOVA_AI_TURN_IN_PROGRESS");
        }
        if (activeConversations.containsKey(conversationKey)) {
            throw new BizException(429, "NOVA_AI_CONVERSATION_BUSY");
        }
        if (!tryAcquire()) throw new BizException(429, "NOVA_AI_BUSY");
        ActiveTurn active = new ActiveTurn(turnKey, conversationKey, language, message);
        activeTurns.put(turnKey, active);
        activeConversations.put(conversationKey, active);
        return active;
    }

    private synchronized void release(ActiveTurn active) {
        activeTurns.remove(active.turnKey(), active);
        activeConversations.remove(active.conversationKey(), active);
        activeRequests.decrementAndGet();
    }

    private record ActiveTurn(String turnKey, String conversationKey, String language, String message) {}

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

    private void requireCategoryEnabled() {
        if (!ConversationCategoryPolicy.enabled(configFacade, "ai")) {
            throw new BizException(409, "CONVERSATION_CATEGORY_DISABLED");
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
        return history(userId, requestedConversationId, null);
    }

    public HistoryResponse history(Long userId, String requestedConversationId, String beforeTurnId) {
        requireUser(userId);
        requireCategoryEnabled();
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
        String cursor = beforeTurnId == null || beforeTurnId.isBlank() ? null : beforeTurnId.toLowerCase();
        if (cursor != null && !cursor.matches(UUID_V4)) throw new BizException(400, "NOVA_AI_HISTORY_CURSOR_INVALID");
        List<AppNovaConversationMapper.TurnRow> fetched = mapper.turns(
                userId, conversationId, cursor, HISTORY_TURN_WINDOW + 1);
        boolean truncated = fetched.size() > HISTORY_TURN_WINDOW;
        List<AppNovaConversationMapper.TurnRow> turns = truncated
                ? fetched.subList(1, fetched.size()) : fetched;
        List<HistoryMessage> messages = turns.stream()
                .flatMap(turn -> List.of(
                        new HistoryMessage(turn.turnId() + ":user", "user", turn.userMessage(),
                                time(turn.createdAtEpochMs())),
                        new HistoryMessage(turn.turnId() + ":nova", "nova", turn.assistantReply(),
                                time(turn.createdAtEpochMs()) + 1L)).stream())
                .toList();
        String nextCursor = truncated && !turns.isEmpty() ? turns.get(0).turnId() : null;
        return historyResponse(conversationId, messages, truncated, nextCursor);
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
        return historyResponse(conversationId, messages, false, null);
    }

    private HistoryResponse historyResponse(String conversationId, List<HistoryMessage> messages, boolean truncated) {
        return historyResponse(conversationId, messages, truncated, null);
    }

    private HistoryResponse historyResponse(String conversationId, List<HistoryMessage> messages,
            boolean truncated, String nextCursor) {
        return new HistoryResponse(conversationId, List.copyOf(messages), truncated, nextCursor);
    }

    private long time(Long epochMs) {
        return epochMs == null || epochMs <= 0 ? 1L : epochMs;
    }

    private String deriveRagQueueScope(Long userId, String conversationId) {
        // Idempotency has the same user+conversation scope as durable history.
        // Login-session changes must not start a second copy of an unfinished turn.
        return "nova-queue-v1-" + digest(userId + "\u0000" + conversationId);
    }

    private String deriveRagSessionId(Long userId, String authenticatedSessionId, String conversationId) {
        return "nova-v1-" + digest(authenticatedSessionId + "\u0000" + userId + "\u0000" + conversationId.toLowerCase());
    }

    private String digest(String scope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Status(boolean available) {}

    public record ChatResponse(String reply, String conversationId, String turnId) {}

    public record HistoryMessage(String id, String sender, String text, long ts) {}

    public record HistoryResponse(String conversationId, List<HistoryMessage> messages, boolean truncated,
            String nextCursor) {}
}
