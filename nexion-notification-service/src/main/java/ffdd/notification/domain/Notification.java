package ffdd.notification.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_notification")
public class Notification extends BaseEntity {
    private String bizNo;
    private Long userId;
    private String type;
    private String title;
    private String body;
    private Integer readFlag;
    private String pushStatus;
    private Integer pushAttempts;
    private LocalDateTime nextPushAt;
    private String lastPushError;
    private LocalDateTime pushedAt;
}
