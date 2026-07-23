package ffdd.opsconsole.shared.outbox.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_event_outbox")
public class EventOutboxEntity extends BaseEntity {
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
}
