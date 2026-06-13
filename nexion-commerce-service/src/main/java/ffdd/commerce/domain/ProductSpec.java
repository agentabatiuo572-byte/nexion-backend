package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_product_spec")
public class ProductSpec extends BaseEntity {
    private Long productId;
    private String specGroup;
    private String specKey;
    private String specValue;
    private String unit;
    private String status;
    private Integer sortOrder;
}
