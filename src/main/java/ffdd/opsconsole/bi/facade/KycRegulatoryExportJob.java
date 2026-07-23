package ffdd.opsconsole.bi.facade;

import java.time.LocalDateTime;

public record KycRegulatoryExportJob(
        String jobNo,
        String status,
        String scope,
        long rowCount,
        boolean masked,
        String downloadPath,
        LocalDateTime createdAt) {
}
