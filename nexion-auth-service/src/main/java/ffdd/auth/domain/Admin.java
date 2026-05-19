package ffdd.auth.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin")
public class Admin extends BaseEntity {
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String phone;
    private Integer superAdmin;
    private Integer status;
}

