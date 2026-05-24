package ffdd.compute.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TaskMaintenanceResult {
    private int scanned;
    private int retried;
    private int retryScheduled;
    private int failed;
    private int skipped;
    private List<String> taskNos = new ArrayList<>();

    public void incrementRetried(String taskNo) {
        retried++;
        taskNos.add(taskNo);
    }

    public void incrementRetryScheduled(String taskNo) {
        retryScheduled++;
        taskNos.add(taskNo);
    }

    public void incrementFailed(String taskNo) {
        failed++;
        taskNos.add(taskNo);
    }

    public void incrementSkipped() {
        skipped++;
    }
}
