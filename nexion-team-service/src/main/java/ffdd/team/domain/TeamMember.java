package ffdd.team.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_team_member")
public class TeamMember extends BaseEntity {
    private Long userId;
    private Long memberUserId;
    private String memberNo;
    private String nickname;
    private String vRank;
    private Integer level;
    private BigDecimal volume;
}

