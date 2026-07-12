package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_disclosure_jurisdiction")
public class DisclosureJurisdictionEntity extends BaseEntity {
    private String jurisdictionCode;
    private String jurisdictionName;
    private String countryCodes;
    private String versionLabel;
    private String status;
    private String publishedAtLabel;
    private Long affectedCount;
    private BigDecimal ackProgressPct;
    private Long blockedCount;
    private String lastOperator;
}
