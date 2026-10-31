package ffdd.opsconsole.device.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** Persistent request row; scoped orchestration writes use the mapper's fenced statements. */
@Data
@TableName("nx_test_cloud_request")
public class TestCloudRequestEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String executorId;
    private String operation;
    private String keyHash;
    private String bodyHash;
    private Long ownerId;
    private Long deviceId;
    private String instanceNo;
    private String taskConfigId;
    private String taskNo;
    private String state;
    private Long generation;
    private String recoveryToken;
    private Boolean recoveryUsed;
    private Long proofTimestamp;
    private Boolean proofValidated;
    private Boolean reviewedTermination;
    private String responseJson;
    private LocalDateTime updatedAt;
}
