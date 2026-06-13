package ffdd.wallet.dto;

import lombok.Data;

@Data
public class BankCardQueryRequest {
    private Long userId;
    private String status;
}
