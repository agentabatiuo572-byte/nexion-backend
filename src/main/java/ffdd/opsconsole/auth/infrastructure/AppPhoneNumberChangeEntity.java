package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nx_user_phone_number_change")
public class AppPhoneNumberChangeEntity {
    @TableId(value = "change_no", type = IdType.INPUT)
    private String changeNo;
    private Long userId;
    private String oldCountryCode;
    private String oldPhone;
    private String newCountryCode;
    private String newPhone;
    private String oldCodeHash;
    private String newCodeHash;
    private LocalDateTime expiresAt;
    private Integer attemptCount;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
