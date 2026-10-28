package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * The Android chip-tier presets shipped with the code: reference data for the E6 draft only.
 * Nothing here is a published rule until A2 approves a proposal.
 */
public final class AndroidSocPresetCatalog {
    private static final String RESOURCE = "/phone-calibration/android-soc-presets.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AndroidSocPresetCatalog.class);
    private static volatile Catalog cached;

    public record AmbiguousId(String id, List<String> chips, String note) { }
    public record Excluded(String chip, String reason) { }
    public record Catalog(String catalogVersion, String verifiedOn, String scope, String matchBy, String computeUnit,
                          String valueSource, List<String> sources, String notice, List<AmbiguousId> ambiguousIds,
                          List<Excluded> excluded, List<PhoneCalibrationPolicy.AndroidSocRule> tiers) { }

    private AndroidSocPresetCatalog() { }

    /** Parsed and validated once: the bundled resource cannot change while the process runs. */
    public static Catalog load() {
        Catalog catalog = cached;
        if (catalog == null) cached = catalog = read();
        return catalog;
    }

    private static Catalog read() {
        try (InputStream in = AndroidSocPresetCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            Catalog catalog = JSON.readValue(in, Catalog.class);
            // The same rules as a published policy, so loading the catalog can never stage an invalid draft.
            new PhoneCalibrationPolicy(1, 0, List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3), BigDecimal.TEN),
                    catalog.tiers(), List.of()).validate();
            return catalog;
        } catch (BizException | java.io.IOException | IllegalStateException invalid) {
            LOG.error("Android chip preset catalog {} is unusable", RESOURCE, invalid);
            throw new BizException(503, "PHONE_CALIBRATION_ANDROID_PRESETS_UNAVAILABLE");
        }
    }
}
