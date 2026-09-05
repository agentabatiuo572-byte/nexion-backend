package ffdd.opsconsole.content.application;

import java.util.List;

public interface NovaAiGateway {
    String chat(ChatRequest request);

    boolean available();

    record ChatRequest(String model, String language, String sessionId, List<Message> messages,
                       int maxOutputTokens, String turnId, String queueScope) {
        public ChatRequest(String model, String language, String sessionId, List<Message> messages, int maxOutputTokens) {
            this(model, language, sessionId, messages, maxOutputTokens, null, null);
        }
        public ChatRequest(String model, String language, String sessionId, List<Message> messages, int maxOutputTokens, String turnId) {
            this(model, language, sessionId, messages, maxOutputTokens, turnId, null);
        }
    }

    record Message(String role, String content) {}
}
