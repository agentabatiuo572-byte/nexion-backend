package ffdd.commerce.dto;

import ffdd.commerce.domain.ProductFaq;
import ffdd.commerce.domain.ProductReview;
import ffdd.commerce.domain.ProductSpec;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class StoreProductContentResponse {
    private Long productId;
    private BigDecimal averageRating;
    private Integer reviewCount;
    private List<RatingBar> ratingBars;
    private List<ProductReview> reviews;
    private List<ProductFaq> faqs;
    private List<ProductSpec> specs;

    @Data
    public static class RatingBar {
        private Integer star;
        private Integer pct;

        public RatingBar(Integer star, Integer pct) {
            this.star = star;
            this.pct = pct;
        }
    }
}
