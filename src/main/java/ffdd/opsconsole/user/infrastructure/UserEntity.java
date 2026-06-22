package ffdd.opsconsole.user.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user")
public class UserEntity extends BaseEntity {
    private String countryCode;
    private String phone;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String referralCode;
    private Long sponsorUserId;
    private String sponsorCode;
    private String kycStatus;
    private String userLevel;
    private String vRank;
    private String status;
    private String language;
    private String region;
    private String bio;
    private String timezone;
}
