package ffdd.opsconsole.content.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record NovaAiChatRequest(
        @NotBlank @Size(max = 2000) String message,
        @NotBlank @Pattern(regexp = "en|zh|vi") String language,
        @Size(max = 10) List<@Valid HistoryMessage> history) {

    public record HistoryMessage(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 2000) String content) {}
}
