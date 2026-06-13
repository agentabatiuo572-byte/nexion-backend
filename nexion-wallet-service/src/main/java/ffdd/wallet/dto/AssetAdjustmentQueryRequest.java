package ffdd.wallet.dto;

import lombok.Data;

@Data
public class AssetAdjustmentQueryRequest {
    private Long userId;
    private String status;
    private String asset;
    private long current = 1;
    private long size = 50;
}
