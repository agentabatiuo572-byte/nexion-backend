package ffdd.opsconsole.auth.mapper;

import lombok.Data;

/** One active sponsor-chain owner that must receive the new member projection. */
@Data
public class TeamAncestorProjection {
    private Long ownerUserId;
    private Integer level;
}
