package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.shared.exception.BizException;

public final class HdPayCreateRejectedException extends BizException {
    private final String providerReason;

    HdPayCreateRejectedException(String providerReason) {
        super(422, "HDPAY_ORDER_CREATE_REJECTED");
        this.providerReason = providerReason;
    }

    public String providerReason() {
        return providerReason;
    }
}
