package ffdd.commerce.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_product_review")
public class ProductReview extends BaseEntity {
    private Long productId;
    private Long userId;
    private Long orderId;
    private BigDecimal rating;
    private String title;
    private String content;
    private String mediaObjectKeys;
    private String avatarObjectKey;
    private String avatarColor;
    private String status;
    private Integer sortOrder;
}
