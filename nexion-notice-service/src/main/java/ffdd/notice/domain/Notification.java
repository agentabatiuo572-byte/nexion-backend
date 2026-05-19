package ffdd.notice.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_notification")
public class Notification extends BaseEntity {
    private Long userId;
    private String type;
    private String title;
    private String body;
    private Integer readFlag;
}

