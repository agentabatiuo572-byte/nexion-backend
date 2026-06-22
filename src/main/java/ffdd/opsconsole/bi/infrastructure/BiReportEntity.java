package ffdd.opsconsole.bi.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_admin_fourth_batch_report")
public class BiReportEntity extends BaseEntity {
    private String moduleCode;
    private String reportId;
    private String reportName;
    private String reportType;
    private String cycle;
    private String fileFormat;
    private String scopeText;
    private String fieldText;
    private Long rowCount;
    private Integer containsPii;
    private String maskingPolicy;
    private String status;
    private String note;
    private String lastAction;
    private LocalDateTime lastActionAt;
    private String reason;
}
