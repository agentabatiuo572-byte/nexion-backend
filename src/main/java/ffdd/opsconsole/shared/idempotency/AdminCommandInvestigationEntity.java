package ffdd.opsconsole.shared.idempotency;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_command_investigation")
public class AdminCommandInvestigationEntity extends BaseEntity {
    private Long originalRecordId;
    private String scope;
    private String commandKey;
    private String requestHash;
    private String originalMethod;
    private String originalPath;
    private String originalTarget;
    private String originalTargets;
    private Long originalAdminId;
    private Long investigatorId;
    private LocalDateTime investigatedAt;
    private String requestedConclusion;
    private String conclusion;
    private String evidenceReference;
    private String evidenceType;
    private String factType;
    private String factId;
    private String factAction;
}
