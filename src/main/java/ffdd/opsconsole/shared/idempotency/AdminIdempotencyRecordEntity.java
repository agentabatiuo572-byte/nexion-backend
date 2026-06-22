package ffdd.opsconsole.shared.idempotency;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_idempotency_record")
public class AdminIdempotencyRecordEntity extends BaseEntity {
    private String scope;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("request_hash")
    private String requestHash;

    private String status;

    @TableField("response_json")
    private String responseJson;

    @TableField("error_message")
    private String errorMessage;

    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
