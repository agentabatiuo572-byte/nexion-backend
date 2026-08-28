package ffdd.opsconsole.content.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record NovaAiChatRequest(
        @NotBlank @Size(max = 2000) String message,
        @NotBlank @Pattern(regexp = "en|zh|vi") String language,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        String conversationId,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        String turnId,
        @Size(max = 10) List<@Valid HistoryMessage> history) {

    public NovaAiChatRequest(String message, String language, List<HistoryMessage> history) {
        this(message, language, null, null, history);
    }

    public record HistoryMessage(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 2000) String content) {}
}
