package ffdd.opsconsole.finance.purchase;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;

/**
 * B58-BE-01 / BUG-553: 同一本人、同一业务身份已有在途外部付款单时，按既有 409
 * PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED 拒绝，并把**已核对**的原付款单号放进回包 data，
 * 让客户端能凭它读回原单。写入路径不变，也不新建任何单据。
 */
public final class ExistingPurchasePaymentIntentException extends BizException {
    private final String intentNo;

    public ExistingPurchasePaymentIntentException(String intentNo) {
        super(409, "PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED");
        this.intentNo = intentNo;
    }

    /** 只回本人同一业务身份的在途单号，不含任何他人或其它业务的信息。 */
    public Map<String, Object> errorData() {
        return Map.of("intentNo", intentNo);
    }
}
