package ffdd.auth.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_maker_checker_task")
public class MakerCheckerTask extends BaseEntity {
    private String actionType;
    private String resourceType;
    private String resourceId;
    private String title;
    private String detail;
    private String payloadJson;
    private String maker;
    private String checker;
    private String status;
    private String reason;
    private LocalDateTime reviewedAt;
}
