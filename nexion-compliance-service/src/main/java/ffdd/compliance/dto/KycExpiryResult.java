package ffdd.compliance.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class KycExpiryResult {
    private int scanned;
    private int expired;
    private int skipped;
    private List<Long> userIds = new ArrayList<>();

    public void incrementExpired(Long userId) {
        expired++;
        if (userId != null) {
            userIds.add(userId);
        }
    }

    public void incrementSkipped() {
        skipped++;
    }
}
