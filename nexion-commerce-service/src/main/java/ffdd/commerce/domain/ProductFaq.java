package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_product_faq")
public class ProductFaq extends BaseEntity {
    private Long productId;
    private String question;
    private String answer;
    private String status;
    private Integer sortOrder;
}
