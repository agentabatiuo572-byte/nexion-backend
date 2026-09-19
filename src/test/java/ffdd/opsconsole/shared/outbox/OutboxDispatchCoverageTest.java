package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the root cause of the A3 backlog: a producer could publish a new
 * event type that no delivery channel selected, and nothing failed — the fact
 * simply aged in PENDING forever.
 *
 * <p>Every event type found in a publish call must be either dispatched to a
 * bus consumer, carried by the developer-webhook canonical channel, or listed
 * as record-only. Adding a producer without choosing one of those is a build
 * failure rather than a silent backlog.
 */
class OutboxDispatchCoverageTest {
    private static final Pattern PUBLISH = Pattern.compile(
            "\\.publish(?:UserEventAt|UserEvent)?\\(\\s*\"([^\"]+)\"\\s*,\\s*[^,]+,\\s*\"([^\"]+)\"");

    @Test
    void everyProducedEventTypeHasADeliveryPath() throws IOException {
        Set<String> produced = producedEventTypes();
        assertThat(produced).as("producer scan must find event types").isNotEmpty();

        Set<String> covered = new LinkedHashSet<>(EventOutboxService.RECORD_ONLY_EVENT_TYPES);
        covered.addAll(dispatcherEventTypes());
        covered.addAll(developerWebhookEventTypes());
        covered.addAll(familySchedulerEventTypes());

        assertThat(produced)
                .as("each produced event type must be dispatched, webhook-carried, or explicitly record-only")
                .allSatisfy(type -> assertThat(covered)
                        .as("event type %s has no delivery path", type)
                        .contains(type));
    }

    @Test
    void recordOnlyTypesAreNeverAlsoDispatched() {
        assertThat(EventOutboxService.RECORD_ONLY_EVENT_TYPES)
                .as("a type cannot be both dispatched and retired as record-only")
                .doesNotContainAnyElementsOf(dispatcherEventTypes());
    }

    /**
     * Retirement matches {@code event_type} with {@code IN}, which is an exact
     * comparison. A name that is a proper prefix of another covered name is
     * therefore a silent no-op: it binds a value no row can equal, so the fact
     * ages in PENDING forever while the tick reports success. The previous
     * retirement set carried {@code "JANUS_STRATEGY_"} and
     * {@code "JANUS_DEVICE_COMMAND_"} for producers that build names by
     * concatenation, so neither the ACK/FAILED device commands nor the
     * publish/pause/archive strategy facts were ever retired.
     */
    @Test
    void noDeliveryPathIsAPrefixOfAnother() {
        Set<String> covered = new LinkedHashSet<>(EventOutboxService.RECORD_ONLY_EVENT_TYPES);
        covered.addAll(dispatcherEventTypes());
        covered.addAll(developerWebhookEventTypes());
        covered.addAll(familySchedulerEventTypes());

        assertThat(covered)
                .as("a prefix entry can never match an exact IN comparison")
                .allSatisfy(type -> assertThat(covered.stream()
                        .filter(other -> !other.equals(type) && other.startsWith(type))
                        .toList())
                        .as("event type %s is a proper prefix of a longer covered type", type)
                        .isEmpty());
    }

    /**
     * The producer scan only sees literal third arguments, so a name built by
     * concatenation is invisible to it — that blind spot is how the two prefix
     * entries survived. Producers now reference
     * {@link JanusOutboxEventTypes}, so every Janus name is checkable, and this
     * asserts the record-only set covers all of them.
     */
    @Test
    void everyJanusOutboxEventTypeIsRecordOnly() {
        assertThat(EventOutboxService.RECORD_ONLY_EVENT_TYPES)
                .as("Janus facts have no bus consumer, so all of them must be retired")
                .containsAll(JanusOutboxEventTypes.ALL);
        assertThat(JanusOutboxEventTypes.ALL)
                .as("device command and strategy lifecycle names must be enumerated, not concatenated")
                .contains("JANUS_DEVICE_COMMAND_ACKED", "JANUS_DEVICE_COMMAND_FAILED",
                        "JANUS_STRATEGY_PUBLISH", "JANUS_STRATEGY_PAUSE", "JANUS_STRATEGY_ARCHIVE");
    }

    private static Set<String> producedEventTypes() throws IOException {
        Set<String> types = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = PUBLISH.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    types.add(matcher.group(2));
                }
            }
        }
        return types;
    }

    /** Mirrors the runtime allowlist so the guard fails when the two drift apart. */
    private static Set<String> dispatcherEventTypes() {
        Set<String> types = new LinkedHashSet<>(List.of(
                EventOutboxDispatchScheduler.SUPPORTED_EVENT_TYPE,
                EventOutboxDispatchScheduler.TAMPER_EVENT_TYPE,
                EventOutboxDispatchScheduler.TAMPER_CONFIG_EVENT_TYPE,
                EventOutboxDispatchScheduler.D5_WITHDRAWAL_LIMIT_CHANGED_EVENT_TYPE,
                EventOutboxDispatchScheduler.D4_WALLET_LEDGER_EVENT_TYPE,
                EventOutboxDispatchScheduler.D6_FX_QUOTE_CHANGED_EVENT_TYPE,
                EventOutboxDispatchScheduler.K4_WITHDRAWAL_ESCALATED_EVENT_TYPE,
                EventOutboxDispatchScheduler.VRANK_PROMOTION_COMPLETED_EVENT_TYPE));
        types.addAll(EventOutboxDispatchScheduler.C2_HIGH_RISK_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.C1_AUDIT_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.C5_SECURITY_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.C3_ASSET_ADJUSTMENT_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.D1_TOPUP_LIFECYCLE_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.D2_WITHDRAWAL_LIFECYCLE_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.D3_TREASURY_LIFECYCLE_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.H3_QUEST_FACT_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.H3_WEEKLY_PARTICIPATION_SOURCE_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.H3_WEEKLY_EXCHANGE_REFERRAL_SOURCE_EVENT_TYPES);
        types.addAll(EventOutboxDispatchScheduler.F1_PASSIVE_EVAL_EVENT_TYPES);
        return types;
    }

    /** The canonical channel selects by event name; those facts are never record-only. */
    private static Set<String> developerWebhookEventTypes() {
        return Set.of(
                "account.updated", "billing.invoice.created", "checkout.completed", "checkout.started",
                "compute.job.completed", "compute.job.failed", "earnings.credited", "earnings.updated",
                "market.curve_advanced", "market.updated", "order.completed", "order.created",
                "order.paid", "order.refunded", "order.updated", "task.completed", "task.failed");
    }

    /**
     * Bounded family schedulers that isolate one evidence family from the broad
     * scan: L6 behaviour facts and the F4 configuration alert. They deliver, so
     * their types must never be retired as record-only.
     */
    private static Set<String> familySchedulerEventTypes() {
        return Set.of("app.page_viewed", "app.element_clicked", "leadership_pool.settlement_blocked");
    }
}
