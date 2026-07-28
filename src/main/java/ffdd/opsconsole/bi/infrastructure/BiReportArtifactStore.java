package ffdd.opsconsole.bi.infrastructure;

import ffdd.opsconsole.bi.domain.BiReportSnapshot;
import ffdd.opsconsole.bi.mapper.BiReportArtifactMapper;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BiReportArtifactStore {
    private static final String CONTENT_TYPE = "text/csv;charset=UTF-8";

    private final BiReportArtifactMapper mapper;
    private final ObjectStorageService objectStorage;

    @PostConstruct
    void ensureSchema() {
        mapper.createArtifactTable();
        mapper.createGrantTable();
    }

    public void storeCsv(String reportId, String csv) {
        String normalizedReportId = requireReportId(reportId);
        byte[] bytes = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) throw new IllegalStateException("BI_REPORT_SNAPSHOT_EMPTY");
        String objectKey = "bi-reports/" + normalizedReportId.toLowerCase(Locale.ROOT) + ".csv";
        objectStorage.put(objectKey, CONTENT_TYPE, new ByteArrayInputStream(bytes), bytes.length);
        registerRollbackCleanup(objectKey);
        if (mapper.upsertArtifact(
                normalizedReportId, objectKey, CONTENT_TYPE, bytes.length, sha256(bytes)) != 1) {
            objectStorage.remove(objectKey);
            throw new IllegalStateException("BI_REPORT_ARTIFACT_METADATA_WRITE_FAILED");
        }
    }

    public boolean exists(String reportId) {
        return StringUtils.hasText(reportId) && mapper.findArtifact(reportId.trim()) != null;
    }

    public Optional<BiReportSnapshot> open(String reportId) {
        if (!StringUtils.hasText(reportId)) return Optional.empty();
        BiReportArtifactMapper.ArtifactRow row = mapper.findArtifact(reportId.trim());
        if (row == null) return Optional.empty();
        InputStream stream = objectStorage.get(row.objectKey());
        return Optional.of(new BiReportSnapshot(
                row.objectKey(), row.contentType(), row.sizeBytes(), row.sha256(), stream));
    }

    public void issueGrant(String reportId, String tokenHash, long adminId, LocalDateTime expiresAt) {
        if (adminId <= 0 || expiresAt == null || !StringUtils.hasText(tokenHash)) {
            throw new IllegalArgumentException("BI_DOWNLOAD_GRANT_INVALID");
        }
        mapper.deleteExpiredGrants(LocalDateTime.now());
        if (mapper.insertGrant(reportId, tokenHash, adminId, expiresAt) != 1) {
            throw new IllegalStateException("BI_DOWNLOAD_GRANT_WRITE_FAILED");
        }
    }

    public boolean isGrantValid(
            String reportId, String tokenHash, long adminId, LocalDateTime now) {
        return adminId > 0
                && StringUtils.hasText(reportId)
                && StringUtils.hasText(tokenHash)
                && now != null
                && mapper.countValidGrant(reportId.trim(), tokenHash.trim(), adminId, now) == 1;
    }

    private String requireReportId(String reportId) {
        if (!StringUtils.hasText(reportId)
                || !reportId.trim().matches("^[A-Za-z0-9_-]{3,64}$")) {
            throw new IllegalArgumentException("BI_REPORT_ID_INVALID");
        }
        return reportId.trim();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void registerRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) objectStorage.removeQuietly(objectKey);
            }
        });
    }
}
