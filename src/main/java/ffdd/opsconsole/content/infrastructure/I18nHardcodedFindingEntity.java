package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_i18n_hardcoded_finding")
public class I18nHardcodedFindingEntity extends BaseEntity {
    private String location;
    private String rawCopy;
    private String suggestedKey;
    private String status;
    private Integer sortOrder;
}
