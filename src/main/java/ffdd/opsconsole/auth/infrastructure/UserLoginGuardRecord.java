package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nx_user_login_guard")
public class UserLoginGuardRecord {
    @TableId
    private String loginKey;
    private int failedCount;
    private LocalDateTime windowStartedAt;
    private LocalDateTime lockedUntil;
}
