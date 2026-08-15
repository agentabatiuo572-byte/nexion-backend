package ffdd.opsconsole.auth.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_oauth_identity")
public class UserOAuthIdentityEntity extends BaseEntity {
    private String provider;
    private String externalSubject;
    private Long userId;
    private String sourceEnvironment;
    private String displayName;
}
