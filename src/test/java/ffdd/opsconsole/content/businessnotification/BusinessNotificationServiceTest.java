package ffdd.opsconsole.content.businessnotification;

import ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;

class BusinessNotificationServiceTest {
    @Test void replayNeverCreatesAnotherNotificationAndDoesNotCallAnyFinancialProducer() {
        var mapper=mock(BusinessNotificationMapper.class);
        var service=new BusinessNotificationService(mapper);
        var fact=new BusinessNotificationFact("EVENT:withdraw:42",7L,"FUNDS_RESULT","withdrawal_returned","/pages/me/wallet-bills");
        when(mapper.receipt(anyString(),anyString(),eq(7L))).thenReturn(1,0);
        service.emit(fact); service.emit(fact);
        verify(mapper,times(1)).notify(eq(fact),startsWith("NATIVE1:"),anyString(),anyString(),anyString());
        assertThat(BusinessNotificationService.bizNo(fact.factKey())).hasSize(72);
        assertThat(BusinessNotificationService.bizNo(fact.factKey())).isEqualTo(BusinessNotificationService.bizNo(fact.factKey()));
    }
    @Test void reusesCommittedHdpayInboxMessageWithoutChangingItsProducerIdempotencyKey() {
        var mapper=mock(BusinessNotificationMapper.class);
        var service=new BusinessNotificationService(mapper);
        var fact=new BusinessNotificationFact("event",7L,"FUNDS_RESULT","deposit","/pages/me/wallet-bills");
        when(mapper.existingTopup("event",7L)).thenReturn("HDPAY:order");
        when(mapper.receipt("event","HDPAY:order",7L)).thenReturn(1);
        service.emit(fact);
        verify(mapper).notify(eq(fact),eq("HDPAY:order"),anyString(),anyString(),anyString());
    }
    @Test void everySupportedMessageHasThreeSafeShortTexts() {
        for(var key:new String[]{"deposit","withdrawal_completed","withdrawal_returned","refund","password", "payment_method",
            "security","new_signin","support","device_alert","trial_24h","trial_1h","trial_end","retention_24h","retention_1h","retention_end"}) {
            var text=BusinessNotificationService.text(key);
            assertThat(text.zh()).isNotBlank().hasSizeLessThan(129);
            assertThat(text.en()).isNotBlank().hasSizeLessThan(129);
            assertThat(text.vi()).isNotBlank().hasSizeLessThan(129);
        }
        assertThatThrownBy(()->BusinessNotificationService.text("unknown")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingMigrationStopsBeforeScanningAnyBusinessData() {
        var mapper=mock(BusinessNotificationMapper.class);
        when(mapper.lockControl()).thenReturn(null);
        assertThatThrownBy(()->new BusinessNotificationService(mapper).scan()).hasMessage("BUSINESS_NOTIFICATION_MIGRATION_REQUIRED");
        verify(mapper,never()).facts();
    }
}
