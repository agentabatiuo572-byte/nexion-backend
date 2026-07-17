package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountClusterProjection;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountEdge;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountNode;
import ffdd.opsconsole.risk.domain.RiskOpsRepository.MultiAccountSignalFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Deterministic K1 graph builder: shared keys become edges and connected components become clusters. */
@Component
public class MultiAccountClusterEngine {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> LAYERS = Set.of("ip", "device", "payment");

    public List<MultiAccountClusterProjection> project(
            List<MultiAccountSignalFact> source,
            Set<String> activeIpWhitelist,
            Config config) {
        List<MultiAccountSignalFact> facts = source == null ? List.of() : source.stream()
                .filter(this::validFact)
                .filter(fact -> !"ip".equals(fact.layer()) || !matchesWhitelist(fact.rawKey(), activeIpWhitelist))
                .toList();
        Map<Long, MultiAccountSignalFact> users = new TreeMap<>();
        facts.forEach(fact -> users.merge(fact.userId(), fact, this::earlierFact));
        UnionFind union = new UnionFind(users.keySet());
        Map<GroupKey, List<MultiAccountSignalFact>> groups = groupedFacts(facts);
        groups.entrySet().stream()
                .filter(entry -> eligible(entry.getKey().layer(), distinctUsers(entry.getValue()).size(), config))
                .forEach(entry -> {
            List<MultiAccountSignalFact> group = entry.getValue();
            List<Long> ids = distinctUsers(group);
            for (int i = 1; i < ids.size(); i++) union.join(ids.get(0), ids.get(i));
        });

        Map<Long, List<Long>> components = new TreeMap<>();
        users.keySet().forEach(userId -> components.computeIfAbsent(union.find(userId), ignored -> new ArrayList<>()).add(userId));
        List<MultiAccountClusterProjection> result = new ArrayList<>();
        for (List<Long> memberIds : components.values()) {
            if (memberIds.size() < 2) continue;
            memberIds.sort(Comparator.comparing((Long id) -> users.get(id).joinedAt()).thenComparingLong(Long::longValue));
            Set<Long> members = new LinkedHashSet<>(memberIds);
            List<GroupScore> scores = groupScores(groups, members, config);
            if (scores.isEmpty()) continue;
            List<MultiAccountEdge> edges = realEdges(scores);
            double strength = pairStrength(scores, memberIds);
            GroupScore primary = scores.stream()
                    .max(Comparator.comparingDouble(GroupScore::weightedScore)
                            .thenComparing(score -> score.key().layer())
                            .thenComparing(score -> score.key().rawKey())
                            .thenComparing(GroupScore::maskedKey))
                    .orElseThrow();
            List<MultiAccountNode> nodes = memberIds.stream().map(id -> node(users.get(id))).toList();
            LocalDateTime first = users.get(memberIds.get(0)).joinedAt();
            LocalDateTime last = memberIds.stream().map(id -> users.get(id).joinedAt()).max(LocalDateTime::compareTo).orElse(first);
            String clusterId = "K1-" + String.format(Locale.ROOT, "%08d", memberIds.get(0));
            result.add(new MultiAccountClusterProjection(
                    clusterId,
                    primary.maskedKey(),
                    primary.key().layer(),
                    layerLabel(primary.key().layer()),
                    memberIds.size(),
                    round(strength),
                    span(first, last),
                    note(scores),
                    evidenceFingerprint(scores),
                    List.copyOf(memberIds),
                    nodes,
                    edges,
                    giftDuplicates(clusterId, nodes),
                    false));
        }
        return result.stream()
                .sorted(Comparator.comparingDouble(MultiAccountClusterProjection::strength).reversed()
                        .thenComparing(MultiAccountClusterProjection::clusterId))
                .toList();
    }

    private boolean validFact(MultiAccountSignalFact fact) {
        return fact != null && fact.userId() > 0 && fact.joinedAt() != null
                && LAYERS.contains(fact.layer()) && StringUtils.hasText(fact.rawKey());
    }

    private MultiAccountSignalFact earlierFact(MultiAccountSignalFact left, MultiAccountSignalFact right) {
        return left.joinedAt().isAfter(right.joinedAt()) ? right : left;
    }

    private Map<GroupKey, List<MultiAccountSignalFact>> groupedFacts(List<MultiAccountSignalFact> facts) {
        Map<GroupKey, List<MultiAccountSignalFact>> groups = new LinkedHashMap<>();
        for (MultiAccountSignalFact fact : facts) {
            GroupKey key = new GroupKey(fact.layer(), fact.rawKey().trim().toLowerCase(Locale.ROOT));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }
        return groups;
    }

    private List<Long> distinctUsers(List<MultiAccountSignalFact> group) {
        return group.stream().map(MultiAccountSignalFact::userId).distinct().sorted().toList();
    }

    private List<GroupScore> groupScores(
            Map<GroupKey, List<MultiAccountSignalFact>> groups, Set<Long> members, Config config) {
        List<GroupScore> scores = new ArrayList<>();
        groups.forEach((key, group) -> {
            List<Long> ids = distinctUsers(group).stream().filter(members::contains).toList();
            if (!eligible(key.layer(), ids.size(), config)) return;
            double layerScore = layerScore(key.layer(), ids.size());
            String masked = group.stream().map(MultiAccountSignalFact::maskedKey).filter(StringUtils::hasText).findFirst().orElse("已脱敏");
            scores.add(new GroupScore(key, ids, masked, layerScore, layerScore * config.weight(key.layer())));
        });
        scores.sort(Comparator.comparing((GroupScore score) -> score.key().layer())
                .thenComparing(score -> score.key().rawKey())
                .thenComparing(GroupScore::maskedKey));
        return scores;
    }

    private List<MultiAccountEdge> realEdges(List<GroupScore> scores) {
        Map<String, MultiAccountEdge> edges = new LinkedHashMap<>();
        for (GroupScore score : scores) {
            // A shared evidence group is a clique mathematically, but serializing every pair is O(n²).
            // A deterministic spanning chain keeps every emitted edge real while preserving connectivity.
            for (int right = 1; right < score.userIds().size(); right++) {
                String from = userNo(score.userIds().get(right - 1));
                String to = userNo(score.userIds().get(right));
                String key = from + "\u0000" + to + "\u0000" + score.key().layer();
                MultiAccountEdge edge = new MultiAccountEdge(from, to, score.key().layer(), round(score.weightedScore()));
                edges.merge(key, edge, (left, candidate) -> left.weight() >= candidate.weight() ? left : candidate);
            }
        }
        return edges.values().stream()
                .sorted(Comparator.comparing(MultiAccountEdge::from)
                        .thenComparing(MultiAccountEdge::to)
                        .thenComparing(MultiAccountEdge::layer)
                        .thenComparingDouble(MultiAccountEdge::weight))
                .toList();
    }

    /**
     * Returns the strongest real account pair without expanding every member pair.
     *
     * <p>K1 has exactly three independent evidence layers. For a concrete pair, each layer contributes
     * the highest scoring evidence group that contains both users. Therefore the exact maximum can be
     * found by intersecting at most one group from each layer: an intersection with cardinality >= 2
     * proves that a real pair shares every selected group. This preserves the former pairwise semantics
     * while avoiding O(n²) work for a large shared device/IP/payment group.</p>
     */
    private double pairStrength(List<GroupScore> scores, List<Long> memberIds) {
        Map<Long, Integer> memberIndex = new HashMap<>();
        for (int i = 0; i < memberIds.size(); i++) memberIndex.put(memberIds.get(i), i);

        Map<String, List<IndexedGroupScore>> byLayer = new HashMap<>();
        double maximum = 0;
        for (GroupScore score : scores) {
            BitSet members = new BitSet(memberIds.size());
            for (Long userId : score.userIds()) {
                Integer index = memberIndex.get(userId);
                if (index != null) members.set(index);
            }
            if (members.cardinality() < 2) continue;
            IndexedGroupScore indexed = new IndexedGroupScore(members, score.weightedScore());
            byLayer.computeIfAbsent(score.key().layer(), ignored -> new ArrayList<>()).add(indexed);
            maximum = Math.max(maximum, indexed.weightedScore());
        }

        List<IndexedGroupScore> devices = byLayer.getOrDefault("device", List.of());
        List<IndexedGroupScore> payments = byLayer.getOrDefault("payment", List.of());
        List<IndexedGroupScore> ips = byLayer.getOrDefault("ip", List.of());
        maximum = strongestTwoLayers(devices, payments, maximum);
        maximum = strongestTwoLayers(devices, ips, maximum);
        maximum = strongestTwoLayers(payments, ips, maximum);

        double maxIpScore = ips.stream().mapToDouble(IndexedGroupScore::weightedScore).max().orElse(0);
        for (IndexedGroupScore device : devices) {
            for (IndexedGroupScore payment : payments) {
                double firstTwo = device.weightedScore() + payment.weightedScore();
                if (firstTwo + maxIpScore <= maximum) continue;
                BitSet shared = intersection(device.members(), payment.members());
                if (shared.cardinality() < 2) continue;
                for (IndexedGroupScore ip : ips) {
                    double candidate = firstTwo + ip.weightedScore();
                    if (candidate <= maximum) continue;
                    BitSet allThree = intersection(shared, ip.members());
                    if (allThree.cardinality() >= 2) maximum = candidate;
                }
            }
        }
        return maximum;
    }

    private double strongestTwoLayers(
            List<IndexedGroupScore> leftGroups, List<IndexedGroupScore> rightGroups, double currentMaximum) {
        double maximum = currentMaximum;
        for (IndexedGroupScore left : leftGroups) {
            for (IndexedGroupScore right : rightGroups) {
                double candidate = left.weightedScore() + right.weightedScore();
                if (candidate <= maximum) continue;
                if (intersection(left.members(), right.members()).cardinality() >= 2) maximum = candidate;
            }
        }
        return maximum;
    }

    private BitSet intersection(BitSet left, BitSet right) {
        BitSet shared = (BitSet) left.clone();
        shared.and(right);
        return shared;
    }

    private MultiAccountNode node(MultiAccountSignalFact fact) {
        return new MultiAccountNode(
                StringUtils.hasText(fact.userNo()) ? fact.userNo() : userNo(fact.userId()),
                fact.joinedAt(),
                fact.sponsorUserId() == null ? "—" : userNo(fact.sponsorUserId()),
                fact.gotWelcomeGift(),
                fact.depositCumulativeUsdt(),
                StringUtils.hasText(fact.accountStatus()) ? fact.accountStatus() : "active");
    }

    private List<List<String>> giftDuplicates(String clusterId, List<MultiAccountNode> nodes) {
        List<MultiAccountNode> received = nodes.stream().filter(node -> Boolean.TRUE.equals(node.gotWelcomeGift())).toList();
        if (received.size() < 2) return List.of();
        return List.of(List.of(clusterId, received.size() + " 个关联账户已领取新人礼", "待 K2 复核"));
    }

    private String note(List<GroupScore> scores) {
        return scores.stream().map(score -> layerLabel(score.key().layer()))
                .distinct().sorted().collect(java.util.stream.Collectors.joining(" + ")) + " 形成真实关联边";
    }

    /** Hashes only canonical evidence identity and membership; weights and display fields are excluded. */
    private String evidenceFingerprint(List<GroupScore> scores) {
        String canonical = scores.stream()
                .map(score -> score.key().layer() + "\u0000" + score.key().rawKey() + "\u0000"
                        + score.userIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String span(LocalDateTime first, LocalDateTime last) {
        long minutes = Math.max(0, Duration.between(first, last).toMinutes());
        return first.format(DISPLAY_TIME) + " 至 " + last.format(DISPLAY_TIME) + "（" + minutes + " 分钟）";
    }

    private double layerScore(String layer, int count) {
        return switch (layer) {
            case "device" -> count >= 3 ? 1.0 : 0.6;
            case "payment" -> count >= 3 ? 1.0 : 0.7;
            case "ip" -> count >= 4 ? 0.6 : 0.3;
            default -> 0;
        };
    }

    private boolean eligible(String layer, int count, Config config) {
        int maximum = switch (layer) {
            case "ip" -> config.maxSignupPerIp24h();
            case "device" -> config.maxAccountsPerDevice();
            case "payment" -> config.maxAccountsPerPaymentInstrument();
            default -> Integer.MAX_VALUE;
        };
        return count > maximum;
    }

    private boolean matchesWhitelist(String value, Set<String> cidrs) {
        if (!StringUtils.hasText(value) || cidrs == null || cidrs.isEmpty()) return false;
        return cidrs.stream().anyMatch(cidr -> ipv4InCidr(value.trim(), cidr));
    }

    static boolean ipv4InCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/", -1);
            if (parts.length != 2) return false;
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) return false;
            byte[] address = InetAddress.getByName(ip).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (address.length != 4 || network.length != 4) return false;
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            return (toInt(address) & mask) == (toInt(network) & mask);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int toInt(byte[] bytes) {
        return ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
    }

    private String userNo(long userId) {
        return "U" + String.format(Locale.ROOT, "%08d", userId);
    }

    private String layerLabel(String layer) {
        return switch (layer) {
            case "device" -> "设备指纹";
            case "payment" -> "支付工具";
            default -> "IP";
        };
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record Config(
            double deviceWeight, double paymentWeight, double ipWeight, double freezeThreshold,
            int maxSignupPerIp24h, int maxAccountsPerDevice, int maxAccountsPerPaymentInstrument) {
        public Config(double deviceWeight, double paymentWeight, double ipWeight, double freezeThreshold) {
            this(deviceWeight, paymentWeight, ipWeight, freezeThreshold, 3, 2, 2);
        }

        public static Config defaults() {
            return new Config(0.5, 0.4, 0.1, 0.7, 3, 2, 2);
        }

        double weight(String layer) {
            return switch (layer) {
                case "device" -> deviceWeight;
                case "payment" -> paymentWeight;
                case "ip" -> ipWeight;
                default -> 0;
            };
        }
    }

    private record GroupKey(String layer, String rawKey) {
    }

    private record GroupScore(GroupKey key, List<Long> userIds, String maskedKey, double layerScore, double weightedScore) {
    }

    private record IndexedGroupScore(BitSet members, double weightedScore) {
    }

    private static final class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();

        UnionFind(Set<Long> users) {
            users.forEach(id -> parent.put(id, id));
        }

        long find(long id) {
            long root = parent.getOrDefault(id, id);
            if (root != id) {
                root = find(root);
                parent.put(id, root);
            }
            return root;
        }

        void join(long left, long right) {
            long a = find(left), b = find(right);
            if (a == b) return;
            parent.put(Math.max(a, b), Math.min(a, b));
        }
    }
}
