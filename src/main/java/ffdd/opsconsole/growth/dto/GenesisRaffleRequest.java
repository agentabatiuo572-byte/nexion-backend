package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.List;

public record GenesisRaffleRequest(String id, Long expectedRevision, Boolean enabled,
        String titleZh, String titleEn, String titleVi, String startsAt, String drawAt,
        List<Prize> prizes, String reason) {
    public record Prize(String id, String nameZh, String nameEn, String nameVi,
            String rewardType, BigDecimal amount, int quantity, String productNo) { }
}
