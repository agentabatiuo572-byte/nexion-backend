package ffdd.opsconsole.janus.infrastructure;

import lombok.Data;

@Data
public class JanusStrategyVersionRecord {
    private Integer version;
    private String note;
    private String actorId;
    private Long createdAt;
    private String snapshotJson;
    private String configHash;
}
