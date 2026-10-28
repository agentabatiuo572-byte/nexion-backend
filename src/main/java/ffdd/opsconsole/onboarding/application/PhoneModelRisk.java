package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Model consistency checks run before a phone calibration draws or assigns a value. A reported
 * model is only as honest as the client, so each check compares it with facts a casual edit of
 * the model field does not change. Each check is OFF, OBSERVE (record a server-only flag) or
 * ENFORCE (the calibration stays pending and nothing is drawn).
 */
public final class PhoneModelRisk {
    public enum Mode { OFF, OBSERVE, ENFORCE }

    /** The same installation reported a different model than the one it is locked to (iOS and Android). */
    public static final String MODEL_CHANGED = "MODEL_CHANGED";
    /** The same Android installation resolved to a different chip than the one it is locked to; follows the lock's mode. */
    public static final String SOC_CHANGED = "SOC_CHANGED";
    /** The iOS version is older than the model shipped with or newer than it can run. */
    public static final String OS_MISMATCH = "OS_MISMATCH";
    /** The screen scale is not one the model has. */
    public static final String SCALE_MISMATCH = "SCALE_MISMATCH";
    /** The point size is not one of the model's, which Display Zoom can cause; never enforced. */
    public static final String SCREEN_MISMATCH = "SCREEN_MISMATCH";
    /** An iPhone report without OS version or screen facts, as older App builds send. */
    public static final String SIGNALS_MISSING = "SIGNALS_MISSING";
    /** The account reported many distinct models within the window (iOS and Android); never enforced. */
    public static final String ACCOUNT_MODEL_CHURN = "ACCOUNT_MODEL_CHURN";
    /** Reasons this class can make a calibration pending; the App only ever sees {@link #APP_REASON}. */
    public static final Set<String> ENFORCEABLE = Set.of(MODEL_CHANGED, SOC_CHANGED, OS_MISMATCH, SCALE_MISMATCH, SIGNALS_MISSING);
    public static final String APP_REASON = "MODEL_UNVERIFIED";
    /** Server-only result fields: the flagged checks, and the installation's locked model. */
    public static final String RISK_FLAGS = "riskFlags";
    public static final String INSTALL_MODEL_LOCK = "installModelLock";

    /**
     * Operator settings, saved directly by E6 without A2. A missing setting keeps the default: the
     * installation lock enforced from launch, every other check observed.
     */
    public record Settings(long revision, Mode installModelLock, Mode osVersion, Mode screenScale, Mode screenSize,
                           Mode missingSignals, Mode accountModelChurn, Integer churnWindowDays, Integer churnModelCount,
                           Long updatedAt, Long updatedBy, String reason) {
        public static final Settings DEFAULTS = new Settings(0, Mode.ENFORCE, Mode.OBSERVE, Mode.OBSERVE, Mode.OBSERVE,
                Mode.OBSERVE, Mode.OBSERVE, 30, 3, null, null, null);

        public Settings {
            installModelLock = installModelLock == null ? Mode.ENFORCE : installModelLock;
            osVersion = osVersion == null ? Mode.OBSERVE : osVersion;
            screenScale = screenScale == null ? Mode.OBSERVE : screenScale;
            screenSize = screenSize == null ? Mode.OBSERVE : screenSize;
            missingSignals = missingSignals == null ? Mode.OBSERVE : missingSignals;
            accountModelChurn = accountModelChurn == null ? Mode.OBSERVE : accountModelChurn;
            churnWindowDays = churnWindowDays == null ? 30 : churnWindowDays;
            churnModelCount = churnModelCount == null ? 3 : churnModelCount;
        }

        public void validate() {
            if (revision < 0 || screenSize == Mode.ENFORCE || accountModelChurn == Mode.ENFORCE
                    || churnWindowDays < 1 || churnWindowDays > 365 || churnModelCount < 2 || churnModelCount > 20
                    || (reason != null && (reason.length() > 200 || reason.codePoints().anyMatch(Character::isISOControl)))) {
                throw new BizException(422, "PHONE_RISK_SETTINGS_INVALID");
            }
        }
    }

    /** What the device reported beyond its model; any field may be missing. */
    public record Facts(String platform, String osVersion, Double pixelRatio, Integer screenWidth, Integer screenHeight) { }
    public record Finding(String code, Mode mode) { }
    /** Every flagged check, and the reason to hold the calibration when one of them is enforced. */
    public record Outcome(List<Finding> findings, String pendingReason) {
        public List<String> flags() { return findings.stream().map(Finding::code).toList(); }
    }

    private PhoneModelRisk() { }

    /**
     * Runs the checks for one report. {@code lockChanged} and {@code accountModels} come from the
     * account's stored calibrations; {@code rule} is the matched iPhone rule, or null when none matched.
     */
    public static Outcome evaluate(Settings settings, Facts facts, PhoneCalibrationPolicy.IosModelRule rule,
                                   boolean lockChanged, int accountModels) {
        return evaluate(settings, facts, rule, lockChanged, false, accountModels);
    }

    public static Outcome evaluate(Settings settings, Facts facts, PhoneCalibrationPolicy.IosModelRule rule,
                                   boolean lockChanged, boolean chipChanged, int accountModels) {
        List<Finding> findings = new ArrayList<>();
        if (lockChanged) add(findings, MODEL_CHANGED, settings.installModelLock());
        if (chipChanged) add(findings, SOC_CHANGED, settings.installModelLock());
        if (accountModels >= settings.churnModelCount()) add(findings, ACCOUNT_MODEL_CHURN, settings.accountModelChurn());
        if (rule != null && "ios".equals(PhoneCalibrationPolicy.normalize(facts.platform()))) iosFacts(settings, facts, rule, findings);
        String pending = findings.stream().filter(finding -> finding.mode() == Mode.ENFORCE).map(Finding::code)
                .findFirst().orElse(null);
        return new Outcome(List.copyOf(findings), pending);
    }

    private static void iosFacts(Settings settings, Facts facts, PhoneCalibrationPolicy.IosModelRule rule, List<Finding> findings) {
        int[] os = osVersion(facts.osVersion());
        boolean screenKnown = facts.pixelRatio() != null && facts.screenWidth() != null && facts.screenHeight() != null;
        if (os == null || !screenKnown) add(findings, SIGNALS_MISSING, settings.missingSignals());
        if (os != null) {
            int[] min = rule.minOsVersion() == null ? null : osVersion(rule.minOsVersion());
            boolean tooOld = min != null && (os[0] < min[0] || os[0] == min[0] && os[1] < min[1]);
            boolean tooNew = rule.maxOsMajor() != null && os[0] > rule.maxOsMajor();
            if (tooOld || tooNew) add(findings, OS_MISMATCH, settings.osVersion());
        }
        if (facts.pixelRatio() != null && !rule.scales().isEmpty()
                && !rule.scales().contains((int) Math.round(facts.pixelRatio()))) add(findings, SCALE_MISMATCH, settings.screenScale());
        if (screenKnown && !rule.screens().isEmpty()) {
            String size = Math.min(facts.screenWidth(), facts.screenHeight()) + "x" + Math.max(facts.screenWidth(), facts.screenHeight());
            if (rule.screens().stream().map(PhoneModelRisk::screen).noneMatch(size::equals)) add(findings, SCREEN_MISMATCH, settings.screenSize());
        }
    }

    private static void add(List<Finding> findings, String code, Mode mode) {
        if (mode != Mode.OFF) findings.add(new Finding(code, mode));
    }

    /** "17", "17.5" or "17.5.1" as {major, minor}; anything else is null. */
    public static int[] osVersion(String value) {
        if (value == null || !value.trim().matches("\\d{1,2}(\\.\\d{1,2}){0,2}")) return null;
        String[] parts = value.trim().split("\\.");
        int major = Integer.parseInt(parts[0]);
        return major < 1 ? null : new int[] {major, parts.length > 1 ? Integer.parseInt(parts[1]) : 0};
    }

    /** "WxH" in points, either orientation, as "short x long"; anything else is null. */
    public static String screen(String value) {
        if (value == null || !value.matches("\\d{3,4}x\\d{3,4}")) return null;
        String[] parts = value.split("x");
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        return Math.min(a, b) + "x" + Math.max(a, b);
    }
}
