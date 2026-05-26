package ffdd.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPushResult {
    private int scanned;
    private int sent;
    private int failed;
    private int dead;

    public void incrementScanned() {
        scanned++;
    }

    public void incrementSent() {
        sent++;
    }

    public void incrementFailed() {
        failed++;
    }

    public void incrementDead() {
        dead++;
    }
}
