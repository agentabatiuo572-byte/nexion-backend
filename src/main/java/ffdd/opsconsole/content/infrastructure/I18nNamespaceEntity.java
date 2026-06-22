package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_i18n_namespace")
public class I18nNamespaceEntity extends BaseEntity {
    private String namespaceCode;
    private Integer keyCount;
    private Integer coveragePct;
    private String variants;
    private String lastChange;
    private Integer status;
    private Integer sortOrder;
}
