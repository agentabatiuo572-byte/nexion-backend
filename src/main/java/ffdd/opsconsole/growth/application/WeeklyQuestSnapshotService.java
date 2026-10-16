package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper;
import ffdd.opsconsole.growth.mapper.WeeklyQuestSnapshotMapper.*;
import ffdd.opsconsole.growth.mapper.QuestCanonicalEventBindingMapper.CanonicalQuestEventBinding;
import ffdd.opsconsole.growth.mapper.QuestCompletionFactMapper.MissionDefinition;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A published week is immutable; completion and claims remain individual user progress. */
@Service
public class WeeklyQuestSnapshotService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final WeeklyQuestSnapshotMapper mapper;
    private final GrowthRhythmFacade rhythm;
    private final Clock clock;
    private final ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade availability;

    /** Legacy direct construction; Spring uses the complete constructor below. */
    public WeeklyQuestSnapshotService(WeeklyQuestSnapshotMapper mapper, GrowthRhythmFacade rhythm, Clock clock) {
        this(mapper, rhythm, clock, null);
    }

    @Autowired
    public WeeklyQuestSnapshotService(WeeklyQuestSnapshotMapper mapper, GrowthRhythmFacade rhythm, Clock clock,
            ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade availability) {
        this.mapper = mapper;
        this.rhythm = rhythm;
        this.clock = clock;
        this.availability = availability;
    }

    public static String weekKey(LocalDateTime time) {
        return String.format(Locale.ROOT, "WEEK:%04d-W%02d", time.get(WeekFields.ISO.weekBasedYear()),
                time.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZONE)); }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Header current() { return initialize(null, true); }

    @Transactional(rollbackFor = Exception.class)
    public Header beforeConfigurationChange() {
        // Keep the same mutex through the caller's mutation, including when this week's header already exists.
        mapper.ensureMutex();
        if (!"H3_CONFIG".equals(mapper.lockMutex())) throw new BizException(409,"H3_CONFIG_BUSY");
        return initialize(null, false);
    }

    private Header initialize(String eventWeek, boolean requireRhythm) {
        Header existing = mapper.find(weekKey(now()));
        if (existing != null) return existing;
        mapper.ensureMutex();
        if (!"H3_CONFIG".equals(mapper.lockMutex())) throw new BizException(409,"H3_CONFIG_BUSY");
        // Waiting for another publisher can cross a week boundary. Recompute and reread its committed header.
        LocalDateTime current = now();
        String key = weekKey(current);
        if (eventWeek != null && !eventWeek.equals(key)) return null;
        existing = mapper.findAfterLock(key);
        if (existing != null) return existing;
        GrowthRhythmSnapshot policy = rhythm.snapshot();
        if (policy == null || !policy.reliable() || policy.currentMonth() <= 0
                || policy.questBonusMultiplier() == null || policy.questBonusMultiplier().signum() <= 0) {
            if (!requireRhythm) return null; // No usable week was publishable before initial H1 setup/repair.
            throw new BizException(409,"H1_RHYTHM_UNAVAILABLE");
        }
        List<Definition> definitions = mapper.lockDefinitions();
        if (availability != null) {
            boolean genesis = definitions.stream().noneMatch(d -> "weekly_t2_genesis_browse".equals(d.questCode()))
                    || availability.genesisAvailableForMissionPublication();
            boolean exchange = definitions.stream().noneMatch(d -> "weekly_t2_nex_swap".equals(d.questCode()))
                    || availability.exchangeAvailableForMissionPublication();
            definitions = definitions.stream().filter(d -> genesis || !"weekly_t2_genesis_browse".equals(d.questCode()))
                    .filter(d -> exchange || !"weekly_t2_nex_swap".equals(d.questCode())).toList();
        }
        Map<Long,Definition> items = new LinkedHashMap<>();
        for (Definition definition : definitions) items.putIfAbsent(definition.missionId(), definition);
        LocalDateTime start = current.toLocalDate().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        Header header = new Header(key,start,start.plusWeeks(1),policy.questBonusMultiplier(),
                policy.currentMonth(),items.size());
        mapper.insertHeader(header);
        int ordinal = 0;
        for (Definition item : items.values()) mapper.insertItem(key,item,ordinal++);
        for (Definition definition : definitions) mapper.insertBinding(key,definition);
        return header;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<CanonicalQuestEventBinding> bindings(String eventType, LocalDateTime eventTs) {
        LocalDateTime current = now();
        if (eventTs == null || eventTs.isAfter(current) || !weekKey(eventTs).equals(weekKey(current))) return List.of();
        Header header = mapper.find(weekKey(current));
        if (header == null) {
            if (mapper.liveWeeklyBindingCount(eventType) == 0) return List.of();
            header = initialize(weekKey(eventTs), true);
        }
        if (header == null || !header.instanceKey().equals(weekKey(eventTs))) return List.of();
        return mapper.bindings(header.instanceKey(),eventType);
    }

    public boolean publishedFor(LocalDateTime eventTime) {
        return eventTime != null && !eventTime.isAfter(now()) && weekKey(eventTime).equals(weekKey(now()))
                && mapper.publishedKey(weekKey(eventTime)) != null;
    }

    public List<Map<String,Object>> state(Header header, Long userId, String locale) {
        return mapper.state(header.instanceKey(),userId,locale);
    }

    public List<String> legacyProgressCodes(Header header, Long userId) {
        return mapper.legacyProgressCodes(header.instanceKey(),userId);
    }

    public boolean legacyClaimEligible(Header header, Long userId, String code) {
        return header.instanceKey().equals(weekKey(now()))
                && mapper.legacyClaimMission(header.instanceKey(),userId,code) != null;
    }

    public boolean isWeekly(String code) { return mapper.weeklyCode(code) > 0; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MissionDefinition mission(String code, LocalDateTime occurredAt) {
        LocalDateTime eventTime = occurredAt == null ? now() : occurredAt;
        if (eventTime.isAfter(now()) || !weekKey(eventTime).equals(weekKey(now()))) return null;
        Header header = initialize(weekKey(eventTime), true);
        return header == null ? null : mapper.mission(header.instanceKey(),code,eventTime);
    }

    public Reward lockReward(Header header,String code,Long userId) {
        if (!header.instanceKey().equals(weekKey(now()))) return null;
        return mapper.lockReward(header.instanceKey(),code,userId);
    }
}
