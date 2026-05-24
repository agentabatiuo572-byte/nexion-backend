package ffdd.openapi.service;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WebhookDeliveryPublishResponse {
    private int scanned;
    private int succeeded;
    private int failed;
    private int dead;
}
