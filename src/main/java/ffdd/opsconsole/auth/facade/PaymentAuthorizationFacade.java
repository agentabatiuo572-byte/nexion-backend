package ffdd.opsconsole.auth.facade;

import ffdd.opsconsole.shared.api.ApiResult;
import java.util.function.Supplier;

/** Owns the credential/version gate and the durable purchase command transaction. */
public interface PaymentAuthorizationFacade {
    record Authorization(String paymentPassword, Long credentialVersion, String attemptNo) {
        @com.fasterxml.jackson.annotation.JsonProperty("paymentMethod")
        public void requireWalletMethod(String method){PaymentAuthorizationFacade.requireWalletMethod(method);}
        @Override public String toString() { return "Authorization[REDACTED]"; }
    }
    static void requireWalletMethod(String method){
        if(method!=null&&!"WALLET".equals(method))throw new IllegalArgumentException("PURCHASE_PAYMENT_USE_DIRECT_INTENT");
    }
    <T> ApiResult<T> execute(Long userId, String kind, String commandKey, Object businessSummary,
                             Authorization authorization, Supplier<ApiResult<T>> action);
    default <T> ApiResult<T> execute(Long userId,String kind,String commandKey,Object businessSummary,
            Authorization authorization,Supplier<ApiResult<T>> existingSuccess,Supplier<ApiResult<T>> action) {
        return execute(userId,kind,commandKey,businessSummary,authorization,action);
    }
}
