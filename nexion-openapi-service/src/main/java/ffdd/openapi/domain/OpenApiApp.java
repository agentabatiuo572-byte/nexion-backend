package ffdd.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_openapi_app")
public class OpenApiApp extends BaseEntity {
    private Long ownerUserId;
    private String appName;
    private String appKey;
    private String appSecret;
    private String status;
    private Integer qpsLimit;
    private Integer dailyLimit;
    private String remark;
}
