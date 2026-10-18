package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import ffdd.opsconsole.growth.mapper.WeeklyRegionPolicyMapper;
import ffdd.opsconsole.growth.mapper.WeeklyRegionPolicyMapper.LegacyWindow;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.VietnamBusinessPeriod;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One atomic A2 policy. Existing account assignments remain the calendar authority. */
@Service
@RequiredArgsConstructor
public class WeeklyRegionPolicyService {
    public static final String KEY = "growth.quest.weekly_region_policy";
    public static final String TARGET = "growth.h3.weekly-region-policy";
    public static final String DEFAULT_ZONE = "Asia/Ho_Chi_Minh";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();
    private final PlatformConfigFacade config;
    private final WeeklyRegionPolicyMapper mapper;
    private final Clock clock;

    public record Mapping(String callingCode, String zoneId) {}
    public record State(long revision, String defaultZoneId, List<Mapping> mappings,
                        Instant effectiveFrom, String lastLegacyInstanceKey) {}
    public record ZoneSelection(String callingCode, String zoneId, long revision, String source) {}
    public record Activation(Instant effectiveFrom, String lastLegacyInstanceKey) {}
    public record View(long revision, String defaultZoneId, List<Mapping> mappings, Instant effectiveFrom,
                       String lastLegacyInstanceKey, String activation, String source, Activation nextActivation) {}
    public record Proposal(long expectedRevision, List<Mapping> mappings,
                           Instant effectiveFrom, String lastLegacyInstanceKey) {}

    public State read() {
        return parse(config.activeValue(KEY).orElse(null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public State readLocked() {
        return parse(config.activeValueForUpdate(KEY).orElse(null));
    }

    public View overview() {
        State state = read();
        return new View(state.revision(), DEFAULT_ZONE, state.mappings(), state.effectiveFrom(),
                state.lastLegacyInstanceKey(), "NEXT_ACCOUNT_WEEK",
                state.revision() == 0 ? "DEFAULT" : "PUBLISHED", state.revision() == 0 ? activationPreview() : null);
    }

    public ZoneSelection resolve(String countryCode) {
        return resolve(read(),countryCode);
    }

    public ZoneSelection resolve(State state,String countryCode) {
        String code = normalizeCallingCode(countryCode);
        return state.mappings().stream().filter(mapping -> mapping.callingCode().equals(code)).findFirst()
                .map(mapping -> new ZoneSelection(code, mapping.zoneId(), state.revision(), "MAPPED"))
                .orElseGet(() -> new ZoneSelection(code, DEFAULT_ZONE, state.revision(), "DEFAULT"));
    }

    public Set<String> configuredZones() {
        return zones(read().mappings());
    }

    public static Set<String> zones(List<Mapping> mappings) {
        Set<String> result = new LinkedHashSet<>();
        result.add(DEFAULT_ZONE);
        mappings.forEach(mapping -> result.add(mapping.zoneId()));
        return Set.copyOf(result);
    }

    public Proposal proposal(Map<String, Object> params) {
        if (params == null || !params.containsKey("expectedRevision") || !params.containsKey("mappings")
                || !Set.of("expectedRevision", "mappings", "effectiveFrom", "lastLegacyInstanceKey")
                        .containsAll(params.keySet())
                || !(params.get("expectedRevision") instanceof Number revision)
                || !revision.toString().matches("[0-9]+")) throw invalid();
        try {
            Proposal proposal = JSON.convertValue(params, Proposal.class);
            if (proposal.expectedRevision() > 0
                    && (params.containsKey("effectiveFrom") || params.containsKey("lastLegacyInstanceKey"))) throw invalid();
            return proposal;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** Read-only validation used both by proposal canonicalization and A2 replay. */
    public State candidate(Proposal proposal, State before) {
        if (proposal == null || proposal.expectedRevision() != before.revision()) {
            throw new BizException(409, "WEEKLY_REGION_VERSION_CONFLICT");
        }
        List<Mapping> mappings = canonicalMappings(proposal.mappings());
        Instant effectiveFrom = before.effectiveFrom();
        String legacyKey = before.lastLegacyInstanceKey();
        if (before.revision() == 0) {
            Activation preview = activationPreview();
            if (preview == null || !preview.effectiveFrom().equals(proposal.effectiveFrom())
                    || !preview.lastLegacyInstanceKey().equals(proposal.lastLegacyInstanceKey())) {
                throw new BizException(409, "WEEKLY_REGION_ACTIVATION_CONFLICT");
            }
            effectiveFrom = preview.effectiveFrom();
            legacyKey = preview.lastLegacyInstanceKey();
        } else if (proposal.effectiveFrom() != null || proposal.lastLegacyInstanceKey() != null) {
            throw invalid();
        }
        State after = new State(Math.addExact(before.revision(), 1), DEFAULT_ZONE, mappings, effectiveFrom, legacyKey);
        encode(after);
        return after;
    }

    /** H3_CONFIG is held by the caller; no user row may be locked on this path. */
    @Transactional(propagation = Propagation.MANDATORY)
    public State publish(Proposal proposal) {
        if (!A2ReplayContext.isReplaying()) throw new BizException(409, "A2_CONFIRMATION_REQUIRED");
        config.insertAdminValueIfMissing(KEY, encode(defaultState()), "JSON", "growth_quest", "每周任务地区映射");
        State before = parse(config.activeValueForUpdate(KEY)
                .orElseThrow(() -> new BizException(503, "WEEKLY_REGION_POLICY_UNAVAILABLE")));
        State after = candidate(proposal, before);
        config.upsertAdminValue(KEY, encode(after), "JSON", "growth_quest", "每周任务地区映射 v" + after.revision());
        State saved = read();
        if (!saved.equals(after)) throw new BizException(503, "WEEKLY_REGION_READBACK_FAILED");
        return saved;
    }

    private Activation activationPreview() {
        Instant now = clock.instant();
        List<LegacyWindow> windows = mapper.openLegacy(LocalDateTime.ofInstant(now, VietnamBusinessPeriod.STORAGE_ZONE));
        if (windows == null || windows.size() != 1) return null;
        LegacyWindow window = windows.get(0);
        if (window.instanceKey() == null || !window.instanceKey().matches("WEEK:[0-9]{4}-W[0-9]{2}")
                || window.eligibleFrom() == null || window.eligibleUntil() == null) return null;
        Instant start = window.eligibleFrom().atZone(VietnamBusinessPeriod.STORAGE_ZONE).toInstant();
        Instant end = window.eligibleUntil().atZone(VietnamBusinessPeriod.STORAGE_ZONE).toInstant();
        return !now.isBefore(start) && now.isBefore(end) ? new Activation(end, window.instanceKey()) : null;
    }

    public static String normalizeCallingCode(String raw) {
        String value = raw == null ? "" : raw.trim().replace(" ", "");
        if (value.startsWith("+")) value = value.substring(1);
        return value.matches("[1-9][0-9]{0,2}") ? "+" + value : "";
    }

    private List<Mapping> canonicalMappings(List<Mapping> mappings) {
        if (mappings == null || mappings.size() > 999) throw invalid();
        Map<String, Mapping> unique = new TreeMap<>();
        for (Mapping mapping : mappings) {
            if (mapping == null) throw invalid();
            String code = normalizeCallingCode(mapping.callingCode());
            String zone = mapping.zoneId();
            if (code.isEmpty() || zone == null || !zone.contains("/") || zone.startsWith("Etc/") || !ZONES.contains(zone)
                    || unique.putIfAbsent(code, new Mapping(code, zone)) != null) throw invalid();
        }
        return List.copyOf(unique.values());
    }

    private State parse(String value) {
        if (value == null) return defaultState();
        try {
            State state = JSON.readValue(value, State.class);
            if (state == null || state.revision() < 0 || !DEFAULT_ZONE.equals(state.defaultZoneId())
                    || !canonicalMappings(state.mappings()).equals(state.mappings())
                    || (state.revision() == 0 && (state.effectiveFrom() != null || state.lastLegacyInstanceKey() != null))
                    || (state.revision() > 0 && (state.effectiveFrom() == null || state.lastLegacyInstanceKey() == null
                        || !state.lastLegacyInstanceKey().matches("WEEK:[0-9]{4}-W[0-9]{2}")))) throw invalid();
            return state;
        } catch (Exception exception) {
            throw new BizException(503, "WEEKLY_REGION_POLICY_UNAVAILABLE");
        }
    }

    public String encode(State state) {
        try {
            String value = JSON.writeValueAsString(state);
            if (value.getBytes(StandardCharsets.UTF_8).length > 60000) throw invalid();
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BizException(503, "WEEKLY_REGION_POLICY_UNAVAILABLE");
        }
    }

    private State defaultState() {
        return new State(0, DEFAULT_ZONE, List.of(new Mapping("+84", DEFAULT_ZONE),
                new Mapping("+86", "Asia/Shanghai")), null, null);
    }

    private BizException invalid() { return new BizException(422, "WEEKLY_REGION_POLICY_INVALID"); }
}
