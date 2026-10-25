package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.List;

public record DeviceRankConfigRequest(Long expectedVersion, Boolean enabled, List<Tier> tiers,
                                      String reason, String operator) {
    public record Tier(String name, BigDecimal sharePct, BigDecimal minTops, BigDecimal maxTops) { }
}
