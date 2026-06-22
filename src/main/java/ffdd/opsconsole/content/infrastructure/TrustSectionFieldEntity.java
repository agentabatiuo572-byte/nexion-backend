package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_trust_section_field")
public class TrustSectionFieldEntity extends BaseEntity {
    private String sectionKey;
    private String fieldKey;
    private String fieldValue;
    private String fieldDelta;
    private Integer sortOrder;
    private String lastOperator;
}
