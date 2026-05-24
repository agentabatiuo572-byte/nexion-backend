package ffdd.compliance.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_kyc_profile")
public class KycProfile extends BaseEntity {
    private Long userId;
    private String kycNo;
    private String status;
    private String country;
    private String documentObjectKey;
    private LocalDateTime reviewedAt;
}
