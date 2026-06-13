package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_emergency_tamper_gate")
public class EmergencyTamperGate extends BaseEntity {
    private String gateKey;
    private String gateName;
    @TableField("event_count_24h")
    private Integer eventCount24h;
    private String verdict;
    private String reviewReason;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private Integer status;
}
