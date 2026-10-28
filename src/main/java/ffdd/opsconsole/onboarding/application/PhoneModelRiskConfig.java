package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;

/**
 * Phone model risk settings in nx_config_item. Unlike the calibration rules they are saved
 * directly by E6 (no A2): a revision guards concurrent edits and every save is audited by the caller.
 */
public final class PhoneModelRiskConfig {
    public static final String KEY = "E.compute.phoneRisk.settings";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PhoneModelRiskConfig.class);

    private final PlatformConfigFacade config;
    private final Clock clock;

    public PhoneModelRiskConfig(PlatformConfigFacade config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** Never fails a calibration: an unreadable setting falls back to the defaults and is logged. */
    public PhoneModelRisk.Settings read() {
        try {
            return parse(config.activeValue(KEY).orElse(null));
        } catch (RuntimeException unreadable) {
            LOG.error("phone model risk settings {} are unreadable; using defaults", KEY, unreadable);
            return PhoneModelRisk.Settings.DEFAULTS;
        }
    }

    public PhoneModelRisk.Settings save(PhoneModelRisk.Settings next, long expectedRevision, long actor, String reason) {
        if (next == null) throw new BizException(422, "PHONE_RISK_SETTINGS_INVALID");
        config.insertAdminValueIfMissing(KEY, encode(PhoneModelRisk.Settings.DEFAULTS), "JSON", "e6_compute", "手机机型风控");
        PhoneModelRisk.Settings before;
        try {
            before = parse(config.activeValueForUpdate(KEY).orElse(null));
        } catch (BizException unreadable) {
            // read() shows the defaults (revision 0) for an unreadable value; let E6 overwrite it from there.
            LOG.error("phone model risk settings {} are unreadable; replacing them", KEY, unreadable);
            before = PhoneModelRisk.Settings.DEFAULTS;
        }
        if (before.revision() != expectedRevision) throw new BizException(409, "PHONE_RISK_SETTINGS_VERSION_CONFLICT");
        PhoneModelRisk.Settings saved = new PhoneModelRisk.Settings(before.revision() + 1, next.installModelLock(),
                next.osVersion(), next.screenScale(), next.screenSize(), next.missingSignals(), next.accountModelChurn(),
                next.churnWindowDays(), next.churnModelCount(), clock.millis(), actor, reason == null ? null : reason.trim());
        saved.validate();
        config.upsertAdminValue(KEY, encode(saved), "JSON", "e6_compute", "手机机型风控 r" + saved.revision());
        return saved;
    }

    private static PhoneModelRisk.Settings parse(String value) {
        if (value == null || value.isBlank()) return PhoneModelRisk.Settings.DEFAULTS;
        try {
            PhoneModelRisk.Settings settings = JSON.readValue(value, PhoneModelRisk.Settings.class);
            settings.validate();
            return settings;
        } catch (Exception invalid) {
            throw new BizException(503, "PHONE_RISK_SETTINGS_UNAVAILABLE");
        }
    }

    private static String encode(PhoneModelRisk.Settings settings) {
        try {
            return JSON.writeValueAsString(settings);
        } catch (Exception invalid) {
            throw new BizException(503, "PHONE_RISK_SETTINGS_UNAVAILABLE");
        }
    }
}
