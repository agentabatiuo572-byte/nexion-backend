package ffdd.opsconsole.shared.canonical;

import java.util.List;
import java.util.Map;

/** The paid delivery record survives later activation, deactivation, or refund. */
public record OrderDeviceDelivery(String orderNo, Long deviceId, boolean activatedOnDelivery) {
    public static void project(Map<String, Object> view, List<OrderDeviceDelivery> deliveries) {
        if (deliveries.isEmpty()) return;
        var activated = deliveries.stream().filter(OrderDeviceDelivery::activatedOnDelivery)
                .map(OrderDeviceDelivery::deviceId).toList();
        var inventory = deliveries.stream().filter(device -> !device.activatedOnDelivery())
                .map(OrderDeviceDelivery::deviceId).toList();
        view.put("activatedDeviceIds", activated);
        view.put("inventoryDeviceIds", inventory);
        view.put("activatedQuantity", activated.size());
        view.put("inventoryQuantity", inventory.size());
    }
}
