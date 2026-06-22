package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_i18n_message")
public class I18nMessageEntity extends BaseEntity {
    private String messageKey;
    private String locale;
    private String messageValue;
    private Integer status;
}
