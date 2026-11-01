package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("nx_compute_v2_event")
public class ComputeV2EventEntity {
    @TableId(value="event_id",type=IdType.INPUT) private String eventId;
    private String batchId,deploymentScope,taskNo,kind,payloadSha256,payload;
    @TableField(insertStrategy=FieldStrategy.NEVER,updateStrategy=FieldStrategy.NEVER)
    private String requestId;
    private Long generation,userId,userDeviceId;
    private LocalDateTime occurredAtUtc;
}
