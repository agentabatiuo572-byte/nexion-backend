package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authoritative support SLA category baseline.
 *
 * <p>Startup and clean-schema provisioning may add missing rows from this
 * baseline, but must never overwrite a value already configured by operations.</p>
 */
public final class SupportSlaBaseline {
    public static final List<Rule> RULES = List.of(
            new Rule("account", 30, 24, "账户台", "C5 security"),
            new Rule("withdrawal", 15, 12, "支付台", "D2 withdrawal review"),
            new Rule("deposit", 15, 12, "支付台", "D1 deposit reconciliation"),
            new Rule("hardware", 45, 48, "设备运维台", "E5 device ops"),
            new Rule("earnings", 30, 24, "收益台", "F3/E6 earnings ledger"),
            new Rule("genesis", 20, 18, "创世节点台", "G4 Genesis economy"),
            new Rule("technical", 60, 72, "技术支持台", "A3 system config"),
            new Rule("other", 60, 72, "综合支持台", "M2 manual triage"));

    public static final Set<String> CATEGORIES = RULES.stream()
            .map(Rule::category)
            .collect(Collectors.toUnmodifiableSet());

    private SupportSlaBaseline() {
    }

    public record Rule(
            String category,
            int firstResponseMins,
            int resolutionHours,
            String queue,
            String escalation) {
    }
}
