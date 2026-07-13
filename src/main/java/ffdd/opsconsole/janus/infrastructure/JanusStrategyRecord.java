package ffdd.opsconsole.janus.infrastructure;

import lombok.Data;

@Data
public class JanusStrategyRecord {
    private String strategyId;
    private String name;
    private String description;
    private String status;
    private Integer version;
    private Integer priority;
    private String owner;
    private String scopeJson;
    private String ruleTreeJson;
    private String actionJson;
    private String safeguardsJson;
    private String rolloutJson;
    private String healthConfigJson;
    private String templateKey;
    private Long createdAt;
    private Long publishedAt;
    private Long lockVersion;
}
