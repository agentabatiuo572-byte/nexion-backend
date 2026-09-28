package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/** Reuses the locked operator configuration row; A2 owns approval and its history. */
public final class PhoneCalibrationConfigService {
    public static final String KEY = "E.compute.phoneCalibration.policy";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformConfigFacade config;
    private final Clock clock;
    public PhoneCalibrationConfigService(PlatformConfigFacade config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }
    public record State(long revision, PhoneCalibrationPolicy current, PhoneCalibrationPolicy scheduled) { }
    public record Proposal(long expectedRevision, long effectiveAt, List<java.math.BigDecimal> thresholds,
                           List<PhoneCalibrationPolicy.Rule> rules) { }

    public State read() { return effective(parse(config.activeValue(KEY).orElse(null))); }
    public PhoneCalibrationPolicy activePolicy() { return read().current(); }

    public PhoneCalibrationPolicy candidate(Proposal proposal, State state) {
        if (proposal == null || proposal.expectedRevision() != state.revision()) {
            throw new BizException(409, "PHONE_CALIBRATION_VERSION_CONFLICT");
        }
        if (proposal.effectiveAt() < 0 || proposal.effectiveAt() > clock.millis() + 366L * 86400000) {
            throw new BizException(422, "PHONE_CALIBRATION_EFFECTIVE_TIME_INVALID");
        }
        PhoneCalibrationPolicy policy = new PhoneCalibrationPolicy(state.revision() + 1,
                Math.max(clock.millis(), proposal.effectiveAt()), proposal.thresholds(), proposal.rules());
        policy.validate();
        return policy;
    }

    /** Must run in the caller's A2 replay transaction, not as a standalone public write. */
    public State publish(Proposal proposal) {
        config.insertAdminValueIfMissing(KEY, encode(new State(0, null, null)), "JSON", "e6_compute", "手机校准规则");
        State before = effective(parse(config.activeValueForUpdate(KEY)
                .orElseThrow(() -> new BizException(503, "PHONE_CALIBRATION_CONFIG_UNAVAILABLE"))));
        PhoneCalibrationPolicy next = candidate(proposal, before);
        // Do not silently discard another already-approved future publication.
        if (before.scheduled() != null) throw new BizException(409, "PHONE_CALIBRATION_PUBLICATION_PENDING");
        State after = next.effectiveAt() <= clock.millis()
                ? new State(next.version(), next, null) : new State(next.version(), before.current(), next);
        config.upsertAdminValue(KEY, encode(after), "JSON", "e6_compute", "手机校准规则 v" + next.version());
        State saved = read();
        if (!saved.equals(effective(after))) throw new BizException(503, "PHONE_CALIBRATION_READBACK_FAILED");
        return saved;
    }

    private State effective(State state) {
        return state.scheduled() != null && state.scheduled().effectiveAt() <= clock.millis()
                ? new State(state.revision(), state.scheduled(), null) : state;
    }
    private State parse(String value) {
        if (value == null) return new State(0, null, null);
        try {
            var tree = JSON.readTree(value);
            if (tree == null || !tree.isObject() || !tree.has("revision") || !tree.has("current") || !tree.has("scheduled")) {
                throw new IllegalArgumentException();
            }
            State state = JSON.readValue(value, State.class);
            if (state == null || state.revision() < 0) throw new IllegalArgumentException();
            if (state.current() != null) state.current().validate();
            if (state.scheduled() != null) state.scheduled().validate();
            long latest = Math.max(state.current() == null ? 0 : state.current().version(),
                    state.scheduled() == null ? 0 : state.scheduled().version());
            if (latest != state.revision() || (state.scheduled() != null && state.current() != null
                    && state.scheduled().version() <= state.current().version())) throw new IllegalArgumentException();
            return state;
        } catch (Exception invalid) {
            throw new BizException(503, "PHONE_CALIBRATION_CONFIG_UNAVAILABLE");
        }
    }
    private String encode(State value) {
        try {
            String json = JSON.writeValueAsString(value);
            // nx_config_item uses TEXT; reject oversize atomically instead of truncating rules.
            if (json.getBytes(StandardCharsets.UTF_8).length > 60000) throw new BizException(422, "PHONE_CALIBRATION_RULE_LIMIT");
            return json;
        } catch (BizException exception) { throw exception; }
        catch (Exception exception) { throw new BizException(503, "PHONE_CALIBRATION_CONFIG_UNAVAILABLE"); }
    }
}
