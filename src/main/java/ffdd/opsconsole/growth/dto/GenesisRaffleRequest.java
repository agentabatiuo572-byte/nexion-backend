package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record GenesisRaffleRequest(String id, Long expectedRevision, Boolean enabled,
        String titleZh, String titleEn, String titleVi, String startsAt, String drawAt,
        List<Prize> prizes, String reason, Map<String, String> titleTranslations) {
    public GenesisRaffleRequest(String id, Long expectedRevision, Boolean enabled, String titleZh, String titleEn,
            String titleVi, String startsAt, String drawAt, List<Prize> prizes, String reason) {
        this(id, expectedRevision, enabled, titleZh, titleEn, titleVi, startsAt, drawAt, prizes, reason, null);
    }
    public record Prize(String id, String nameZh, String nameEn, String nameVi,
            String rewardType, BigDecimal amount, int quantity, String productNo, Map<String, String> nameTranslations) {
        public Prize(String id, String nameZh, String nameEn, String nameVi, String rewardType, BigDecimal amount,
                int quantity, String productNo) {
            this(id, nameZh, nameEn, nameVi, rewardType, amount, quantity, productNo, null);
        }
    }
}
