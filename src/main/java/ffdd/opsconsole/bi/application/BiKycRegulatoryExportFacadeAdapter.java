package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.facade.BiKycRegulatoryExportFacade;
import ffdd.opsconsole.bi.facade.KycRegulatoryExportJob;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BiKycRegulatoryExportFacadeAdapter implements BiKycRegulatoryExportFacade {
    private static final String TYPE = "KYC_REGULATORY";
    private final BiReportRepository repository;

    @Override
    public KycRegulatoryExportJob create(String jobNo, String scope, long rowCount, String csv, String reason) {
        BiReportView created = repository.createReport(new BiReportCreateCommand(
                jobNo,
                "C4 KYC监管脱敏台账",
                TYPE,
                "ON_DEMAND",
                "CSV",
                scope,
                "user_no,status,network,paired_at,trigger_source",
                rowCount,
                true,
                "MASKED",
                "READY",
                reason));
        repository.saveSnapshotCsv(jobNo, csv);
        return map(created);
    }

    @Override
    public List<KycRegulatoryExportJob> recent(int limit) {
        int pageSize = Math.max(1, Math.min(limit, 50));
        return repository.reports(TYPE, List.of("READY", "EXPORTED"), 1, pageSize).getRecords().stream()
                .map(this::map)
                .toList();
    }

    @Override
    public Optional<String> downloadCsv(String jobNo) {
        return repository.findReport(jobNo)
                .filter(view -> TYPE.equalsIgnoreCase(view.type()))
                .flatMap(view -> repository.findSnapshotCsv(jobNo));
    }

    private KycRegulatoryExportJob map(BiReportView view) {
        return new KycRegulatoryExportJob(
                view.reportId(),
                view.status(),
                view.scope(),
                view.rowCount() == null ? 0L : view.rowCount(),
                true,
                "/api/admin/users/kyc/exports/" + view.reportId() + "/download",
                view.lastActionAt());
    }
}
