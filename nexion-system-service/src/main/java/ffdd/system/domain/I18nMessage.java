package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_i18n_message")
public class I18nMessage extends BaseEntity {
    private String messageKey;
    private String locale;
    private String messageValue;
    private Integer status;
}
