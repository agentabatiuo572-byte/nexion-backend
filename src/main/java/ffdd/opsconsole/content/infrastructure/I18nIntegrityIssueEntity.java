package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_i18n_integrity_issue")
public class I18nIntegrityIssueEntity extends BaseEntity {
    private String issueCode;
    private String issueKind;
    private Integer issueCount;
    private String samplesText;
    private String status;
    private Integer sortOrder;
}
