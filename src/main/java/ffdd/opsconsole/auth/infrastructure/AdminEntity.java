package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin")
public class AdminEntity extends BaseEntity {
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String phone;
    private Integer superAdmin;
    private Integer status;
}
