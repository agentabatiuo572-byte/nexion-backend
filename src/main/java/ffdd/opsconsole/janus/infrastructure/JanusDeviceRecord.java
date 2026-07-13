package ffdd.opsconsole.janus.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("nx_janus_device")
public class JanusDeviceRecord {
    @TableId
    private String sid;
    private String deviceId;
    private Long firstSeenAt;
    private Long lastSeenAt;
    private Long installAt;
    private Integer installDays;
    private String inviteCode;
    private String channel;
    private String cohortId;
    private String status;
    private String desiredStatus;
    private String commandState;
    private String statusSource;
    private Boolean activated;
    private String remoteUrlKey;
    private Integer maturityScore;
    private Integer recommendationScore;
    private Integer environmentRiskScore;
    private Integer priorityScore;
    private String ua;
    private String platform;
    private String model;
    private String osName;
    private String browser;
    private String maturityJson;
    private String environmentJson;
    private String hitStrategy;
    private Integer hitStrategyVersion;
    private String latestDecisionJson;
    private String latestSessionJson;
    private String manualOverrideJson;
    private String lastOperatorId;
    private String lastOperationReason;
    private String activationKind;
    private String tagsJson;
    private Long lockVersion;
}
