package ffdd.opsconsole.content.application;

import java.util.List;

public interface NovaAiGateway {
    String chat(ChatRequest request);

    boolean available();

    record ChatRequest(String model, String language, String sessionId, List<Message> messages, int maxOutputTokens) {}

    record Message(String role, String content) {}
}
