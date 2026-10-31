package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class TestCloudResourceEntryOrderTest {
    enum Entry { CANONICAL, E5, RESTORE, ORDER, TRADEIN, REPLACE, KEEP, PROMOTION, GIFT, H4, RAFFLE, ENGAGEMENT }
    @ParameterizedTest @EnumSource(Entry.class)
    void theGlobalGuardPrecedesAnyOwnerDeviceOrWalletAccess(Entry entry) {
        var admission=mock(TestCloudActivationAdmission.class);
        doThrow(new BizException(503,"TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE")).when(admission).lock();
        Object bean;
        switch(entry) {
            case CANONICAL -> bean=mock(ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService.class,CALLS_REAL_METHODS);
            case E5,RESTORE -> bean=mock(OpsDeviceService.class,CALLS_REAL_METHODS);
            case ORDER -> bean=mock(ffdd.opsconsole.commerce.application.AppOrderCommandService.class,CALLS_REAL_METHODS);
            case TRADEIN,REPLACE,KEEP -> bean=mock(AppTradeinService.class,CALLS_REAL_METHODS);
            case PROMOTION -> bean=mock(ffdd.opsconsole.promotion.application.PromotionRewardService.class,CALLS_REAL_METHODS);
            case GIFT -> bean=mock(ffdd.opsconsole.growth.application.EventDeviceGiftService.class,CALLS_REAL_METHODS);
            case H4 -> bean=mock(ffdd.opsconsole.growth.application.H4EventEngine.class,CALLS_REAL_METHODS);
            case RAFFLE -> bean=mock(ffdd.opsconsole.growth.application.GenesisRaffleService.class,CALLS_REAL_METHODS);
            case ENGAGEMENT -> bean=mock(ffdd.opsconsole.growth.application.AppGrowthEngagementService.class,CALLS_REAL_METHODS);
            default -> throw new IllegalStateException();
        }
        ReflectionTestUtils.setField(bean,"testCloudAdmission",admission);
        assertThatThrownBy(()->{
            switch(entry) {
                case CANONICAL -> ((ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryService)bean).activateDevice(7L,11L,0L,6,"resource-key");
                case RESTORE -> ((OpsDeviceService)bean).restoreDevice(11L,"resource-key",null);
                case E5 -> ((OpsDeviceService)bean).activateE5Device(11L,false,"resource-key",null);
                case ORDER -> ReflectionTestUtils.invokeMethod(bean,"payFromWallet",7L,"ORDER");
                case TRADEIN -> ReflectionTestUtils.invokeMethod(bean,"submitAuthorized",7L,"resource-key",null);
                case REPLACE -> ReflectionTestUtils.invokeMethod(bean,"capacityReplaceAuthorized",7L,"resource-key",null);
                case KEEP -> ReflectionTestUtils.invokeMethod(bean,"capacityKeepAuthorized",7L,"resource-key",null);
                case PROMOTION -> ((ffdd.opsconsole.promotion.application.PromotionRewardService)bean).issue("reward","command");
                case GIFT -> ((ffdd.opsconsole.growth.application.EventDeviceGiftService)bean).award(7L,"source","product",2);
                case H4 -> ReflectionTestUtils.invokeMethod(bean,"award",7L,"event");
                case RAFFLE -> ((ffdd.opsconsole.growth.application.GenesisRaffleService)bean).pay("draw","prize",0);
                case ENGAGEMENT -> ((ffdd.opsconsole.growth.application.AppGrowthEngagementService)bean).claimEvent(7L,"event","resource-key");
            }
        }).hasMessage("TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE");
        verify(admission).lock();
    }
}
