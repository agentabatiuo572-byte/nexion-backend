package ffdd.opsconsole.device.domain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;

/** Validated E2 scheduling settings. Missing legacy fields preserve the existing policy. */
public record TaskSchedulingSettings(int selectionWeight, int expectedDurationMinSec,
        int expectedDurationMaxSec, int executionTimeoutSec, String runtimeKey,
        String validatorKey, String workloadConfigJson) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public TaskSchedulingSettings {
        range(selectionWeight, 1, 1_000_000, "TASK_SELECTION_WEIGHT_INVALID");
        range(expectedDurationMinSec, 1, 86_400, "TASK_DURATION_RANGE_INVALID");
        range(expectedDurationMaxSec, expectedDurationMinSec, 86_400, "TASK_DURATION_RANGE_INVALID");
        range(executionTimeoutSec, expectedDurationMaxSec, 172_800, "TASK_EXECUTION_TIMEOUT_INVALID");
        runtimeKey = key(runtimeKey); validatorKey = key(validatorKey);
        workloadConfigJson = workloadConfigJson == null ? "{}" : workloadConfigJson.trim();
        if (workloadConfigJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384) throw invalid("TASK_WORKLOAD_CONFIG_INVALID");
        try {
            JsonNode value = JSON.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(workloadConfigJson);
            if (value == null || !value.isObject()) throw invalid("TASK_WORKLOAD_CONFIG_INVALID");
            workloadConfigJson = JSON.writeValueAsString(value);
        } catch (java.io.IOException malformed) { throw invalid("TASK_WORKLOAD_CONFIG_INVALID"); }
    }

    public static int defaultDuration(String taskClass) {
        return switch (taskClass == null ? "" : taskClass.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "IG" -> 18; case "VG" -> 900; case "LL" -> 12;
            case "FT" -> 1800; case "EM" -> 5; case "SP" -> 30; default -> 60;
        };
    }

    public static TaskSchedulingSettings defaults(String taskClass, String model) {
        int seconds = defaultDuration(taskClass);
        String runtime = "EM".equalsIgnoreCase(taskClass) && "BGE-M3".equalsIgnoreCase(model)
                ? "test-bge-m3-v1" : "";
        return new TaskSchedulingSettings(1, seconds, seconds, Math.max(300, seconds), runtime, runtime, "{}");
    }

    public TaskSchedulingSettings merge(DeviceTaskUpsertRequest request) {
        return new TaskSchedulingSettings(
                request.selectionWeight() == null ? selectionWeight : request.selectionWeight(),
                request.expectedDurationMinSec() == null ? expectedDurationMinSec : request.expectedDurationMinSec(),
                request.expectedDurationMaxSec() == null ? expectedDurationMaxSec : request.expectedDurationMaxSec(),
                request.executionTimeoutSec() == null ? executionTimeoutSec : request.executionTimeoutSec(),
                request.runtimeKey() == null ? runtimeKey : request.runtimeKey(),
                request.validatorKey() == null ? validatorKey : request.validatorKey(),
                request.workloadConfigJson() == null ? workloadConfigJson : request.workloadConfigJson());
    }

    public static void validateSupplied(DeviceTaskUpsertRequest request) {
        if (request.selectionWeight() != null) range(request.selectionWeight(), 1, 1_000_000, "TASK_SELECTION_WEIGHT_INVALID");
        if (request.expectedDurationMinSec() != null) range(request.expectedDurationMinSec(), 1, 86_400, "TASK_DURATION_RANGE_INVALID");
        if (request.expectedDurationMaxSec() != null) range(request.expectedDurationMaxSec(), 1, 86_400, "TASK_DURATION_RANGE_INVALID");
        if (request.expectedDurationMinSec() != null && request.expectedDurationMaxSec() != null
                && request.expectedDurationMinSec() > request.expectedDurationMaxSec()) throw invalid("TASK_DURATION_RANGE_INVALID");
        if (request.executionTimeoutSec() != null) {
            range(request.executionTimeoutSec(), 1, 172_800, "TASK_EXECUTION_TIMEOUT_INVALID");
            if (request.expectedDurationMaxSec() != null && request.executionTimeoutSec() < request.expectedDurationMaxSec())
                throw invalid("TASK_EXECUTION_TIMEOUT_INVALID");
        }
        if (request.runtimeKey() != null) key(request.runtimeKey());
        if (request.validatorKey() != null) key(request.validatorKey());
        if (request.workloadConfigJson() != null) new TaskSchedulingSettings(1, 1, 1, 1, "", "", request.workloadConfigJson());
    }

    private static String key(String value) {
        String key = value == null ? "" : value.trim();
        if (!key.isEmpty() && !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) throw invalid("TASK_RUNTIME_KEY_INVALID");
        return key;
    }
    private static void range(int value, int min, int max, String message) {
        if (value < min || value > max) throw invalid(message);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
