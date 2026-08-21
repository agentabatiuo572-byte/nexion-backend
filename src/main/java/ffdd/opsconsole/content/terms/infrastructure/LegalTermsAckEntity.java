package ffdd.opsconsole.content.terms.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_legal_terms_ack")
public class LegalTermsAckEntity extends BaseEntity {
    private Long userId;
    private String sourceEnvironment;
    private String runId;
    private String locale;
    private String jurisdiction;
    private String versionLabel;
    private String idempotencyKey;
    private LocalDateTime acknowledgedAt;
}
