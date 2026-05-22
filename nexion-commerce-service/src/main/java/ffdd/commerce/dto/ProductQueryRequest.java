package ffdd.commerce.dto;

import lombok.Data;

@Data
public class ProductQueryRequest {
    private String keyword;
    private String productType;
    private String status;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
