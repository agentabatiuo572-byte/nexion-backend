package ffdd.opsconsole.content.infrastructure;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DisclosureAckStatusEntity {
    private Long userId;
    private String jurisdictionCode;
    private String requiredVersion;
    private String acknowledgedVersion;
    private String ackStatus;
    private LocalDateTime acknowledgedAt;
}
