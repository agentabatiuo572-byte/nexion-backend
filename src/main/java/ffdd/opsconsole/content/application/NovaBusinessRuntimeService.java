package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.CopyAudiencePhaseProvider;
import ffdd.opsconsole.content.domain.NovaBusinessEventFact;
import ffdd.opsconsole.content.domain.NovaBusinessFanoutProgress;
import ffdd.opsconsole.content.domain.NovaChannelDispatchResult;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialRuntimeRepository;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * One server-side Nova producer/gate for every non-social cadence channel.
 *
 * <p>Adapters only consume allowlisted, server-authoritative A4 facts. If an
 * upstream domain has not emitted the exact eligibility/state fact yet, the
 * channel is explicitly skipped; adjacent facts are never treated as a
 * substitute. This keeps cadence, kill, template, cooldown, dedupe and A4
 * emission in one implementation instead of nine diverging timers.
 */
@Slf4j
@ApplicationService
@RequiredArgsConstructor
public class NovaBusinessRuntimeService {
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^(\\d+)\\s*(s|min|h|d)$", Pattern.CASE_INSENSITIVE);
    private static final int FACT_BATCH_SIZE = 100;
    static final int FANOUT_BATCH_SIZE = 250;

    private final NovaRepository novaRepository;
    private final NovaSocialRuntimeRepository runtimeRepository;
    private final CopyAudiencePhaseProvider phaseProvider;
    private final EventOutboxService eventOutboxService;
    private final String leaseOwner = UUID.randomUUID().toString();

    public List<String> channelKeys() {
        novaRepository.ensureTables();
        LinkedHashSet<String> keys = new LinkedHashSet<>(NovaRuntimeAdapterCatalog.fixedSources().keySet());
        novaRepository.channels().stream()
                .filter(channel -> NovaRuntimeAdapterCatalog.resolve(channel.key(), channel.trigger()).isPresent())
                .map(NovaChannelView::key)
                .forEach(keys::add);
        return List.copyOf(keys);
    }

    @Transactional
    public NovaChannelDispatchResult runScheduledChannel(String channel) {
        NovaChannelDispatchResult result = dispatchChannelAt(channel, LocalDateTime.now());
        if (result.dispatched()) {
            log.info("Nova business notifications delivered: channel={}, source={}, users={}",
                    result.channel(), result.sourceEventId(), result.notificationCount());
        } else {
            log.debug("Nova business channel skipped: channel={}, reason={}",
                    result.channel(), result.reason());
        }
        return result;
    }

    public NovaChannelDispatchResult dispatchChannelAt(String channelKey, LocalDateTime now) {
        novaRepository.ensureTables();
        runtimeRepository.ensureRuntimeTables();
        NovaChannelView channel = novaRepository.channel(safe(channelKey)).orElse(null);
        if (channel == null) {
            return NovaChannelDispatchResult.skipped(safe(channelKey), "CHANNEL_NOT_CONFIGURED");
        }
        var source = NovaRuntimeAdapterCatalog.resolve(channel.key(), channel.trigger()).orElse(null);
        if (source == null) {
            return NovaChannelDispatchResult.skipped(channel.key(), "CHANNEL_ADAPTER_UNSUPPORTED");
        }
        Adapter adapter = new Adapter(channel.key(), source.eventNames(), source.targeted());
        if (!channel.enabled()) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "CHANNEL_DISABLED");
        }
        NovaTemplateView template = novaRepository.template(adapter.channel()).orElse(null);
        if (template == null || !"PUBLISHED".equalsIgnoreCase(template.status())) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "TEMPLATE_NOT_PUBLISHED");
        }

        String currentPhase = safe(phaseProvider.currentPhase()).toUpperCase(Locale.ROOT);
        if (!currentPhase.matches("P[1-6]")) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "H1_PHASE_UNAVAILABLE");
        }
        Duration tick = parseDuration(channel.tick());
        Duration cooldown = effectiveCooldown(adapter.channel(), channel.cooldown(), currentPhase);
        if (cooldown == null) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "H1_PHASE_EXPLICIT_SKIP");
        }
        if (tick == null || tick.isZero() || cooldown.isZero()) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "CADENCE_INVALID");
        }

        String notificationType = notificationType(adapter.channel());
        if (runtimeRepository.latestNotificationAt(notificationType)
                .filter(last -> last.plus(tick).isAfter(now))
                .isPresent()) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "TICK_NOT_DUE");
        }

        List<NovaBusinessEventFact> facts = runtimeRepository.pendingBusinessFacts(
                adapter.channel(), adapter.eventNames(), FACT_BATCH_SIZE);
        if (facts.isEmpty()) {
            return NovaChannelDispatchResult.skipped(
                    adapter.channel(), "NO_REAL_FACT:" + String.join(",", adapter.eventNames()));
        }

        long tickSeconds = Math.max(1, tick.toSeconds());
        long slot = Math.floorDiv(now.toEpochSecond(ZoneOffset.UTC), tickSeconds);
        String slotKey = "NOVA-" + adapter.channel() + "-" + slot;
        long leaseSeconds = Math.max(5, Math.min(30, tickSeconds / 2));
        if (!runtimeRepository.claimSlot(slotKey, leaseOwner, now.plusSeconds(leaseSeconds), now)) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "SLOT_ALREADY_CLAIMED");
        }

        int deliveredTotal = 0;
        String firstSourceEventId = "";
        for (NovaBusinessEventFact fact : facts) {
            boolean claimed = runtimeRepository.claimBusinessFact(
                    adapter.channel(), fact.sourceEventId(), fact.eventName(), now);
            var existingFanout = adapter.targeted() ? Optional.<NovaBusinessFanoutProgress>empty()
                    : runtimeRepository.businessFanoutProgress(adapter.channel(), fact.sourceEventId());
            if (!claimed && existingFanout.isEmpty()) {
                continue;
            }
            if (adapter.targeted() && (fact.userId() == null || fact.userId() <= 0)) {
                runtimeRepository.completeBusinessFact(
                        adapter.channel(), fact.sourceEventId(), "SKIPPED",
                        "SOURCE_USER_UNAVAILABLE", 0, now);
                continue;
            }

            String bizNo = "NOVA-" + adapter.channel() + "-" + fact.sourceEventId();
            if (!adapter.targeted()) {
                NovaBusinessFanoutProgress progress = existingFanout.orElse(new NovaBusinessFanoutProgress(0, 0));
                Optional<Long> upper = runtimeRepository.fanoutBatchUpperUserId(
                        progress.cursorUserId(), FANOUT_BATCH_SIZE);
                if (upper.isEmpty()) {
                    runtimeRepository.completeBusinessFact(
                            adapter.channel(), fact.sourceEventId(), "DELIVERED",
                            "FANOUT_COMPLETE", progress.notificationCount(), now);
                    completeClaimOrThrow(slotKey, now);
                    return NovaChannelDispatchResult.skipped(adapter.channel(), "FANOUT_COMPLETE_NO_NEW_USERS");
                }
                String batchBizNo = bizNo + "-B" + upper.get();
                int inserted = runtimeRepository.enqueueBusinessNotificationBatch(
                        adapter.channel(), notificationType, batchBizNo,
                        progress.cursorUserId(), upper.get(),
                        template.titleZh(), template.bodyZh(), template.titleVi(), template.bodyVi(),
                        fallback(template.titleEn(), template.titleZh()),
                        fallback(template.bodyEn(), template.bodyZh()),
                        "NONE".equalsIgnoreCase(template.cta()) ? "" : safe(template.cta()),
                        now.minus(cooldown), now);
                int delivered = runtimeRepository.markNotificationsDelivered(batchBizNo, now);
                if (delivered != inserted) {
                    throw new IllegalStateException("NOVA_BUSINESS_DELIVERY_COUNT_MISMATCH");
                }
                publishNotificationFacts(batchBizNo, currentPhase, delivered, adapter.channel(), now);
                if (!runtimeRepository.advanceBusinessFanout(
                        adapter.channel(), fact.sourceEventId(), progress.cursorUserId(), upper.get(), delivered, now)) {
                    throw new IllegalStateException("NOVA_BUSINESS_FANOUT_CURSOR_CONFLICT");
                }
                boolean more = runtimeRepository.fanoutBatchUpperUserId(upper.get(), 1).isPresent();
                if (!more) {
                    runtimeRepository.completeBusinessFact(
                            adapter.channel(), fact.sourceEventId(), "DELIVERED", "FANOUT_COMPLETE",
                            progress.notificationCount() + delivered, now);
                }
                completeClaimOrThrow(slotKey, now);
                return new NovaChannelDispatchResult(
                        true, adapter.channel(), delivered, fact.sourceEventId(),
                        more ? "FANOUT_BATCH_COMMITTED_MORE_PENDING" : "FANOUT_COMPLETE");
            }
            int inserted = runtimeRepository.enqueueBusinessNotifications(
                    adapter.channel(), notificationType, fact.sourceEventId(),
                    adapter.targeted() ? fact.userId() : null,
                    bizNo,
                    template.titleZh(), template.bodyZh(),
                    template.titleVi(), template.bodyVi(),
                    fallback(template.titleEn(), template.titleZh()),
                    fallback(template.bodyEn(), template.bodyZh()),
                    "NONE".equalsIgnoreCase(template.cta()) ? "" : safe(template.cta()),
                    now.minus(cooldown), now);
            if (inserted <= 0) {
                runtimeRepository.completeBusinessFact(
                        adapter.channel(), fact.sourceEventId(), "SKIPPED",
                        "NO_USER_OUTSIDE_COOLDOWN", 0, now);
                continue;
            }

            int delivered = runtimeRepository.markNotificationsDelivered(bizNo, now);
            if (delivered != inserted) {
                throw new IllegalStateException("NOVA_BUSINESS_DELIVERY_COUNT_MISMATCH");
            }
            publishNotificationFacts(bizNo, currentPhase, delivered, adapter.channel(), now);
            runtimeRepository.completeBusinessFact(
                    adapter.channel(), fact.sourceEventId(), "DELIVERED",
                    "SERVER_CANONICAL_NOTIFICATION", delivered, now);
            deliveredTotal += delivered;
            if (firstSourceEventId.isEmpty()) {
                firstSourceEventId = fact.sourceEventId();
            }
        }

        completeClaimOrThrow(slotKey, now);
        if (deliveredTotal == 0) {
            return NovaChannelDispatchResult.skipped(adapter.channel(), "NO_USER_OUTSIDE_COOLDOWN");
        }
        return new NovaChannelDispatchResult(
                true, adapter.channel(), deliveredTotal, firstSourceEventId,
                "DELIVERED_FROM_SERVER_FACT");
    }

    private void publishNotificationFacts(
            String bizNo, String currentPhase, int delivered, String channel, LocalDateTime now) {
        var notificationFacts = runtimeRepository.notificationFacts(bizNo, currentPhase, now);
        if (notificationFacts.size() != delivered) {
            throw new IllegalStateException("NOVA_BUSINESS_DELIVERY_FACT_MISMATCH");
        }
        notificationFacts.forEach(notification -> {
                Map<String, Object> payload = Map.of(
                        "notification_id", notification.notificationId(),
                        "channel", channel,
                        "priority", notification.priority());
                eventOutboxService.publishUserEvent(
                        "NOVA_NOTIFICATION", String.valueOf(notification.notificationId()), "nova.push_sent",
                        notification.userId(), notification.phase(), notification.accountAgeMonths(),
                        notification.cohort(), payload);
                eventOutboxService.publishUserEvent(
                        "NOTIFICATION", String.valueOf(notification.notificationId()), "notification.delivered",
                        notification.userId(), notification.phase(), notification.accountAgeMonths(),
                        notification.cohort(), Map.of(
                                "campaign_id", bizNo,
                                "notification_id", notification.notificationId(),
                                "kind", notification.kind(),
                                "priority", notification.priority()));
        });
    }

    static Map<String, List<String>> adapterContracts() {
        return NovaRuntimeAdapterCatalog.fixedSources().entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().eventNames()));
    }

    private void completeClaimOrThrow(String slotKey, LocalDateTime now) {
        if (!runtimeRepository.completeSlot(slotKey, leaseOwner, now)) {
            throw new IllegalStateException("NOVA_BUSINESS_SLOT_LEASE_LOST");
        }
    }

    private Duration effectiveCooldown(String channel, String configured, String phase) {
        if ("tradein".equals(channel)) {
            return switch (phase) {
                case "P1", "P2" -> null;
                case "P3", "P4" -> Duration.ofMinutes(60);
                default -> Duration.ofHours(24);
            };
        }
        if ("taskLockMonthly".equals(channel)) {
            return switch (phase) {
                case "P1", "P2" -> Duration.ofDays(30);
                case "P3", "P4" -> Duration.ofDays(7);
                default -> Duration.ofHours(84);
            };
        }
        return parseDuration(configured);
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
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> Duration.ofSeconds(amount);
                case "min" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> null;
            };
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private String notificationType(String channel) {
        return "NOVA_" + channel.toUpperCase(Locale.ROOT);
    }

    private String fallback(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : safe(fallback);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record Adapter(String channel, List<String> eventNames, boolean targeted) {
    }
}
