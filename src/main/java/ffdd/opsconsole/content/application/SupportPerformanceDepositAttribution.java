package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.AttributionState;
import ffdd.opsconsole.content.domain.SupportPerformance.EventAttribution;
import ffdd.opsconsole.content.domain.SupportPerformance.ObservedDeposit;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionProof;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ConfirmedDepositCorrection;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads original deposit ownership from the same immutable source snapshot as the performance amounts. */
final class SupportPerformanceDepositAttribution {
    private static final EventAttribution UNKNOWN = new EventAttribution(AttributionState.UNKNOWN, null, null);

    private SupportPerformanceDepositAttribution() { }

    record Result(Map<String, EventAttribution> events, Map<String, Long> verifiedOriginals) { }

    static Result resolve(SupportAnalyticsFactSnapshot source, List<ObservedDeposit> deposits,
            List<ConfirmedDepositCorrection> corrections) {
        var originalIds = new HashSet<String>();
        deposits.forEach(fact -> originalIds.add(fact.factId()));
        corrections.forEach(fact -> originalIds.add(fact.originalFactId()));
        if (originalIds.isEmpty()) return new Result(Map.of(), Map.of());

        var payments = source.payments();
        var originals = payments.facts().stream()
                .filter(fact -> originalIds.contains(fact.factId()) && fact.kind() == Kind.DEPOSIT
                        && SupportPerformance.CURRENCY.equals(fact.currency()))
                .collect(Collectors.groupingBy(Fact::factId));
        var rows = source.attributions().stream().filter(row -> originalIds.contains(row.factId()))
                .collect(Collectors.groupingBy(AttributionRow::factId));
        var proofs = source.proofs().stream().filter(proof -> originalIds.contains(proof.factId()))
                .collect(Collectors.groupingBy(AttributionProof::factId));
        var verified = new HashMap<String,OriginalAttribution>();
        var verifiedOwners = new HashMap<String, Long>();
        for (String id : originalIds) {
            var candidates = originals.getOrDefault(id, List.of());
            var candidateRows = rows.getOrDefault(id, List.of());
            var candidateProofs = proofs.getOrDefault(id, List.of());
            if (candidates.size() != 1) continue;
            var fact = candidates.get(0);
            if (fact.historicalEnvironmentStatus() != ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status.READY
                    || SupportAnalyticsService.proofRejected(payments, fact)) continue;
            verifiedOwners.put(id, fact.customerId());
            if (candidateRows.size() != 1 || candidateProofs.size() != 1) continue;
            var row = candidateRows.get(0);
            if (!"NEW_SUCCESS".equals(row.captureMode()) || !SupportAnalyticsService.validLayers(row)
                    || !SupportAnalyticsService.aligned(row, fact, payments.businessZone())
                    || !SupportAnalyticsService.netProof(candidateProofs.get(0), row, fact)) continue;
            verified.put(id, new OriginalAttribution(fact.customerId(), project(row)));
        }

        var events = new HashMap<String,EventAttribution>();
        deposits.forEach(fact -> events.put(fact.factId(),
                forCustomer(verified.get(fact.factId()), fact.customerId())));
        corrections.forEach(fact -> events.put(fact.operationNo(),
                forCustomer(verified.get(fact.originalFactId()), fact.customerId())));
        return new Result(Map.copyOf(events), Map.copyOf(verifiedOwners));
    }

    private static EventAttribution forCustomer(OriginalAttribution original, long customerId) {
        return original != null && original.customerId() == customerId ? original.attribution() : UNKNOWN;
    }

    private static EventAttribution project(AttributionRow row) {
        if ("KNOWN".equals(row.agentStatus()) && "KNOWN".equals(row.groupStatus())) {
            return new EventAttribution(AttributionState.ASSIGNED, row.agentAdminId(), row.groupId());
        }
        if ("KNOWN".equals(row.agentStatus()) && "UNASSIGNED".equals(row.groupStatus())) {
            return new EventAttribution(AttributionState.AGENT_WITHOUT_GROUP, row.agentAdminId(), null);
        }
        if ("UNASSIGNED".equals(row.agentStatus()) && "UNASSIGNED".equals(row.groupStatus())) {
            return new EventAttribution(AttributionState.UNASSIGNED, null, null);
        }
        return new EventAttribution(AttributionState.UNKNOWN,
                "KNOWN".equals(row.agentStatus()) ? row.agentAdminId() : null,
                "KNOWN".equals(row.groupStatus()) ? row.groupId() : null);
    }

    private record OriginalAttribution(long customerId, EventAttribution attribution) { }
}
