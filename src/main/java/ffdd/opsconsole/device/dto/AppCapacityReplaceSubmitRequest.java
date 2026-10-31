package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppCapacityReplaceSubmitRequest(
        Long sourceDeviceId,
        String targetProductNo,
        BigDecimal expectedPayableUsdt,
        String paymentPassword,Long credentialVersion,String attemptNo) {
    @com.fasterxml.jackson.annotation.JsonProperty("paymentMethod")
    public void requireWalletMethod(String method){ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.requireWalletMethod(method);}
    public AppCapacityReplaceSubmitRequest(Long sourceDeviceId,String targetProductNo,BigDecimal expectedPayableUsdt) {
        this(sourceDeviceId,targetProductNo,expectedPayableUsdt,null,null,null);
    }
    @Override public String toString() {
        return "AppCapacityReplaceSubmitRequest[sourceDeviceId="+sourceDeviceId+", targetProductNo="+targetProductNo
                +", expectedPayableUsdt="+expectedPayableUsdt+"]";
    }
}
