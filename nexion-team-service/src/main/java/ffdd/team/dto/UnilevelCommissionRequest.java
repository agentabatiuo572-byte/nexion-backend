package ffdd.team.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class UnilevelCommissionRequest {
    @NotNull
    private Long buyerUserId;
    private String buyerName;
    @NotBlank
    private String orderNo;
    @NotNull
    private BigDecimal orderAmountUsd;
    @Valid
    @NotEmpty
    private List<SponsorNode> sponsorChain;
}

