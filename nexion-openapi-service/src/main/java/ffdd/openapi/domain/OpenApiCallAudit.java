package ffdd.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_openapi_call_audit")
public class OpenApiCallAudit extends BaseEntity {
    private Long appId;
    private String appKey;
    private String apiPath;
    private String httpMethod;
    private String nonce;
    private String requestHash;
    private Integer responseCode;
    private String responseMessage;
    private Long costMs;
}
