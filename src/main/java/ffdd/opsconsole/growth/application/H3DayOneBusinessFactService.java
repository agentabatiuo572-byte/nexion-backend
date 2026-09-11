package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.H3DayOneBusinessFactReceiptMapper;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called only after a canonical business write, inside that write's transaction. */
@Service
@RequiredArgsConstructor
public class H3DayOneBusinessFactService {
    private final DayOneInstanceMapper instances;
    private final QuestCompletionFactMapper missions;
    private final H3DayOneBusinessFactReceiptMapper receipts;
    private final EventOutboxService outbox;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void record(Long userId, H3DayOneBusinessFactContract rule) {
        // The mapper also excludes sandbox users. Missing/expired/legacy snapshots
        // cannot be backfilled by a later profile save or payment-method change.
        if (userId == null || userId <= 0 || rule == null || !userId.equals(missions.lockActiveUser(userId))) return;
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        var bindings = instances.listInWindowSnapshotBindings(List.of(userId), rule.eventType(), occurredAt);
        if (bindings == null) return;
        var binding = bindings.stream().filter(b -> b != null && userId.equals(b.userId())
                && rule.eventType().equals(b.eventType())
                && rule.matches(b.producer(), b.questCode(), b.userIdField())).findFirst().orElse(null);
        if (binding == null) return;
        var mission = missions.lockDayOneSnapshotMissionAt(userId, binding.sourceMissionId(),
                rule.questCode(), binding.instanceKey(), occurredAt);
        if (mission == null) return;
        String status = missions.lockUserMissionStatus(userId, mission.missionId(), mission.instanceKey());
        if (status != null && Set.of("COMPLETED", "CLAIMABLE", "CLAIMED")
                .contains(status.trim().toUpperCase(Locale.ROOT))) return;
        Map<String, Object> attribution = missions.attribution(userId);
        if (attribution == null) throw new BizException(503, "H3_DAY_ONE_FACT_ATTRIBUTION_UNAVAILABLE");
        String phase = String.valueOf(attribution.get("phase")).trim().toUpperCase(Locale.ROOT);
        if (phase.matches("[1-6]")) phase = "P" + phase;
        Integer age;
        try { age = Integer.valueOf(String.valueOf(attribution.get("accountAgeMonths"))); }
        catch (NumberFormatException ex) { throw new BizException(503, "H3_DAY_ONE_FACT_ATTRIBUTION_UNAVAILABLE"); }
        if (receipts.insertIfAbsent(userId, mission.instanceKey(), rule.eventType()) != 1) return;
        outbox.publishUserEventAt("H3_DAY_ONE_BUSINESS", userId + ":" + mission.instanceKey() + ":" + rule.name(),
                rule.eventType(), userId, phase, age, String.valueOf(attribution.get("cohort")), occurredAt,
                Map.of("questCode", rule.questCode(), "instanceKey", mission.instanceKey()));
    }
}
