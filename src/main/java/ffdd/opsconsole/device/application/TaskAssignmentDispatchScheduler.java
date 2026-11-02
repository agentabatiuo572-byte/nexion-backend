package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskAssignmentDispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignmentDispatchScheduler.class);
    private final AppTaskAssignmentMapper mapper;
    private final AppTaskAssignmentService service;
    @Value("${nexion.tasks.assignment-dispatch-batch-size:100}")
    private final int batchSize;
    private final AtomicLong afterDeviceId = new AtomicLong();
    private final List<Long> computeV2Devices;
    private final boolean simulatedTasksEnabled;

    public TaskAssignmentDispatchScheduler(AppTaskAssignmentMapper mapper,AppTaskAssignmentService service,int batchSize) {
        this.mapper=mapper;this.service=service;this.batchSize=batchSize;this.computeV2Devices=List.of();
        this.simulatedTasksEnabled=false;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskAssignmentDispatchScheduler(AppTaskAssignmentMapper mapper,AppTaskAssignmentService service,
            @Value("${nexion.tasks.assignment-dispatch-batch-size:100}") int batchSize,
            org.springframework.core.env.Environment environment) {
        this.mapper=mapper;this.service=service;this.batchSize=batchSize;
        simulatedTasksEnabled=environment.getProperty("nexion.simulated-tasks.enabled",Boolean.class,false);
        computeV2Devices=Boolean.TRUE.equals(environment.getProperty("nexion.compute-v2.enabled",Boolean.class,false))
                ?java.util.Arrays.stream(environment.getProperty("nexion.compute-v2.device-ids","").split(","))
                .map(String::trim).filter(value->!value.isEmpty()).map(Long::parseLong).distinct().sorted().toList():List.of();
        if(computeV2Devices.size()>6000 || computeV2Devices.stream().anyMatch(id->id<=0))
            throw new IllegalArgumentException("COMPUTE_V2_DEVICE_IDS_INVALID");
    }

    @Scheduled(fixedDelayString = "${nexion.tasks.assignment-dispatch-delay-ms:30000}")
    public synchronized void dispatch() {
        if(simulatedTasksEnabled)return;
        int limit = Math.max(1, Math.min(batchSize, 500));
        long cursor = afterDeviceId.get();
        List<AppTaskAssignmentMapper.AssignmentCandidate> candidates =
                candidates(cursor, limit);
        if ((candidates == null || candidates.isEmpty()) && cursor > 0) {
            cursor = 0;
            afterDeviceId.set(0);
            candidates = candidates(cursor, limit);
        }
        if (candidates == null || candidates.isEmpty()) return;
        afterDeviceId.set(candidates.stream()
                .filter(candidate -> candidate != null && candidate.deviceId() != null && candidate.deviceId() > 0)
                .mapToLong(AppTaskAssignmentMapper.AssignmentCandidate::deviceId)
                .max().orElse(cursor));
        for (AppTaskAssignmentMapper.AssignmentCandidate candidate : candidates) {
            if (candidate == null || candidate.userId() == null || candidate.userId() <= 0
                    || candidate.deviceId() == null || candidate.deviceId() <= 0) continue;
            try {
                service.assignAutomatically(candidate.userId(), candidate.deviceId());
            } catch (BizException expectedStateChange) {
                if (expectedStateChange.getCode() == 404 || expectedStateChange.getCode() == 409) {
                    log.debug("Task assignment candidate became ineligible for user {} device {}: {}",
                            candidate.userId(), candidate.deviceId(), expectedStateChange.getMessage());
                } else {
                    log.warn("Task assignment dispatch rejected for user {} device {}: {}",
                            candidate.userId(), candidate.deviceId(), expectedStateChange.getMessage());
                }
            } catch (RuntimeException failure) {
                log.error("Task assignment dispatch failed for user {} device {}",
                        candidate.userId(), candidate.deviceId(), failure);
            }
        }
    }

    private List<AppTaskAssignmentMapper.AssignmentCandidate> candidates(long cursor,int limit) {
        return computeV2Devices.isEmpty()?mapper.assignmentCandidates(cursor,limit)
                :mapper.assignmentCandidatesExcluding(cursor,limit,computeV2Devices);
    }
}
