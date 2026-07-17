package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountClusterProjection;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountClusterState;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds the K1 graph from authoritative server facts and persists its projections atomically. */
@Service
@RequiredArgsConstructor
public class MultiAccountClusterBatchService {
    static final String EVENT_TYPE = "RISK_MULTI_ACCOUNT_FLAGGED";
    static final String INCIDENT_EVENT_TYPE = "RISK_MULTI_ACCOUNT_INCIDENT_CREATED";

    private final RiskOpsRepository repository;
    private final MultiAccountClusterEngine engine;
    private final EventOutboxService outboxService;
    private final AuditLogService auditLogService;

    @Transactional
    public int rebuild() {
        MultiAccountClusterEngine.Config config = config(repository.multiAccountConfigValues());
        List<RiskOpsRepository.MultiAccountSignalFact> facts = repository.multiAccountSignalFacts();
        Set<String> whitelist = repository.activeIpWhitelistCidrs();
        Map<String, Optional<MultiAccountClusterState>> stateCache = new HashMap<>();
        List<MultiAccountClusterProjection> fullProjections = engine.project(facts, Set.of(), config);
        Map<String, String> fullFingerprints = fullProjections.stream().collect(Collectors.toMap(
                MultiAccountClusterProjection::clusterId,
                MultiAccountClusterProjection::evidenceFingerprint));
        List<RoutedProjection> withoutWhitelist = route(fullProjections, stateCache, Map.of());
        List<RoutedProjection> routed = route(
                engine.project(facts, whitelist, config), stateCache, fullFingerprints).stream()
                .map(item -> new RoutedProjection(
                        item.projection().withThresholdHit(item.projection().strength() >= config.freezeThreshold()),
                        item.currentState(), item.terminalRoot()))
                .toList();
        List<MultiAccountClusterProjection> projections = routed.stream()
                .map(RoutedProjection::projection).toList();
        Set<String> activeIds = projections.stream()
                .map(MultiAccountClusterProjection::clusterId).collect(Collectors.toSet());
        Set<String> clearedByWhitelist = withoutWhitelist.stream()
                .map(item -> item.projection().clusterId())
                .filter(clusterId -> !activeIds.contains(clusterId))
                .collect(Collectors.toSet());
        Map<String, Optional<MultiAccountClusterState>> before = routed.stream().collect(Collectors.toMap(
                item -> item.projection().clusterId(),
                RoutedProjection::currentState,
                (left, ignored) -> left,
                LinkedHashMap::new));
        Map<String, Optional<MultiAccountClusterState>> clearBefore = clearedByWhitelist.stream().collect(Collectors.toMap(
                clusterId -> clusterId,
                clusterId -> stateCache.computeIfAbsent(clusterId, repository::multiAccountClusterState),
                (left, ignored) -> left,
                LinkedHashMap::new));
        repository.upsertMultiAccountProjections(projections);
        repository.clearWhitelistedDetectedClusters(clearedByWhitelist);
        auditWhitelistClears(clearedByWhitelist, clearBefore);
        repository.retireMissingDetectedClusters(activeIds);
        for (RoutedProjection item : routed) {
            MultiAccountClusterProjection projection = item.projection();
            Optional<MultiAccountClusterState> old = before.getOrDefault(projection.clusterId(), Optional.empty());
            Optional<MultiAccountClusterState> current = repository.multiAccountClusterState(projection.clusterId());
            if (old.isEmpty() && item.terminalRoot().isPresent()
                    && current.filter(state -> "detected".equals(state.status())).isPresent()) {
                MultiAccountClusterState root = item.terminalRoot().orElseThrow();
                auditLogService.recordRequired(AuditLogWriteRequest.builder()
                        .action("K1_CLUSTER_INCIDENT_CREATED")
                        .resourceType("RISK_MULTI_ACCOUNT_CLUSTER")
                        .resourceId(projection.clusterId())
                        .bizNo(projection.clusterId())
                        .actorType("SYSTEM")
                        .actorUsername("K1_CLUSTER_SCHEDULER")
                        .result("SUCCESS")
                        .riskLevel("HIGH")
                        .detail(Map.of(
                                "lineageRootClusterId", root.clusterId(),
                                "previousTerminalStatus", root.status(),
                                "evidenceFingerprint", projection.evidenceFingerprint(),
                                "affectedUserIds", projection.affectedUserIds(),
                                "reason", "NEW_MULTI_ACCOUNT_EVIDENCE"))
                        .build());
                outboxService.publish("RISK_MULTI_ACCOUNT_CLUSTER", projection.clusterId(), INCIDENT_EVENT_TYPE, Map.of(
                        "clusterId", projection.clusterId(),
                        "lineageRootClusterId", root.clusterId(),
                        "previousTerminalStatus", root.status(),
                        "strength", projection.strength(),
                        "affectedUserIds", projection.affectedUserIds(),
                        "isServerAuthoritative", true));
            }
            if (projection.thresholdHit()
                    && current.filter(state -> "detected".equals(state.status())).isPresent()
                    && (old.isEmpty() || !old.get().thresholdHit())) {
                outboxService.publish("RISK_MULTI_ACCOUNT_CLUSTER", projection.clusterId(), EVENT_TYPE, Map.of(
                        "clusterId", projection.clusterId(),
                        "strength", projection.strength(),
                        "suggestedAction", "flagged",
                        "affectedUserIds", projection.affectedUserIds(),
                        "isServerAuthoritative", true,
                        "cluster_id", projection.clusterId(),
                        "dedup_layer", projection.layer(),
                        "score", projection.strength(),
                        "linkedCount", projection.accountCount(),
                        "ts", Instant.now().toString()));
            }
        }
        return projections.size();
    }

    private List<RoutedProjection> route(
            List<MultiAccountClusterProjection> projections,
            Map<String, Optional<MultiAccountClusterState>> stateCache,
            Map<String, String> fullFingerprints) {
        List<RoutedProjection> routed = new ArrayList<>();
        for (MultiAccountClusterProjection projection : projections) {
            Optional<MultiAccountClusterState> rootState = stateCache.computeIfAbsent(
                    projection.clusterId(), repository::multiAccountClusterState);
            Optional<MultiAccountClusterState> terminal = rootState.filter(this::terminal);
            if (terminal.isPresent() && hasFingerprint(terminal.get().evidenceFingerprint())
                    && fullFingerprints.containsKey(projection.clusterId())
                    && !fullFingerprints.get(projection.clusterId()).equals(projection.evidenceFingerprint())
                    && (terminal.get().evidenceFingerprint().equals(fullFingerprints.get(projection.clusterId()))
                    || terminal.get().evidenceFingerprint().equals(projection.evidenceFingerprint()))) {
                // A whitelist may only remove evidence from an already-closed incident. That is not new risk
                // evidence and must neither reopen/overwrite the terminal root nor create a derived incident.
                continue;
            }
            if (terminal.isPresent() && hasFingerprint(terminal.get().evidenceFingerprint())
                    && !terminal.get().evidenceFingerprint().equals(projection.evidenceFingerprint())) {
                String incidentId = incidentId(projection.clusterId(), projection.evidenceFingerprint());
                Optional<MultiAccountClusterState> incidentState = stateCache.computeIfAbsent(
                        incidentId, repository::multiAccountClusterState);
                routed.add(new RoutedProjection(projection.withClusterId(incidentId), incidentState, terminal));
            } else {
                routed.add(new RoutedProjection(projection, rootState, Optional.empty()));
            }
        }
        return List.copyOf(routed);
    }

    private void auditWhitelistClears(
            Set<String> clearedByWhitelist,
            Map<String, Optional<MultiAccountClusterState>> before) {
        for (String clusterId : clearedByWhitelist) {
            Optional<MultiAccountClusterState> old = before.getOrDefault(clusterId, Optional.empty())
                    .filter(state -> "detected".equals(state.status()));
            if (old.isEmpty()) continue;
            Optional<MultiAccountClusterState> current = repository.multiAccountClusterState(clusterId)
                    .filter(state -> "cleared".equals(state.status()) && state.version() > old.orElseThrow().version());
            if (current.isEmpty()) continue;
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("K1_CLUSTER_AUTO_CLEARED_BY_WHITELIST")
                    .resourceType("RISK_MULTI_ACCOUNT_CLUSTER")
                    .resourceId(clusterId)
                    .bizNo(clusterId)
                    .actorType("SYSTEM")
                    .actorUsername("K1_CLUSTER_SCHEDULER")
                    .result("SUCCESS")
                    .riskLevel("HIGH")
                    .detail(Map.of(
                            "previousStatus", "detected",
                            "newStatus", "cleared",
                            "previousVersion", old.orElseThrow().version(),
                            "currentVersion", current.orElseThrow().version(),
                            "affectedUserIds", old.orElseThrow().affectedUserIds(),
                            "reason", "ACTIVE_IP_WHITELIST_MATCH"))
                    .build());
        }
    }

    private boolean terminal(MultiAccountClusterState state) {
        return "released".equals(state.status()) || "cleared".equals(state.status());
    }

    private boolean hasFingerprint(String value) {
        return value != null && !value.isBlank();
    }

    private String incidentId(String rootClusterId, String fingerprint) {
        String suffix = fingerprint.substring(0, Math.min(12, fingerprint.length())).toUpperCase(Locale.ROOT);
        int maxRootLength = Math.max(1, 64 - suffix.length() - 1);
        String root = rootClusterId.length() <= maxRootLength
                ? rootClusterId : rootClusterId.substring(0, maxRootLength);
        return root + "-" + suffix;
    }

    private record RoutedProjection(
            MultiAccountClusterProjection projection,
            Optional<MultiAccountClusterState> currentState,
            Optional<MultiAccountClusterState> terminalRoot) {
    }

    private MultiAccountClusterEngine.Config config(Map<String, String> values) {
        MultiAccountClusterEngine.Config defaults = MultiAccountClusterEngine.Config.defaults();
        double device = defaults.deviceWeight();
        double payment = defaults.paymentWeight();
        double ip = defaults.ipWeight();
        String weights = values == null ? null : values.get("linkWeight");
        if (weights != null) {
            device = namedWeight(weights, "设备", device);
            payment = namedWeight(weights, "支付", payment);
            ip = namedWeight(weights, "IP", ip);
        }
        if (Math.abs(device + payment + ip - 1d) > 0.001d) {
            device = defaults.deviceWeight();
            payment = defaults.paymentWeight();
            ip = defaults.ipWeight();
        }
        double threshold = parse(values == null ? null : values.get("clusterFreezeSuggestThreshold"), defaults.freezeThreshold());
        if (threshold < 0d || threshold > 1d) threshold = defaults.freezeThreshold();
        int maxIp = boundedInt(values == null ? null : values.get("maxSignupPerIp24h"), defaults.maxSignupPerIp24h(), 1, 10);
        int maxDevice = boundedInt(values == null ? null : values.get("maxAccountsPerDevice"), defaults.maxAccountsPerDevice(), 1, 5);
        int maxPayment = boundedInt(values == null ? null : values.get("maxAccountsPerPaymentInstrument"), defaults.maxAccountsPerPaymentInstrument(), 1, 5);
        return new MultiAccountClusterEngine.Config(device, payment, ip, threshold, maxIp, maxDevice, maxPayment);
    }

    private double namedWeight(String text, String label, double fallback) {
        int start = text.toUpperCase(Locale.ROOT).indexOf(label.toUpperCase(Locale.ROOT));
        if (start < 0) return fallback;
        String suffix = text.substring(start + label.length()).trim();
        String number = suffix.replaceFirst("^([^0-9.]*)", "").split("[^0-9.]", 2)[0];
        return parse(number, fallback);
    }

    private double parse(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private int boundedInt(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
