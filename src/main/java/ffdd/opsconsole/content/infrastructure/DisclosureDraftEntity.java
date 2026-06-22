package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_disclosure_draft")
public class DisclosureDraftEntity extends BaseEntity {
    private String jurisdictionCode;
    private String versionLabel;
    private String languageScope;
    private String effectiveDate;
    private Boolean requiresReack;
    private String zhBody;
    private String enBody;
    private String status;
    private String lastOperator;
}
