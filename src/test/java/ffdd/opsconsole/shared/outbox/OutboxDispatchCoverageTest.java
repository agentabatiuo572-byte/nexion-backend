package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /**
     * Only the outbox service's own publish methods carry an event type in the
     * third argument. Other {@code publish} receivers (tamper detection, mappers,
     * repositories) take unrelated payloads and must not be scanned.
     */
    private static final Pattern PUBLISH = Pattern.compile(
            "(?:outbox|outboxService|eventOutboxService)\\.publish(?:UserEventAt|UserEvent)?\\(");

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
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = PUBLISH.matcher(source);
                while (matcher.find()) {
                    types.addAll(resolveThirdArgument(source, matcher));
                }
            }
        }
        return types;
    }

    /**
     * Resolves the event-type argument beyond a bare literal. The original scan
     * read only literal third arguments, so a producer that passed the name
     * through a local constant or a helper parameter was invisible — that is how
     * dozens of facts with no delivery channel reached PENDING and stayed there.
     * Same-file {@code String} constants and the literals supplied to the
     * enclosing method at its call sites are now included.
     */
    private static Set<String> resolveThirdArgument(String source, Matcher matcher) {
        List<String> args = splitArguments(source, matcher.end());
        if (args.size() < 3) {
            return Set.of();
        }
        String expression = args.get(2).trim();
        // A bare literal is authoritative and stays unfiltered, so an unusual
        // name is still reported rather than silently discarded.
        if (expression.matches("\"[^\"]+\"")) {
            return Set.copyOf(stringLiterals(expression));
        }
        // Any other expression is resolved by inference, so keep only literals
        // shaped like an event type: dotted lowercase or SCREAMING_SNAKE. That
        // drops the field names and comparison operands that share a ternary or
        // payload map with the real name (e.g. "enabled".equals(field) ? "a.b" : "c.d").
        Set<String> literals = new LinkedHashSet<>(stringLiterals(expression));
        if (!literals.isEmpty()) {
            literals.removeIf(name -> !isEventTypeName(name));
            return literals;
        }
        Map<String, Set<String>> constants = new LinkedHashMap<>();
        Matcher constant = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"\\s*;").matcher(source);
        while (constant.find()) {
            constants.computeIfAbsent(constant.group(1), key -> new LinkedHashSet<>()).add(constant.group(2));
        }
        for (String identifier : identifiers(expression)) {
            literals.addAll(constants.getOrDefault(identifier, Set.of()));
        }
        if (!literals.isEmpty()) {
            literals.removeIf(name -> !isEventTypeName(name));
            return literals;
        }
        String passed = firstIdentifier(expression);
        if (passed == null) {
            return Set.of();
        }
        String enclosing = enclosingMethod(source, matcher.start());
        if (enclosing == null) {
            return Set.of();
        }
        Matcher signature = Pattern.compile(
                "(?:private|public|protected|static|final|\\s)+[\\w<>,\\[\\]]+\\s+" + Pattern.quote(enclosing) + "\\s*\\((.*?)\\)",
                Pattern.DOTALL).matcher(source);
        if (!signature.find()) {
            return Set.of();
        }
        List<String> parameters = new ArrayList<>();
        for (String parameter : signature.group(1).split(",")) {
            String[] tokens = parameter.trim().split("\\s+");
            if (tokens.length >= 2) {
                parameters.add(tokens[tokens.length - 1].replace("...", "").trim());
            }
        }
        int position = parameters.indexOf(passed);
        if (position < 0) {
            return Set.of();
        }
        Matcher call = Pattern.compile("\\b" + Pattern.quote(enclosing) + "\\s*\\(").matcher(source);
        while (call.find()) {
            List<String> callArgs = splitArguments(source, call.end());
            if (callArgs.size() > position) {
                literals.addAll(stringLiterals(callArgs.get(position)));
            }
        }
        literals.removeIf(name -> !isEventTypeName(name));
        return literals;
    }

    /** Dotted lowercase or SCREAMING_SNAKE — the only shapes a producer publishes. */
    private static boolean isEventTypeName(String value) {
        return value.matches("[a-z][a-z0-9_]*\\.[a-z0-9_]+") || value.matches("[A-Z][A-Z0-9_]*");
    }

    /** Splits a call's argument list, ignoring commas nested in calls or literals. */
    private static List<String> splitArguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 1;
        char quote = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    current.append(c).append(source.charAt(i + 1));
                    i++;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    args.add(current.toString());
                    return args;
                }
                current.append(c);
            } else if (c == ',' && depth == 1) {
                args.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return args;
    }

    /** The declaration whose body contains {@code offset}, by brace depth. */
    private static String enclosingMethod(String source, int offset) {
        String head = source.substring(0, offset);
        List<String> lines = new ArrayList<>(List.of(head.split("\n", -1)));
        for (int index = lines.size() - 1; index >= 0 && index > lines.size() - 200; index--) {
            Matcher declaration = Pattern.compile(
                    "\\s{4}(?:private|public|protected|static|final|\\s)*[\\w<>,\\[\\]\\s]+\\s(\\w+)\\s*\\(")
                    .matcher(lines.get(index));
            if (declaration.find()) {
                return declaration.group(1);
            }
        }
        return null;
    }

    private static Set<String> stringLiterals(String expression) {
        Set<String> literals = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(expression);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    private static List<String> identifiers(String expression) {
        List<String> identifiers = new ArrayList<>();
        Matcher matcher = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(expression);
        while (matcher.find()) {
            identifiers.add(matcher.group());
        }
        return identifiers;
    }

    private static String firstIdentifier(String expression) {
        List<String> identifiers = identifiers(expression);
        return identifiers.isEmpty() ? null : identifiers.get(0);
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
