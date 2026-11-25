package ffdd.opsconsole.auth.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade;
import ffdd.opsconsole.device.application.HostingCenterService;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.test.util.ReflectionTestUtils;

/** Existing business tests start AFTER the authorization gate; real PIN tests use the real service/database. */
public final class AuthorizedPurchaseFixture {
    public static final String TRIAL_HOSTING_CENTER = "Acceptance Center";

    private AuthorizedPurchaseFixture() {}

    public static AppTrialLifecycleService trialBusinessOnly(AppTrialLifecycleService service) {
        return businessOnly(withTrialHostingCenter(service));
    }

    public static AppTrialLifecycleService withTrialHostingCenter(AppTrialLifecycleService service) {
        HostingCenterService centers = mock(HostingCenterService.class);
        when(centers.requirePool()).thenReturn(new HostingCenterService.Pool(
                List.of(new HostingCenterService.Center("test", TRIAL_HOSTING_CENTER, true, 1)), 1));
        ReflectionTestUtils.setField(service, "hostingCenters", centers);
        return service;
    }
    public static <T> T businessOnly(T service) {
        ReflectionTestUtils.setField(service,"paymentAuthorization",new PaymentAuthorizationFacade() {
            @Override public <R> ApiResult<R> execute(Long user,String kind,String key,Object summary,
                    Authorization authorization,Supplier<ApiResult<R>> action) { return action.get(); }
            @Override public <R> ApiResult<R> executeWithAdmission(Long user,String kind,String key,Object summary,
                    Authorization authorization,Supplier<Runnable> prepareAdmission,Supplier<ApiResult<R>> action) {
                Runnable admit = prepareAdmission.get();
                if (admit != null) admit.run();
                return action.get();
            }
        });
        return service;
    }
}
