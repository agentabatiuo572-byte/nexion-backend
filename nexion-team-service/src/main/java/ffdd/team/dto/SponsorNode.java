package ffdd.team.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SponsorNode {
    @NotNull
    private Long userId;
    private String nickname;
    @NotNull
    private Integer layerNo;
    private String vRank;
}

