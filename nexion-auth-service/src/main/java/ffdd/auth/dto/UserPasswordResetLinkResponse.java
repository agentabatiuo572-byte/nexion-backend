package ffdd.auth.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPasswordResetLinkResponse {
    private Long userId;
    private String resetRequestNo;
    private String deliveryStatus;
    private String recipientMasked;
    private String operator;
    private String reason;
    private LocalDateTime requestedAt;
}
