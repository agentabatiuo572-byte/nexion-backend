package ffdd.opsconsole.shared.outbox;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EventOutboxMessage {
    private Long id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String eventName;
    private String familyKey;
    private LocalDateTime eventTs;
    private String phase;
    private Integer accountAgeMonths;
    private String cohort;
    private Boolean serverAuthoritative;
    private Integer schemaRevision;
    private Boolean schemaRegistered;
    private Boolean analyticsEvent;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
