package ffdd.opsconsole.promotion.application;

import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.media.application.OpsMediaUploadService;
import ffdd.opsconsole.shared.canonical.ManagedPromotionBannerIdentity;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

@Service
@RequiredArgsConstructor
public class PromotionBannerService {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private final PromotionMapper db;
    private final ObjectStorageService storage;

    public void validateAsset(Object assetId) {
        if (assetId == null) return;
        String key = ManagedPromotionBannerIdentity.objectKey(text(assetId));
        if (key == null || !exists(key)) throw invalid();
        image(key, true);
    }

    public void validateEvidenceRefs(Object refs) {
        if (!(refs instanceof List<?> items)) return;
        for (int i = 0; i < items.size(); i++) {
            String ref = text(items.get(i));
            if (ref.length() <= 64) continue; // Existing audit/A2 IDs retain their own approval rules.
            String key = OpsMediaUploadService.promotionEvidenceObjectKey(ref);
            if (key == null) throw new BizException(422, "PROMOTION_FIELD_INVALID:$.evidenceRefs[" + i + "]");
            boolean present;
            try { present = storage.exists(key); }
            catch (RuntimeException failure) { throw new BizException(503, "PROMOTION_EVIDENCE_UNAVAILABLE"); }
            if (!present) throw new BizException(422, "PROMOTION_FIELD_INVALID:$.evidenceRefs[" + i + "]");
        }
    }

    public String issueUrl(String activity, long version, Object assetId) {
        if (ManagedPromotionBannerIdentity.objectKey(text(assetId)) == null) return null;
        if (activity == null || !activity.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || version < 1) {
            throw new IllegalArgumentException("PROMOTION_BANNER_IDENTITY_INVALID");
        }
        return "/api/promotions/" + activity + "/banner/" + version;
    }

    public ImageBytes read(String activity, long version) {
        if (activity == null || !activity.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || version < 1) throw unavailable();
        var row = db.one("""
            SELECT p.status,v.contract_json FROM nx_promotion p JOIN nx_promotion_version v
              ON v.activity_id=p.activity_id AND v.version=p.active_version
            WHERE p.deleted_at IS NULL AND p.activity_id=? AND p.active_version=? AND v.status='PUBLISHED'
              AND p.status IN ('ACTIVE','SCHEDULED') AND v.starts_at<=NOW(6) AND v.ends_at>NOW(6)
            """, activity, version);
        if (row == null) throw unavailable();
        var contract = parse(row.get("contract_json"));
        if (!"ACTIVE".equals(effectiveLifecycle(row.get("status"), contract, Instant.now()))) throw unavailable();
        String key = ManagedPromotionBannerIdentity.objectKey(text(contract.get("bannerAssetId")));
        if (key == null || !exists(key)) throw unavailable();
        return image(key, false);
    }

    private ImageBytes image(String key, boolean validating) {
        byte[] bytes;
        try (InputStream input = storage.get(key)) {
            bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
        } catch (IOException | RuntimeException failure) {
            throw new BizException(503, "PROMOTION_BANNER_UNAVAILABLE");
        }
        String type = ManagedPromotionBannerIdentity.contentType(key);
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !ManagedPromotionBannerIdentity.matchesImageType(type, bytes)) {
            throw validating ? invalid() : unavailable();
        }
        return new ImageBytes(type, bytes);
    }

    private boolean exists(String key) {
        try { return storage.exists(key); }
        catch (RuntimeException failure) { throw new BizException(503, "PROMOTION_BANNER_UNAVAILABLE"); }
    }

    private static BizException invalid() { return new BizException(422, "PROMOTION_FIELD_INVALID:bannerAssetId"); }
    private static BizException unavailable() { return new BizException(404, "PROMOTION_BANNER_UNAVAILABLE"); }
    public record ImageBytes(String contentType, byte[] bytes) {}
}
