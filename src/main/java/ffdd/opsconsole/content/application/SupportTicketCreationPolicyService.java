package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.mapper.SupportTicketCreationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One account budget for every formal ticket creation path; replies and reopening are unaffected. */
@ApplicationService
@RequiredArgsConstructor
@Slf4j
public class SupportTicketCreationPolicyService {
    static final String PREFIX = "support.ticket.creation.";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int WINDOW_HOURS = 24;
    private final SupportOwnershipService ownership;
    private final SupportTicketCreationMapper mapper;
    private final PlatformConfigFacade configFacade;
    private final Clock clock;

    public record CreationPolicy(boolean allowed, String reasonCode, long retryAfterSeconds,
            LocalDateTime retryAt, String existingTicketNo, int cooldownSeconds, int windowHours,
            int maxCreatedInWindow, int maxActiveTickets, int createdInWindow, int activeTickets) {}

    private record Limits(int cooldown, int daily, int active) {}
    private record Snapshot(LocalDateTime now, Limits limits,
            List<SupportTicketCreationMapper.CreationTicket> recent, List<String> active) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CreationPolicy policy(Long userId) {
        return evaluate(snapshot(userId), null, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDateTime requireAllowed(Long userId, String category, String title, String body) {
        Snapshot snapshot = snapshot(userId);
        CreationPolicy policy = evaluate(snapshot, category, title, body);
        if (!policy.allowed()) throw new SupportTicketCreationRejectedException(policy);
        return snapshot.now();
    }

    private Snapshot snapshot(Long userId) {
        ownership.lockCustomer(userId);
        // Read time after acquiring the mutex, so a waiting request cannot use an expired decision.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Limits limits = new Limits(configured("cooldown_seconds", 60, 86400),
                configured("max_per_24h", 10, 1000), configured("max_active", 3, 1000));
        return new Snapshot(now, limits, mapper.currentRecent(userId, now.minusHours(WINDOW_HOURS)),
                mapper.currentActive(userId));
    }

    private CreationPolicy evaluate(Snapshot snapshot, String category, String title, String body) {
        var recent = snapshot.recent();
        var limits = snapshot.limits();
        if (category != null && title != null && body != null) {
            String normalizedCategory = category.strip().toLowerCase(Locale.ROOT);
            String normalizedTitle = normalizeText(title);
            String normalizedBody = normalizeText(body);
            for (var ticket : recent) {
                if (ticket.category() != null && ticket.category().strip().toLowerCase(Locale.ROOT).equals(normalizedCategory)
                        && normalizeText(ticket.title()).equals(normalizedTitle)
                        && normalizedBody.equals(normalizeText(mapper.initialBody(ticket.id())))) {
                    return result(snapshot, "SUPPORT_TICKET_CREATE_DUPLICATE",
                            ticket.createdAt().plusHours(WINDOW_HOURS), ticket.ticketNo());
                }
            }
        }
        if (snapshot.active().size() >= limits.active()) {
            return result(snapshot, "SUPPORT_TICKET_CREATE_ACTIVE_LIMIT", null, snapshot.active().get(0));
        }
        if (recent.size() >= limits.daily()) {
            // If an older policy allowed more tickets, enough rows must expire to fall below the new limit.
            LocalDateTime retryAt = recent.get(limits.daily() - 1).createdAt().plusHours(WINDOW_HOURS);
            return result(snapshot, "SUPPORT_TICKET_CREATE_DAILY_LIMIT", retryAt, null);
        }
        if (!recent.isEmpty()) {
            LocalDateTime retryAt = recent.get(0).createdAt().plusSeconds(limits.cooldown());
            if (retryAt.isAfter(snapshot.now())) {
                return result(snapshot, "SUPPORT_TICKET_CREATE_COOLDOWN", retryAt, null);
            }
        }
        return result(snapshot, null, null, null);
    }

    private CreationPolicy result(Snapshot snapshot, String reason, LocalDateTime retryAt, String existing) {
        long retrySeconds = retryAt == null ? 0 : Math.max(0, Duration.between(snapshot.now(), retryAt).getSeconds());
        return new CreationPolicy(reason == null, reason, retrySeconds, retryAt, existing,
                snapshot.limits().cooldown(), WINDOW_HOURS, snapshot.limits().daily(), snapshot.limits().active(),
                snapshot.recent().size(), snapshot.active().size());
    }

    static String normalizeText(String value) {
        if (value == null) return "";
        return WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    private int configured(String suffix, int fallback, int maximum) {
        String key = PREFIX + suffix;
        String value = configFacade.activeValue(key).orElse(null);
        try {
            int parsed = value == null ? -1 : Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
            // A malformed or disabled configuration never removes the admission limits.
        }
        log.warn("Invalid or missing ticket creation configuration {}; using bounded default {}", key, fallback);
        return fallback;
    }
}
