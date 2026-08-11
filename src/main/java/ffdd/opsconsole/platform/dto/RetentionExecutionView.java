package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;

/** Read-backable result of one bounded retention invocation; null counts are represented as zero. */
public record RetentionExecutionView(
        int retentionMonths,
        LocalDateTime evaluatedAt,
        boolean lockAcquired,
        int archivedRows,
        int deletedRows,
        int outboxRows,
        int behaviorFactRows) {}
