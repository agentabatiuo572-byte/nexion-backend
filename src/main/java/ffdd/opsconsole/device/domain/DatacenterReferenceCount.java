package ffdd.opsconsole.device.domain;

/**
 * 删除数据中心前的跨域引用快照:设备数 / 待履约订单数 / SKU 引用数。
 * 任一计数大于零即拒绝软删,作为数据中心删除的硬保护。
 */
public record DatacenterReferenceCount(long devices, long pendingOrders, long skus) {
    public boolean hasAny() {
        return devices > 0 || pendingOrders > 0 || skus > 0;
    }
}
