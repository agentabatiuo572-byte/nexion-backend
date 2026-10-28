package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * The iPhone preset catalog shipped with the code. It is reference data for the
 * E6 draft only: nothing here is a published rule until A2 approves a proposal.
 */
public final class IosModelPresetCatalog {
    private static final String RESOURCE = "/phone-calibration/ios-model-presets.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public record Catalog(String catalogVersion, String verifiedOn, String scope, String matchBy, String computeUnit,
                          String valueSource, List<String> sources, String notice,
                          List<PhoneCalibrationPolicy.IosModelRule> models) { }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IosModelPresetCatalog.class);
    private static volatile Catalog cached;

    private IosModelPresetCatalog() { }

    /** Parsed and validated once: the bundled resource cannot change while the process runs. */
    public static Catalog load() {
        Catalog catalog = cached;
        if (catalog == null) cached = catalog = read();
        return catalog;
    }

    private static Catalog read() {
        try (InputStream in = IosModelPresetCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            Catalog catalog = JSON.readValue(in, Catalog.class);
            // The same rules as a published policy, so loading the catalog can never stage an invalid draft.
            new PhoneCalibrationPolicy(1, 0, List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3), BigDecimal.TEN),
                    List.of(), catalog.models()).validate();
            return catalog;
        } catch (BizException | java.io.IOException | IllegalStateException invalid) {
            LOG.error("iPhone preset catalog {} is unusable", RESOURCE, invalid);
            throw new BizException(503, "PHONE_CALIBRATION_IOS_PRESETS_UNAVAILABLE");
        }
    }
}
