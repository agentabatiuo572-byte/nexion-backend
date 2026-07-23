package ffdd.opsconsole.team.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public record BinarySettlementRequest(
        @NotNull @Positive Long ownerUserId,
        @NotNull LocalDate settlementDate) { }
