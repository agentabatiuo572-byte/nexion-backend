package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_i18n_message_version")
public class I18nMessageVersionEntity extends BaseEntity {
    private String messageKey;
    private Integer versionNo;
    private String zhValue;
    private String enValue;
    private String viValue;
    private String status;
}
