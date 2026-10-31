package ffdd.opsconsole.auth.support;

import ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.function.Supplier;
import org.springframework.test.util.ReflectionTestUtils;

/** Existing business tests start AFTER the authorization gate; real PIN tests use the real service/database. */
public final class AuthorizedPurchaseFixture {
    private AuthorizedPurchaseFixture() {}
    public static <T> T businessOnly(T service) {
        ReflectionTestUtils.setField(service,"paymentAuthorization",new PaymentAuthorizationFacade() {
            @Override public <R> ApiResult<R> execute(Long user,String kind,String key,Object summary,
                    Authorization authorization,Supplier<ApiResult<R>> action) { return action.get(); }
        });
        return service;
    }
}
