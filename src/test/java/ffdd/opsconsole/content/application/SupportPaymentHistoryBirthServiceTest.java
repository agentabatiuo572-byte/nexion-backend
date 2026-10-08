package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.mapper.SupportPaymentHistoryBirthMapper;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SupportPaymentHistoryBirthServiceTest {
    private final SupportPaymentHistoryBirthMapper mapper=mock(SupportPaymentHistoryBirthMapper.class);
    private final DataSource dataSource=mock(DataSource.class);
    private final SupportPaymentHistoryBirthService service=new SupportPaymentHistoryBirthService(mapper,dataSource);

    @BeforeEach void transaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource,new Object());
        when(mapper.customer(12)).thenReturn(new SupportPaymentHistoryBirthMapper.Customer(12L,0,0));
        when(mapper.insert(anyLong(),anyString(),nullable(Integer.class),anyString())).thenReturn(1);
    }
    @AfterEach void clean() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.clear();
    }
    @Test void actualNewAccountStoresOnlyItsLifetimeAndCreationEnvironment() {
        service.registerNewAccount(12);
        verify(mapper).insert(12,SupportPaymentHistoryBirthService.CAPTURE_PROTOCOL,0,"PRODUCTION");
    }
    @Test void sandboxAndMissingEnvironmentCannotBecomeProductionHistory() {
        when(mapper.customer(12)).thenReturn(new SupportPaymentHistoryBirthMapper.Customer(12L,1,0));
        service.registerNewAccount(12);
        verify(mapper).insert(12,SupportPaymentHistoryBirthService.CAPTURE_PROTOCOL,1,"EXCLUDED");
        when(mapper.customer(13)).thenReturn(new SupportPaymentHistoryBirthMapper.Customer(13L,null,0));
        service.registerNewAccount(13);
        verify(mapper).insert(13,SupportPaymentHistoryBirthService.CAPTURE_PROTOCOL,null,"UNKNOWN");
    }
    @Test void replayDoesNotMoveBirthOrUpgradeAnExcludedEnvironment() {
        when(mapper.find(12)).thenReturn(new SupportPaymentHistoryBirthMapper.Birth(12L,
            SupportPaymentHistoryBirthService.CAPTURE_PROTOCOL,"AUTH_NEW_ACCOUNT_REGISTRATION",LocalDateTime.now(),1,"EXCLUDED"));
        service.registerNewAccount(12);
        verify(mapper,never()).insert(anyLong(),anyString(),nullable(Integer.class),anyString());
    }
    @Test void noActualOrWrongDatasourceOrReadonlyTransactionCannotAttestBirth() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(()->service.registerNewAccount(12)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(()->service.registerNewAccount(12)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.unbindResource(dataSource);
        assertThatThrownBy(()->service.registerNewAccount(12)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }
    @Test void missingCustomerAndInsertFailuresPropagate() {
        when(mapper.customer(12)).thenReturn(null);
        assertThatThrownBy(()->service.registerNewAccount(12)).isInstanceOf(IllegalStateException.class);
        when(mapper.customer(12)).thenReturn(new SupportPaymentHistoryBirthMapper.Customer(12L,0,0));
        when(mapper.insert(12,SupportPaymentHistoryBirthService.CAPTURE_PROTOCOL,0,"PRODUCTION")).thenThrow(new IllegalStateException("storage"));
        assertThatThrownBy(()->service.registerNewAccount(12)).isInstanceOf(IllegalStateException.class).hasMessage("storage");
    }
}
