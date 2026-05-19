package ffdd.auth.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user")
public class User extends BaseEntity {
    private String countryCode;
    private String phone;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String referralCode;
    private String sponsorCode;
    private String kycStatus;
    private String userLevel;
    private String vRank;
    private String status;
}

