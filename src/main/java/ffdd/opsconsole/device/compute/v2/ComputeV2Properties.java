package ffdd.opsconsole.device.compute.v2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.compute-v2")
public class ComputeV2Properties {
    private boolean enabled;
    private boolean accepting = true;
    private boolean strictDurability;
    private String deploymentScope = "";
    private String namesrvAddr = "";
    private String topic = "nexion-compute-v2";
    private String producerGroup = "nexion-compute-v2-producer";
    private String consumerGroup = "nexion-compute-v2-checkpoint";
    private int managedCapacity = 6000;
    private int executionCapacity = 6;
    private int unpersistedCapacity = 30000;
    private int pullBufferCapacity = 1000;
    private int leaseSeconds = 45;
    private int sendTimeoutMillis = 5000;
    private int backlogStopSeconds = 300;
    private int dedupRetentionSeconds = 604800;
    private int postedDedupSeconds = 600;

    public void validate() {
        if (!deploymentScope.matches("[A-Za-z0-9_-]{1,64}") || namesrvAddr.isBlank()
                || !getTopic().matches("[A-Za-z0-9_-]{1,127}")
                || !getProducerGroup().matches("[A-Za-z0-9_-]{1,127}") || !getConsumerGroup().matches("[A-Za-z0-9_-]{1,127}")
                || managedCapacity < 1 || managedCapacity > 6000
                || executionCapacity < 1 || executionCapacity > 6 || unpersistedCapacity < 200
                || unpersistedCapacity > 30000 || pullBufferCapacity < 200 || pullBufferCapacity > 1000
                || leaseSeconds < 10 || leaseSeconds > 900 || sendTimeoutMillis < 1000 || sendTimeoutMillis > 10000
                || backlogStopSeconds < 60 || backlogStopSeconds > 300 || dedupRetentionSeconds < 86400
                || postedDedupSeconds < 1 || postedDedupSeconds > 600)
            throw new IllegalStateException("Invalid bounded compute v2 configuration");
    }

    public String prefix() { return "nexion:{compute-v2-" + deploymentScope + "}:"; }
    public String getTopic() { return scoped(topic); }
    public String getProducerGroup() { return scoped(producerGroup); }
    public String getConsumerGroup() { return scoped(consumerGroup); }
    private String scoped(String value) { return value.endsWith("-"+deploymentScope) ? value : value+"-"+deploymentScope; }
}
