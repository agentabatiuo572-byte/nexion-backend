package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDispatchResult;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.dto.NovaSocialEventSyncRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@ApplicationService
@RequiredArgsConstructor
public class NovaSocialRuntimeService {
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)\\s*(s|min|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final String SOCIAL_CHANNEL = "social";
    private final NovaRepository novaRepository;
    private final NovaSocialRuntimeRepository runtimeRepository;
    private final OpsNovaService novaService;
    private final String leaseOwner = UUID.randomUUID().toString();

    public void runScheduledSync() {
        try {
            var sync = novaService.syncSocialEvents(
                    "nova-social-runtime-sync-" + System.currentTimeMillis(),
                    new NovaSocialEventSyncRequest(List.of(), 24, 12, "system", "定时同步已验证真实事件"));
            if (sync.getCode() != 0) {
                log.warn("Nova social source sync skipped: {}", sync.getMessage());
            }
        } catch (RuntimeException exception) {
            log.error("Nova social source sync failed; no synthetic event will be generated", exception);
        }
    }

    public void runScheduledDispatch() {
        try {
            NovaSocialDispatchResult result = dispatchAt(LocalDateTime.now());
            if (result.dispatched()) {
                log.info("Nova social notifications queued: eventId={}, users={}", result.eventId(), result.notificationCount());
            }
        } catch (RuntimeException exception) {
            log.error("Nova social internal notification dispatch failed", exception);
        }
    }

    @Transactional
    public NovaSocialDispatchResult dispatchAt(LocalDateTime now) {
        novaRepository.ensureTables();
        runtimeRepository.ensureRuntimeTables();
        NovaChannelView channel = novaRepository.channel(SOCIAL_CHANNEL).orElse(null);
        if (channel == null || !channel.enabled()) {
            return NovaSocialDispatchResult.skipped("SOCIAL_CHANNEL_DISABLED");
        }
        NovaTemplateView template = novaRepository.template(SOCIAL_CHANNEL).orElse(null);
        if (template == null || !"PUBLISHED".equalsIgnoreCase(template.status())) {
            return NovaSocialDispatchResult.skipped("SOCIAL_TEMPLATE_NOT_PUBLISHED");
        }

        Duration tick = parseDuration(channel.tick());
        Duration cooldown = parseDuration(channel.cooldown());
        if (tick == null || cooldown == null || tick.isZero() || cooldown.isZero()) {
            return NovaSocialDispatchResult.skipped("SOCIAL_CADENCE_INVALID");
        }
        if (runtimeRepository.latestNotificationAt().filter(last -> last.plus(tick).isAfter(now)).isPresent()) {
            return NovaSocialDispatchResult.skipped("SOCIAL_TICK_NOT_DUE");
        }

        long tickSeconds = Math.max(1, tick.toSeconds());
        long slot = Math.floorDiv(now.toEpochSecond(ZoneOffset.UTC), tickSeconds);
        String slotKey = "NOVA-SOCIAL-" + slot;
        long leaseSeconds = Math.max(5, Math.min(30, tickSeconds / 2));
        if (!runtimeRepository.claimSlot(slotKey, leaseOwner, now.plusSeconds(leaseSeconds), now)) {
            return NovaSocialDispatchResult.skipped("SOCIAL_SLOT_ALREADY_CLAIMED");
        }

        Map<String, Integer> weights = novaRepository.socialDistribution().stream()
                .filter(item -> item.pct() > 0)
                .collect(Collectors.toMap(NovaSocialDistributionItem::key, NovaSocialDistributionItem::pct,
                        (left, right) -> right, LinkedHashMap::new));
        Map<String, List<NovaSocialEventView>> candidates = new LinkedHashMap<>();
        weights.forEach((type, weight) -> {
            List<NovaSocialEventView> rows = novaRepository.activeSocialEventsByType(type, now, 100);
            if (!rows.isEmpty()) {
                candidates.put(type, rows);
            }
        });
        NovaSocialEventView event = weightedEvent(candidates, weights);
        if (event == null) {
            completeClaimOrThrow(slotKey, now);
            return NovaSocialDispatchResult.skipped("NO_ACTIVE_REAL_EVENT");
        }

        NovaSocialMessageRenderer.RenderedMessage zh = NovaSocialMessageRenderer.render(template, event, "ZH");
        NovaSocialMessageRenderer.RenderedMessage vi = NovaSocialMessageRenderer.render(template, event, "VI");
        NovaSocialMessageRenderer.RenderedMessage en = NovaSocialMessageRenderer.render(template, event, "EN");
        String ctaHref = "NONE".equalsIgnoreCase(template.cta()) ? "" : safe(template.cta());
        String bizNo = slotKey;
        int inserted = runtimeRepository.enqueueNotifications(
                event.id(), bizNo, zh.title(), zh.body(), vi.title(), vi.body(), en.title(), en.body(),
                ctaHref, now.minus(cooldown), now);
        if (inserted <= 0) {
            completeClaimOrThrow(slotKey, now);
            return NovaSocialDispatchResult.skipped("NO_USER_OUTSIDE_COOLDOWN");
        }
        if (runtimeRepository.markDispatchedIfStillActive(event.id(), now) != 1) {
            throw new IllegalStateException("NOVA_SOCIAL_EVENT_CHANGED_DURING_DISPATCH");
        }
        completeClaimOrThrow(slotKey, now);
        return new NovaSocialDispatchResult(true, inserted, event.id(), "QUEUED_INTERNAL_NOTIFICATION");
    }

    private void completeClaimOrThrow(String slotKey, LocalDateTime now) {
        if (!runtimeRepository.completeSlot(slotKey, leaseOwner, now)) {
            throw new IllegalStateException("NOVA_SOCIAL_SLOT_LEASE_LOST");
        }
    }

    private NovaSocialEventView weightedEvent(Map<String, List<NovaSocialEventView>> candidates,
                                              Map<String, Integer> weights) {
        int total = candidates.keySet().stream().mapToInt(type -> weights.getOrDefault(type, 0)).sum();
        if (total <= 0) {
            return null;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<String, List<NovaSocialEventView>> entry : candidates.entrySet()) {
            roll -= weights.getOrDefault(entry.getKey(), 0);
            if (roll < 0) {
                List<NovaSocialEventView> rows = entry.getValue();
                return rows.get(ThreadLocalRandom.current().nextInt(rows.size()));
            }
        }
        return null;
    }

    private Duration parseDuration(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        var matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2).toLowerCase()) {
                case "s" -> Duration.ofSeconds(amount);
                case "min" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> null;
            };
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
