package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.DayOneInstanceFacade;
import ffdd.opsconsole.growth.facade.DayOneInstanceFacade.DayOneInstanceSnapshot;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.DayOneDefinitionBinding;
import ffdd.opsconsole.growth.mapper.DayOneInstanceMapper.RegisteredUser;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates exactly one immutable Day-One definition snapshot during registration. */
@Service
public class DayOneInstanceFacadeAdapter implements DayOneInstanceFacade {
    private static final DateTimeFormatter INSTANCE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final int DEFAULT_ELIGIBILITY_HOURS = 72;
    private static final int FULL_REWARD_HOURS = 24;
    private final DayOneInstanceMapper mapper;
    private final GrowthRhythmFacade growthRhythmFacade;

    public DayOneInstanceFacadeAdapter(DayOneInstanceMapper mapper, GrowthRhythmFacade growthRhythmFacade) {
        this.mapper = mapper;
        this.growthRhythmFacade = growthRhythmFacade;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DayOneInstanceSnapshot provisionForRegisteredUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(422, "DAY_ONE_SNAPSHOT_USER_REQUIRED");
        RegisteredUser user = mapper.lockRegisteredUser(userId);
        if (user == null || user.enteredAt() == null) throw new BizException(404, "DAY_ONE_SNAPSHOT_USER_NOT_ACTIVE");
        String instanceKey = "DAY_ONE:" + INSTANCE_TIME.format(user.enteredAt());
        DayOneInstanceSnapshot existing = mapper.lockExistingInstance(userId, instanceKey);
        if (existing != null) return existing;

        List<Long> activeDefinitionIds = safeIds(mapper.lockActiveDayOneDefinitionIds());
        int activeDefinitionCount = activeDefinitionIds.size();
        List<DayOneDefinitionBinding> bindings = activeDefinitionCount == 0
                ? List.of() : safe(mapper.lockActiveDayOneDefinitionBindings());
        Map<Long, List<DayOneDefinitionBinding>> items = groupItems(bindings);
        if (!activeDefinitionIds.equals(new ArrayList<>(items.keySet()))) {
            throw new BizException(503, "DAY_ONE_SNAPSHOT_BINDING_REQUIRED");
        }
        String status = activeDefinitionCount == 0 ? "EMPTY" : "SNAPSHOT";
        int eligibilityHours = eligibilityHours(mapper.lockConfigValue("growth.quest.day_one.eligibility_hours"));
        LocalDateTime eligibleUntil = user.enteredAt().plusHours(eligibilityHours);
        if ("EMPTY".equals(status)) {
            DayOneInstanceSnapshot draft = new DayOneInstanceSnapshot(
                    null, userId, instanceKey, status, user.enteredAt(), eligibilityHours, FULL_REWARD_HOURS,
                    eligibleUntil, null, null, null, 0,
                    definitionHash(status, eligibilityHours, null, null, null, bindings));
            mapper.insertInstanceIfAbsent(draft);
            DayOneInstanceSnapshot stored = mapper.lockExistingInstance(userId, instanceKey);
            if (stored == null) throw new IllegalStateException("DAY_ONE_SNAPSHOT_WRITE_UNAVAILABLE");
            return stored;
        }
        String triReward = DayOneTriRewardPolicy.normalizePolicy(
                mapper.lockConfigValue("growth.quest.day_one.tri_reward"));
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || !rhythm.reliable() || rhythm.currentMonth() <= 0
                || rhythm.questBonusMultiplier() == null || rhythm.questBonusMultiplier().signum() <= 0) {
            throw new BizException(503, "H1_RHYTHM_UNAVAILABLE");
        }
        DayOneInstanceSnapshot draft = new DayOneInstanceSnapshot(
                null, userId, instanceKey, status, user.enteredAt(), eligibilityHours, FULL_REWARD_HOURS,
                eligibleUntil, triReward, rhythm.questBonusMultiplier(), rhythm.currentMonth(), items.size(),
                definitionHash(status, eligibilityHours, triReward, rhythm.questBonusMultiplier(),
                        rhythm.currentMonth(), bindings));
        int inserted = mapper.insertInstanceIfAbsent(draft);
        DayOneInstanceSnapshot stored = mapper.lockExistingInstance(userId, instanceKey);
        if (stored == null) throw new IllegalStateException("DAY_ONE_SNAPSHOT_WRITE_UNAVAILABLE");
        if (inserted == 0) return stored;
        int ordinal = 0;
        for (List<DayOneDefinitionBinding> itemBindings : items.values()) {
            DayOneDefinitionBinding item = itemBindings.get(0);
            if (mapper.insertItem(stored.id(), item.sourceMissionId(), item.questCode(), item.name(),
                    item.category(), item.actionRoute(), item.rewardPoints(), ++ordinal) != 1) {
                throw new IllegalStateException("DAY_ONE_SNAPSHOT_ITEM_WRITE_FAILED");
            }
            for (DayOneDefinitionBinding binding : itemBindings) {
                if (mapper.insertBinding(stored.id(), binding.sourceMissionId(), binding.sourceBindingId(), binding.bindingCode(),
                        binding.producer(), binding.eventType(), binding.userIdField()) != 1) {
                    throw new IllegalStateException("DAY_ONE_SNAPSHOT_BINDING_WRITE_FAILED");
                }
            }
        }
        return stored;
    }

    private int eligibilityHours(String raw) {
        if (raw == null || !raw.trim().matches("^[0-9]{1,3}$")) return DEFAULT_ELIGIBILITY_HOURS;
        int parsed = Integer.parseInt(raw.trim());
        return parsed >= 24 && parsed <= 720 ? parsed : DEFAULT_ELIGIBILITY_HOURS;
    }

    private Map<Long, List<DayOneDefinitionBinding>> groupItems(List<DayOneDefinitionBinding> bindings) {
        Map<Long, List<DayOneDefinitionBinding>> grouped = new LinkedHashMap<>();
        for (DayOneDefinitionBinding binding : bindings) {
            if (binding == null || binding.sourceMissionId() == null || binding.sourceMissionId() <= 0
                    || binding.sourceBindingId() == null || binding.sourceBindingId() <= 0
                    || blank(binding.questCode()) || blank(binding.name()) || blank(binding.category())
                    || blank(binding.actionRoute()) || binding.rewardPoints() < 0 || blank(binding.bindingCode())
                    || blank(binding.producer()) || blank(binding.eventType()) || blank(binding.userIdField())) {
                throw new BizException(503, "DAY_ONE_SNAPSHOT_BINDING_INVALID");
            }
            grouped.computeIfAbsent(binding.sourceMissionId(), ignored -> new ArrayList<>()).add(binding);
        }
        return grouped;
    }

    private String definitionHash(String status, int eligibilityHours, String triReward,
            BigDecimal questBonusMultiplier, Integer rhythmMonth,
            List<DayOneDefinitionBinding> bindings) {
        StringBuilder material = new StringBuilder(status).append('|').append(eligibilityHours)
                .append('|').append(FULL_REWARD_HOURS).append('|').append(triReward)
                .append('|').append(questBonusMultiplier).append('|').append(rhythmMonth);
        safe(bindings).stream().sorted(Comparator.comparing(DayOneDefinitionBinding::sourceMissionId)
                .thenComparing(DayOneDefinitionBinding::bindingCode)).forEach(binding -> material
                        .append('|').append(binding.sourceMissionId()).append('|').append(binding.sourceBindingId())
                        .append('|').append(binding.questCode())
                        .append('|').append(binding.name()).append('|').append(binding.category())
                        .append('|').append(binding.actionRoute()).append('|').append(binding.rewardPoints())
                        .append('|').append(binding.bindingCode()).append('|')
                        .append(binding.producer().toUpperCase(Locale.ROOT)).append('|')
                        .append(binding.eventType()).append('|').append(binding.userIdField()));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("DAY_ONE_SNAPSHOT_HASH_UNAVAILABLE", ex);
        }
    }

    private List<DayOneDefinitionBinding> safe(List<DayOneDefinitionBinding> bindings) {
        return bindings == null ? List.of() : bindings;
    }

    private List<Long> safeIds(List<Long> ids) {
        if (ids == null) return List.of();
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalStateException("DAY_ONE_SNAPSHOT_DEFINITION_ID_INVALID");
        }
        return ids;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
