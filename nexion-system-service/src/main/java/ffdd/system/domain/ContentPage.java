package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_content_page")
public class ContentPage extends BaseEntity {
    private String pageCode;
    private String title;
    private String content;
    private Integer status;
}
