package ffdd.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateBankCardRequest {
    private Long userId;

    @NotBlank
    @Size(max = 80)
    private String cardholderName;

    @NotBlank
    @Size(min = 12, max = 32)
    private String cardNumber;

    @Size(max = 32)
    private String brand;

    @Size(max = 8)
    private String countryCode;

    private Integer isDefault;
}
