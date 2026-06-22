package ffdd.opsconsole.shared.security.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_session")
public class UserSessionEntity extends BaseEntity {
    private Long userId;
    private String refreshTokenId;
    private String deviceName;
    private String clientIp;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
}
