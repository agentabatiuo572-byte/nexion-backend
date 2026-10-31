package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppCapacityKeepSubmitRequest(
        String targetProductNo,
        BigDecimal expectedPayableUsdt,
        String paymentPassword,Long credentialVersion,String attemptNo) {
    @com.fasterxml.jackson.annotation.JsonProperty("paymentMethod")
    public void requireWalletMethod(String method){ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.requireWalletMethod(method);}
    public AppCapacityKeepSubmitRequest(String targetProductNo,BigDecimal expectedPayableUsdt) {
        this(targetProductNo,expectedPayableUsdt,null,null,null);
    }
    @Override public String toString() {
        return "AppCapacityKeepSubmitRequest[targetProductNo="+targetProductNo+", expectedPayableUsdt="+expectedPayableUsdt+"]";
    }
}
