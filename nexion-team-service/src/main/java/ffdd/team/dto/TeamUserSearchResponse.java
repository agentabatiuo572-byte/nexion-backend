package ffdd.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamUserSearchResponse {
    private Long userId;
    private String nickname;
    private String phoneMasked;
    private String referralCode;
    private String userLevel;
    @JsonProperty("vRank")
    private String vRank;
    private String status;
}
