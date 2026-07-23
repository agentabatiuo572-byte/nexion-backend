package ffdd.opsconsole.bi.facade;

import java.util.List;
import java.util.Optional;

public interface BiKycRegulatoryExportFacade {
    KycRegulatoryExportJob create(String jobNo, String scope, long rowCount, String csv, String reason);

    List<KycRegulatoryExportJob> recent(int limit);

    Optional<String> downloadCsv(String jobNo);
}
