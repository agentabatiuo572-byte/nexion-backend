package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_account_state")
public class AdminAccountStateEntity extends BaseEntity {
    private Long adminId;
    private Integer tfaRequired;
    private String tfaSecretEncrypted;
    private LocalDateTime tfaBoundAt;
    private LocalDateTime lastLoginAt;
    private LocalDateTime tfaResetAt;
    private LocalDateTime sessionsRevokedAt;
    private String credentialDeliveryStatus;
}
