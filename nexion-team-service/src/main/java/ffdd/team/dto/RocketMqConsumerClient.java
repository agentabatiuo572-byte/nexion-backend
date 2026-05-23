package ffdd.team.dto;

import lombok.Data;

@Data
public class RocketMqConsumerClient {
    private String clientId;
    private String clientAddr;
    private String language;
    private Integer version;
}
