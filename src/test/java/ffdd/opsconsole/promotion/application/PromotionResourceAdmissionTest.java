package ffdd.opsconsole.promotion.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.device.application.TestCloudActivationAdmission;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromotionResourceAdmissionTest {
    @Test void adminRetryCannotLockTheOwnerBeforeTheSchedulersGlobalGuard() {
        var db=mock(PromotionMapper.class); var orders=mock(PromotionOrderService.class);
        var rewards=mock(PromotionRewardService.class); var admission=mock(TestCloudActivationAdmission.class);
        when(db.requiredRow(anyString(),any(Object[].class))).thenReturn(Map.of("order_no","ORDER","revision",0));
        doThrow(new BizException(503,"TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE")).when(admission).lock();
        when(rewards.issue("REWARD","COMMAND")).thenAnswer(call->{admission.lock();return Map.of("revision",1);});
        var admin=new PromotionAdminService(db,mock(PromotionContractValidator.class),null,null,null,null,null,rewards,orders,
                null,null,null,null,null,null,admission);
        assertThatThrownBy(()->admin.rewardAction("REWARD","retry",Map.of("expectedRevision",0),"COMMAND"))
                .hasMessage("TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE");
        verify(orders,never()).lockOrderParticipants(anyString());
        verify(db,never()).order(anyString(),anyBoolean());
    }
}
