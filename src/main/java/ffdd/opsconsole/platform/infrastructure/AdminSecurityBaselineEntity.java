package ffdd.opsconsole.platform.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_security_baseline")
public class AdminSecurityBaselineEntity extends BaseEntity {
    private String baselineKey;
    private String label;
    private String description;
    private String baselineValue;
    private Integer locked;
    private Integer sortOrder;
    private Integer status;
}
