package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppTradeinSubmitRequest(
        Long sourceDeviceId,
        Long targetProductId,
        String targetProductNo,
        BigDecimal expectedPayableUsdt,
        BigDecimal expectedDiscountUsdt,
        Long expectedSourceVersion,
        String paymentPassword,Long credentialVersion,String attemptNo) {
    @com.fasterxml.jackson.annotation.JsonProperty("paymentMethod")
    public void requireWalletMethod(String method){ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.requireWalletMethod(method);}
    public AppTradeinSubmitRequest(Long sourceDeviceId,Long targetProductId,String targetProductNo,
            BigDecimal expectedPayableUsdt,BigDecimal expectedDiscountUsdt,Long expectedSourceVersion) {
        this(sourceDeviceId,targetProductId,targetProductNo,expectedPayableUsdt,expectedDiscountUsdt,expectedSourceVersion,null,null,null);
    }
    @Override public String toString() {
        return "AppTradeinSubmitRequest[sourceDeviceId="+sourceDeviceId+", targetProductId="+targetProductId
                +", targetProductNo="+targetProductNo+", expectedPayableUsdt="+expectedPayableUsdt
                +", expectedDiscountUsdt="+expectedDiscountUsdt+", expectedSourceVersion="+expectedSourceVersion+"]";
    }
    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo,
                                   BigDecimal expectedPayableUsdt, BigDecimal expectedDiscountUsdt) {
        this(sourceDeviceId, targetProductId, targetProductNo, expectedPayableUsdt, expectedDiscountUsdt, 0L);
    }
    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId) {
        this(sourceDeviceId, targetProductId, null, null, null);
    }

    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo) {
        this(sourceDeviceId, targetProductId, targetProductNo, null, null);
    }
}
