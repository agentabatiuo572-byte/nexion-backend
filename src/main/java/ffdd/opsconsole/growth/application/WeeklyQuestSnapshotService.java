package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.domain.AccountWeeklyPeriod;
import ffdd.opsconsole.user.facade.UserWeeklyRegionFacade;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import org.springframework.core.env.Environment;
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
import org.springframework.transaction.annotation.Transactional;

/** A published week is immutable; completion and claims remain individual user progress. */
@Service
public class WeeklyQuestSnapshotService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final WeeklyQuestSnapshotMapper mapper;
    private final GrowthRhythmFacade rhythm;
    private final Clock clock;
    private final ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade availability;
    private final WeeklyRegionPolicyService regionPolicy;
    private final UserWeeklyRegionFacade users;
    private final Environment environment;

    /** Legacy direct construction; Spring uses the complete constructor below. */
    public WeeklyQuestSnapshotService(WeeklyQuestSnapshotMapper mapper, GrowthRhythmFacade rhythm, Clock clock) {
        this(mapper, rhythm, clock, null);
    }

    public WeeklyQuestSnapshotService(WeeklyQuestSnapshotMapper mapper, GrowthRhythmFacade rhythm, Clock clock,
            ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade availability) {
        this(mapper,rhythm,clock,availability,null,null,null);
    }

    @Autowired
    public WeeklyQuestSnapshotService(WeeklyQuestSnapshotMapper mapper, GrowthRhythmFacade rhythm, Clock clock,
            ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade availability,
            WeeklyRegionPolicyService regionPolicy,UserWeeklyRegionFacade users,Environment environment) {
        this.mapper = mapper;
        this.rhythm = rhythm;
        this.clock = clock;
        this.availability = availability;
        this.regionPolicy=regionPolicy;
        this.users=users;
        this.environment=environment;
    }

    /** R-1: ISO week of a stored (Beijing) timestamp, cut by the business calendar. */
    public static String weekKey(LocalDateTime time) {
        return ffdd.opsconsole.shared.config.BusinessCalendar.legacy().weekKey(time);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZONE)); }

    @Transactional(rollbackFor = Exception.class)
    public Header current() { legacyFixtureOnly(); return initialize(null, true); }

    @Transactional(rollbackFor = Exception.class)
    public Header beforeConfigurationChange() {
        // Keep the same mutex through the caller's mutation, including when this week's header already exists.
        mapper.ensureMutex();
        if (!"H3_CONFIG".equals(mapper.lockMutex())) throw new BizException(409,"H3_CONFIG_BUSY");
        if (regionPolicy!=null && activated(regionPolicy.readLocked(),clock.instant())) {
            freezeRegionalWindows(Set.of());
            return null;
        }
        return initialize(null, false);
    }

    private Header initialize(String eventWeek, boolean requireRhythm) {
        List<Header> open=users==null?mapper.legacySnapshot(now()):mapper.openLegacy(now());
        if (open.size()>1) throw new BizException(409,"WEEKLY_LEGACY_WINDOW_OVERLAP");
        if (!open.isEmpty()) return eventWeek==null || open.get(0).instanceKey().equals(eventWeek)?open.get(0):null;
        Header existing = mapper.find(weekKey(now()));
        if (existing != null) return contains(existing,clock.instant())?existing:null;
        mapper.ensureMutex();
        if (!"H3_CONFIG".equals(mapper.lockMutex())) throw new BizException(409,"H3_CONFIG_BUSY");
        // Waiting for another publisher can cross a week boundary. Recompute and reread its committed header.
        LocalDateTime current = now();
        String key = weekKey(current);
        if (eventWeek != null && !eventWeek.equals(key)) return null;
        existing = mapper.findAfterLock(key);
        if (existing != null) return contains(existing,clock.instant())?existing:null;
        LocalDate monday=ffdd.opsconsole.shared.config.BusinessCalendar.legacy().dateAtStorage(current).with(DayOfWeek.MONDAY);
        return publish(key,ffdd.opsconsole.shared.config.BusinessCalendar.legacy().storageStart(monday),ffdd.opsconsole.shared.config.BusinessCalendar.legacy().storageStart(monday.plusWeeks(1)),null,null,null,requireRhythm);
    }

    private Header publish(String key,LocalDateTime start,LocalDateTime end,String zone,LocalDate monday,String version,boolean requireRhythm) {
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
        Header header = new Header(key,start,end,policy.questBonusMultiplier(),
                policy.currentMonth(),items.size(),zone,monday,version);
        mapper.insertHeader(header);
        int ordinal = 0;
        for (Definition item : items.values()) mapper.insertItem(key,item,ordinal++);
        for (Definition definition : definitions) mapper.insertBinding(key,definition);
        return header;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<CanonicalQuestEventBinding> bindings(String eventType, LocalDateTime eventTs) {
        legacyFixtureOnly();
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
        legacyFixtureOnly();
        return eventTime != null && !eventTime.isAfter(now()) && weekKey(eventTime).equals(weekKey(now()))
                && mapper.publishedKey(weekKey(eventTime)) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Map<String,Object>> state(Header header, Long userId, String locale) {
        return eligible(userId,header,null)?mapper.state(header.instanceKey(),userId,locale):List.of();
    }

    @Transactional(rollbackFor = Exception.class)
    public List<String> legacyProgressCodes(Header header, Long userId) {
        return header.calendarVersion()==null && eligible(userId,header,null)?mapper.legacyProgressCodes(header.instanceKey(),userId):List.of();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean legacyClaimEligible(Header header, Long userId, String code) {
        return header.calendarVersion()==null && eligible(userId,header,null)
                && mapper.legacyClaimMission(header.instanceKey(),userId,code) != null;
    }

    public boolean isWeekly(String code) { return mapper.weeklyCode(code) > 0; }

    public boolean hasEventBindings(String eventType) {
        return mapper.liveWeeklyBindingCount(eventType)>0 || mapper.frozenEventBindings(eventType);
    }


    @Transactional(rollbackFor = Exception.class)
    public MissionDefinition mission(String code, LocalDateTime occurredAt) {
        legacyFixtureOnly();
        LocalDateTime eventTime = occurredAt == null ? now() : occurredAt;
        if (eventTime.isAfter(now()) || !weekKey(eventTime).equals(weekKey(now()))) return null;
        Header header = initialize(weekKey(eventTime), true);
        return header == null ? null : mapper.mission(header.instanceKey(),code,eventTime);
    }

    @Transactional(rollbackFor = Exception.class)
    public Reward lockReward(Header header,String code,Long userId) {
        if (!eligible(userId,header,null)) return null;
        Reward reward=mapper.lockReward(header.instanceKey(),code,userId);
        return contains(header,clock.instant())?reward:null;
    }

    private void legacyFixtureOnly() {
        if (users!=null) throw new IllegalStateException("WEEKLY_ACCOUNT_REQUIRED");
    }
    private boolean contains(Header header,Instant instant) {
        return header!=null && !instant.isBefore(AccountWeeklyPeriod.instant(header.eligibleFrom()))
                && instant.isBefore(AccountWeeklyPeriod.instant(header.eligibleUntil()));
    }
    private LocalDate monday(Header header) {
        return header.localWeekStart()!=null?header.localWeekStart():header.eligibleFrom().toLocalDate().with(DayOfWeek.MONDAY);
    }
    private boolean activated(WeeklyRegionPolicyService.State state,Instant instant) {
        return state.effectiveFrom()!=null && !instant.isBefore(state.effectiveFrom());
    }
    private UserWeeklyRegionFacade.RegisteredCallingCode lockUser(Long userId) {
        if (users==null || regionPolicy==null) throw new IllegalStateException("ACCOUNT_WEEKLY_DEPENDENCIES_REQUIRED");
        var runtime=UserAuthEnvironment.resolve(environment)
                .orElseThrow(()->new BizException(503,"USER_AUTH_ENVIRONMENT_UNSUPPORTED"));
        var account=users.lockActiveRegisteredCallingCode(userId,runtime==UserAuthEnvironment.SANDBOX);
        if (account==null) throw new BizException(404,"USER_NOT_FOUND_OR_INACTIVE");
        return account;
    }
    private void mutex() {
        mapper.ensureMutex();
        if (!"H3_CONFIG".equals(mapper.lockMutex())) throw new BizException(409,"H3_CONFIG_BUSY");
    }

    /** Multi-beneficiary projection locks every account before any of them can publish a definition. */
    @Transactional(rollbackFor=Exception.class)
    public List<Long> lockAccountsForProjection(List<Long> userIds) {
        List<Long> active=new ArrayList<>();
        for (Long userId:userIds.stream().distinct().sorted().toList()) {
            try { lockUser(userId); active.add(userId); }
            catch (BizException failure) {
                if (!(failure.getCode()==403 && "WEEKLY_ACCOUNT_UNAVAILABLE".equals(failure.getMessage()))
                        && !(failure.getCode()==404 && "USER_NOT_FOUND_OR_INACTIVE".equals(failure.getMessage()))) throw failure;
            }
        }
        return List.copyOf(active);
    }

    @Transactional(rollbackFor=Exception.class)
    public Header current(Long userId) {
        var account=lockUser(userId);
        mutex(); // The empty account-range gap must never be held while waiting for H3_CONFIG.
        Assignment previous=mapper.latestAssignment(userId);
        Header last=previous==null?null:mapper.findPublished(previous.instanceKey());
        if (contains(last,clock.instant())) return last;
        if (previous!=null && last==null) throw new BizException(409,"WEEKLY_PERIOD_HEADER_MISSING");
        Instant current=clock.instant();
        var policy=regionPolicy.readLocked();
        var selected=regionPolicy.resolve(policy,account.countryCode());
        Header header;
        if (!activated(policy,current)) {
            header=initialize(null,true);
        } else {
            Header legacy=mapper.findPublished(policy.lastLegacyInstanceKey());
            if (legacy==null || !AccountWeeklyPeriod.instant(legacy.eligibleUntil()).equals(policy.effectiveFrom()))
                throw new BizException(409,"WEEKLY_ACTIVATION_HEADER_MISMATCH");
            AccountWeeklyPeriod window=last==null || last.calendarVersion()==null
                    ?AccountWeeklyPeriod.successor(monday(legacy),policy.effectiveFrom(),selected.zoneId())
                    :AccountWeeklyPeriod.successor(monday(last),AccountWeeklyPeriod.instant(last.eligibleUntil()),selected.zoneId());
            if (!current.isBefore(window.until())) window=AccountWeeklyPeriod.regular(current,selected.zoneId());
            if (!window.contains(current) || window.from().isBefore(policy.effectiveFrom())
                    || (last!=null && (window.from().isBefore(AccountWeeklyPeriod.instant(last.eligibleUntil()))
                    || !window.localWeekStart().isAfter(monday(last)))))
                throw new BizException(409,"WEEKLY_PERIOD_SEQUENCE_CONFLICT");
            header=publishRegional(window,true);
        }
        if (header==null || !contains(header,clock.instant())) throw new BizException(409,"WEEKLY_PERIOD_CHANGED_RETRY");
        if (previous!=null && (!monday(header).isAfter(previous.localWeekStart())
                || header.eligibleFrom().isBefore(last.eligibleUntil())))
            throw new BizException(409,"WEEKLY_PERIOD_SEQUENCE_CONFLICT");
        if (mapper.insertAssignment(new Assignment(userId,monday(header),header.instanceKey(),selected.callingCode(),selected.revision()))!=1)
            throw new BizException(409,"WEEKLY_PERIOD_ASSIGNMENT_CONFLICT");
        return header;
    }

    @Transactional(rollbackFor=Exception.class)
    public boolean eligible(Long userId,Header header,LocalDateTime occurredAtStorage) {
        Header frozen=header;
        if (users!=null) {
            lockUser(userId);
            frozen=header==null?null:mapper.findPublished(header.instanceKey());
            if (frozen==null || !frozen.equals(header) || mapper.assigned(userId,header.instanceKey())!=1) return false;
        }
        return contains(frozen,clock.instant()) && (occurredAtStorage==null
                || (!occurredAtStorage.isAfter(now()) && contains(frozen,AccountWeeklyPeriod.instant(occurredAtStorage))));
    }
    private Header eventHeader(Long userId,LocalDateTime eventTs,String expectedKey) {
        if (eventTs==null || eventTs.isAfter(now())) return null;
        Header header;
        if (expectedKey==null) header=current(userId);
        else {
            lockUser(userId);
            if (expectedKey.matches("WEEK:[0-9]{4}-W[0-9]{2}")) mutex();
            header=mapper.findPublished(expectedKey);
            // Existing authoritative legacy facts predate per-account assignment. Adopt only their still-open old header.
            if (header!=null && header.calendarVersion()==null && expectedKey.matches("WEEK:[0-9]{4}-W[0-9]{2}")
                    && contains(header,clock.instant()) && mapper.assigned(userId,expectedKey)==0) {
                Header assigned=current(userId);
                if (!expectedKey.equals(assigned.instanceKey())) return null;
            }
        }
        return eligible(userId,header,eventTs)?header:null;
    }
    @Transactional(rollbackFor=Exception.class)
    public List<CanonicalQuestEventBinding> bindings(Long userId,String eventType,LocalDateTime eventTs,String expectedKey) {
        if (expectedKey==null) {
            if (!hasEventBindings(eventType)) return List.of();
            lockUser(userId);
            mutex();
            Assignment assignment=mapper.latestAssignment(userId);
            Header assigned=assignment==null?null:mapper.findPublished(assignment.instanceKey());
            if (!contains(assigned,clock.instant()) && mapper.liveWeeklyBindingCount(eventType)==0) {
                boolean openLegacyBinding=mapper.openLegacy(now()).stream()
                        .anyMatch(h->!mapper.bindings(h.instanceKey(),eventType).isEmpty());
                if (!openLegacyBinding) return List.of();
            }
        }
        Header header=eventHeader(userId,eventTs,expectedKey);
        if (header==null) return List.of();
        var result=mapper.bindings(header.instanceKey(),eventType);
        return contains(header,clock.instant())?result:List.of();
    }
    @Transactional(rollbackFor=Exception.class)
    public boolean publishedFor(Long userId,LocalDateTime eventTs,String expectedKey) {
        return eventHeader(userId,eventTs,expectedKey)!=null;
    }
    @Transactional(rollbackFor=Exception.class)
    public MissionDefinition mission(Long userId,String code,LocalDateTime occurredAt,String expectedKey) {
        LocalDateTime event=occurredAt==null?now():occurredAt;
        Header header=eventHeader(userId,event,expectedKey);
        if (header==null) return null;
        var result=mapper.mission(header.instanceKey(),code,event);
        return contains(header,clock.instant())?result:null;
    }
    @Transactional(rollbackFor=Exception.class)
    public void beforeRegionPolicyChange(Set<String> nextZones) {
        mutex();
        if (activated(regionPolicy.readLocked(),clock.instant())) freezeRegionalWindows(nextZones);
        else initialize(null,false);
    }

    private void freezeRegionalWindows(Set<String> additional) {
        var policy=regionPolicy.readLocked();
        Header legacy=mapper.findPublished(policy.lastLegacyInstanceKey());
        if (legacy==null) throw new BizException(409,"WEEKLY_ACTIVATION_HEADER_MISMATCH");
        Set<String> zones=new TreeSet<>(WeeklyRegionPolicyService.zones(policy.mappings()));
        zones.addAll(additional); zones.addAll(mapper.publishedZones());
        Instant current=clock.instant();
        List<AccountWeeklyPeriod> predecessors=zones.stream().map(zone->AccountWeeklyPeriod.regular(current,zone)).toList();
        for (String zone:zones) {
            Set<LocalDate> frozen=new HashSet<>();
            freezeTemplate(AccountWeeklyPeriod.successor(monday(legacy),policy.effectiveFrom(),zone),current,policy.effectiveFrom(),frozen);
            // Enumerate configured zones, never account rows. Each possible predecessor contributes its boundary.
            for (AccountWeeklyPeriod prior:predecessors)
                freezeTemplate(AccountWeeklyPeriod.successor(prior.localWeekStart().minusWeeks(1),prior.from(),zone),
                        current,policy.effectiveFrom(),frozen);
        }
    }

    private void freezeTemplate(AccountWeeklyPeriod window,Instant current,Instant activation,Set<LocalDate> frozen) {
        if (window.contains(current) && !window.from().isBefore(activation) && frozen.add(window.localWeekStart())
                && mapper.template(AccountWeeklyPeriod.CALENDAR_VERSION,window.zoneId(),window.localWeekStart())==null)
            publishRegional(window,false);
    }

    private Header publishRegional(AccountWeeklyPeriod period,boolean required) {
        String version=AccountWeeklyPeriod.CALENDAR_VERSION;
        Header existing=mapper.regional(version,period.zoneId(),period.localWeekStart(),period.storageFrom(),period.storageUntil());
        if (existing!=null) return existing;
        Header template=mapper.template(version,period.zoneId(),period.localWeekStart());
        String key="WEEK:"+UUID.randomUUID().toString().replace("-","");
        if (template!=null) {
            Header copy=new Header(key,period.storageFrom(),period.storageUntil(),template.questBonusMultiplier(),
                    template.rhythmMonth(),template.requiredTaskCount(),period.zoneId(),period.localWeekStart(),version);
            mapper.insertHeaderFromTemplate(copy,template.instanceKey());
            mapper.copyItems(template.instanceKey(),key); mapper.copyBindings(template.instanceKey(),key);
            return copy;
        }
        return publish(key,period.storageFrom(),period.storageUntil(),period.zoneId(),period.localWeekStart(),version,required);
    }
}
