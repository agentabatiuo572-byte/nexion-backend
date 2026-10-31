package ffdd.opsconsole.finance.hdpay;

import java.math.BigDecimal;

public interface HdPayGateway {
    record CreatePayOrder(String merchantOrderId, BigDecimal transAmt, String clientIp) {}
    record PayPage(String url) {}
    record PayOrder(
            String merchantOrderId,
            String providerOrderId,
            int orderStatus,
            BigDecimal transAmt,
            String payType,
            String appLink,
            java.time.Instant expirationTime,
            java.time.Instant paidAt) {
        public PayOrder(String merchantOrderId,String providerOrderId,int orderStatus,BigDecimal transAmt,String payType,String appLink){
            this(merchantOrderId,providerOrderId,orderStatus,transAmt,payType,appLink,null,null);
        }
    }

    PayPage createPayOrder(CreatePayOrder order);

    PayOrder queryPayOrder(String merchantOrderId);
}
