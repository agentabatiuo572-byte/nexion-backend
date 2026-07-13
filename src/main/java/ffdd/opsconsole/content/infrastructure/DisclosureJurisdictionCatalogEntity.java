package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_disclosure_jurisdiction_catalog")
public class DisclosureJurisdictionCatalogEntity extends BaseEntity {
    private String jurisdictionCode;
    private String jurisdictionName;
    private String status;
    private Long revision;
    private String lastOperator;
}
