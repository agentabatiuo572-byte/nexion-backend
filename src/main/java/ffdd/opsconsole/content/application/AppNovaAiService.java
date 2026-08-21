package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.NovaAiChatRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppNovaAiService {
    private final NovaAiGateway gateway;
    private final NovaAiProperties properties;
    private final AtomicInteger activeRequests = new AtomicInteger();

    public Status status(Long userId) {
        requireUser(userId);
        boolean enabled = properties.getMode() == NovaAiProperties.Mode.OLLAMA_LOCAL;
        return new Status(enabled && gateway.available(), "OLLAMA_LOCAL", properties.getModel(), "LOCAL_MACHINE");
    }

    public ChatResponse chat(Long userId, NovaAiChatRequest request) {
        requireUser(userId);
        requireEnabled();
        validate(request);
        if (!tryAcquire()) throw new BizException(429, "NOVA_AI_BUSY");
        try {
            List<NovaAiGateway.Message> messages = List.of(
                    new NovaAiGateway.Message("user", request.message().trim()));
            String reply = gateway.chat(new NovaAiGateway.ChatRequest(
                    properties.getModel(), request.language(), "app-user-" + userId, List.copyOf(messages),
                    Math.max(64, Math.min(2_048, properties.getMaxOutputTokens()))));
            if (reply == null || reply.isBlank()
                    || reply.length() > Math.max(256, Math.min(16_000, properties.getMaxOutputChars()))) {
                throw new BizException(502, "NOVA_AI_RESPONSE_INVALID");
            }
            return new ChatResponse(reply.trim(), "OLLAMA_LOCAL", properties.getModel());
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

    private void validate(NovaAiChatRequest request) {
        int maxInput = Math.max(1, Math.min(2_000, properties.getMaxInputChars()));
        if (request == null || request.message() == null || request.message().trim().isEmpty()
                || request.message().length() > maxInput || !List.of("en", "zh", "vi").contains(request.language())) {
            throw new BizException(400, "NOVA_AI_INPUT_INVALID");
        }
        List<NovaAiChatRequest.HistoryMessage> history = request.history() == null ? List.of() : request.history();
        if (history.size() > 10) throw new BizException(400, "NOVA_AI_HISTORY_INVALID");
        for (NovaAiChatRequest.HistoryMessage item : history) {
            if (item == null || !List.of("user", "assistant").contains(item.role()) || item.content() == null
                    || item.content().trim().isEmpty() || item.content().length() > maxInput) {
                throw new BizException(400, "NOVA_AI_HISTORY_INVALID");
            }
        }
    }

    public record Status(boolean available, String provider, String model, String privacy) {}

    public record ChatResponse(String reply, String provider, String model) {}
}
