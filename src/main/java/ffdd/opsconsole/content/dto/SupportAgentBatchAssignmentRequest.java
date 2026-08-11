package ffdd.opsconsole.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SupportAgentBatchAssignmentRequest(
        @NotEmpty(message = "SUPPORT_ADVISOR_USERS_REQUIRED")
        @Size(max = SupportAgentBatchAssignmentRequest.MAX_USER_IDS, message = "SUPPORT_ADVISOR_BATCH_TOO_LARGE")
        List<Long> userIds,
        @NotBlank(message = "OPERATOR_REQUIRED")
        @Size(max = 64, message = "OPERATOR_TOO_LONG")
        String operator,
        @NotBlank(message = "REASON_REQUIRED")
        @Size(min = 8, max = 200, message = "REASON_LENGTH_INVALID")
        String reason) {
    public static final int MAX_USER_IDS = 100;
}
