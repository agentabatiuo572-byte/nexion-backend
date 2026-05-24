package ffdd.openapi.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_openapi_nonce")
public class OpenApiNonce extends BaseEntity {
    private String appKey;
    private String nonce;
    private LocalDateTime expiresAt;
}
