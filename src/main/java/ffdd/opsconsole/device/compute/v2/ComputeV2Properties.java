package ffdd.opsconsole.device.compute.v2;

import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.compute-v2")
public class ComputeV2Properties {
    private boolean enabled;
    private boolean ruleCycleEnabled;
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
    private boolean resourcePolicyEnabled;
    private int modalMemoryMiB = 6144;
    private int serverCpuThreads = 6;
    private int emExecutionCapacity = 1;
    private Map<String,ResourceCost> resourceCosts = new LinkedHashMap<>(Map.of(
            "IG",new ResourceCost("nota-ai/bk-sdm-tiny",6144,2),
            "VG",new ResourceCost("nota-ai/bk-sdm-tiny",6144,2),
            "LL",new ResourceCost("HuggingFaceTB/SmolLM2-135M-Instruct",1536,2),
            "FT",new ResourceCost("HuggingFaceTB/SmolLM2-135M-Instruct",2560,2),
            "SP",new ResourceCost("openai/whisper-tiny",1024,2),
            "EM",new ResourceCost("BGE-M3",1536,2)));
    public record ResourceCost(String modelName,int memoryMiB,int cpuThreads) { }

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
        if (resourcePolicyEnabled) {
            if (modalMemoryMiB < 1 || modalMemoryMiB > 1048576 || serverCpuThreads < 1 || serverCpuThreads > 256
                    || emExecutionCapacity < 1 || emExecutionCapacity > executionCapacity
                    || resourceCosts == null || !resourceCosts.keySet().equals(java.util.Set.of("IG","VG","LL","FT","SP","EM")))
                throw new IllegalStateException("Invalid compute v2 resource policy");
            for (var entry : resourceCosts.entrySet()) {
                var cost = entry.getValue();
                if (cost == null || cost.modelName() == null || cost.modelName().isBlank() || cost.modelName().length() > 128
                        || cost.memoryMiB() < 1 || cost.cpuThreads() < 1 || cost.cpuThreads() > serverCpuThreads
                        || !"EM".equals(entry.getKey()) && cost.memoryMiB() > modalMemoryMiB)
                    throw new IllegalStateException("Invalid compute v2 resource cost");
            }
        }
    }

    /** Serialized only for an explicitly enabled deployment; the legacy six-EM mode stays unchanged. */
    public Map<String,Object> executionResourcePolicy() {
        return resourcePolicyEnabled ? Map.of("enabled",true,"modalMemoryMiB",modalMemoryMiB,
                "serverCpuThreads",serverCpuThreads,"emMaxConcurrent",emExecutionCapacity,"costs",resourceCosts) : Map.of();
    }

    public String prefix() { return "nexion:{compute-v2-" + deploymentScope + "}:"; }
    public String getTopic() { return scoped(topic); }
    public String getProducerGroup() { return scoped(producerGroup); }
    public String getConsumerGroup() { return scoped(consumerGroup); }
    private String scoped(String value) { return value.endsWith("-"+deploymentScope) ? value : value+"-"+deploymentScope; }
}
