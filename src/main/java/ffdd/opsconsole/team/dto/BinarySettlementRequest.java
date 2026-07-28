package ffdd.opsconsole.team.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record BinarySettlementRequest(
        @NotNull @Positive Long ownerUserId,
        @NotNull LocalDate settlementDate,
        @NotBlank @Size(min = 8, max = 200) String reason) { }
