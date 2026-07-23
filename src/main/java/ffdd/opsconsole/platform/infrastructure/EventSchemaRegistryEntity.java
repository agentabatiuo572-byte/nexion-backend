package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_event_schema_registry")
public class EventSchemaRegistryEntity extends BaseEntity {
    private String eventName;
    private String ownerDomain;
    private String familyKey;
    private String producer;
    private String consumers;
    private Boolean serverAuthoritative;
    private String samplingPolicy;
    private Integer currentRevision;
    private String status;
    private String createdBy;
    private String updatedBy;
    private String reason;
}
