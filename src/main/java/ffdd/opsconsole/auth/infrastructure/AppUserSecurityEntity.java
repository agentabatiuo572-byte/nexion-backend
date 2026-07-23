package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_security")
public class AppUserSecurityEntity extends BaseEntity {
    private Long userId;
    private Boolean twoFactorEnabled;
    private Integer loginFailCount;
    private Boolean passwordResetRequired;
    private LocalDateTime passwordChangedAt;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
