package ffdd.opsconsole.auth.infrastructure;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

@Getter
@Setter
@TableName("nx_oauth_provider_flow")
public class OAuthProviderFlow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String proofHash;
    private String receiptHash;
    private String state;
    private String provider;
    private String nonce;
    private String verifier;
    private String browserHash;
    private Long linkUserId;
    private String sponsorCode;
    private String status;
    private String subject;
    private String displayName;
    private String clientHash;
    private LocalDateTime expiresAt;
    @Override public String toString() { return "OAuthProviderFlow[REDACTED]"; }
}
