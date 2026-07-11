package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_copy_version_option")
public class CopyVersionOptionEntity extends BaseEntity {
    private String versionKey;
    private String name;
    private String description;
    private String status;
    private Integer sortOrder;
    private Long revision;
    private String lastOperator;
}
