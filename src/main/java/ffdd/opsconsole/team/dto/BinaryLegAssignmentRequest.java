package ffdd.opsconsole.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record BinaryLegAssignmentRequest(
        @NotNull @Positive Long ownerUserId,
        @NotNull @Positive Long memberUserId,
        @NotBlank @Pattern(regexp = "(?i)[AB]") String leg) { }
