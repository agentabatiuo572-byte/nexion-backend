package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_trust_section_version")
public class TrustSectionVersionEntity extends BaseEntity {
    private String sectionKey;
    private String versionLabel;
    private String description;
    private String structText;
    private String fieldsJson;
    private String status;
    private Long revision;
    private String lastOperator;
}
