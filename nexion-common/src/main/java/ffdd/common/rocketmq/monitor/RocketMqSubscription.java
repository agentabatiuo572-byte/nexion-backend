package ffdd.common.rocketmq.monitor;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class RocketMqSubscription {
    private String topic;
    private String subString;
    private String expressionType;
    private Boolean classFilterMode;
    private List<String> tags = new ArrayList<>();
}
