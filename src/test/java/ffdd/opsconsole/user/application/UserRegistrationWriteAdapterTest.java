package ffdd.opsconsole.user.application;

import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserRegistrationWriteAdapterTest {
    private final UserOpsMapper mapper=mock(UserOpsMapper.class);
    private final UserRegistrationWriteAdapter writes=new UserRegistrationWriteAdapter(mapper);
    private static NewAccountCommand command(int sandbox) {
        return new NewAccountCommand("+84","123456789","private-ip","private-hash","name","CODE",null,null,"L1","V0","ACTIVE",sandbox,"en");
    }
    @Test void actualInsertHasNoPresetIdAndPreservesExistingRegistrationFields() {
        when(mapper.insert(any(UserEntity.class))).thenAnswer(i->{UserEntity u=i.getArgument(0);
            assertThat(u.getId()).isNull();assertThat(u.getSandbox()).isZero();assertThat(u.getPasswordHash()).isEqualTo("private-hash");
            assertThat(u.getIsDeleted()).isZero();u.setId(7L);return 1;});
        assertThat(writes.insertNewAccount(command(0))).isEqualTo(7);
    }
    @Test void failedInsertOrMissingGeneratedIdCannotBecomeAnAccountBirth() {
        when(mapper.insert(any(UserEntity.class))).thenReturn(0,1);
        assertThatThrownBy(()->writes.insertNewAccount(command(0))).hasMessage("USER_REGISTRATION_INSERT_FAILED");
        assertThatThrownBy(()->writes.insertNewAccount(command(0))).hasMessage("USER_REGISTRATION_INSERT_FAILED");
    }
    @Test void productionNonceIsPassedOnlyToInsertAndAffectedRowCountIsNotAnAttestation() {
        String nonce="12345678-1234-1234-1234-123456789abc";
        when(mapper.ensureNewRegisteredUserWallet(7L,0,nonce)).thenReturn(1);
        writes.ensureRegisteredWallet(7,0,nonce);verify(mapper).ensureNewRegisteredUserWallet(7L,0,nonce);
        verify(mapper,never()).ensureRegisteredUserWallet(anyLong(),anyInt());
    }
    @Test void sandboxRetainsLegacyEnsureAndMalformedProductionNonceFailsBeforeSql() {
        writes.ensureRegisteredWallet(7,1,null);verify(mapper).ensureRegisteredUserWallet(7L,1);
        assertThatThrownBy(()->writes.ensureRegisteredWallet(7,0,null)).isInstanceOf(IllegalArgumentException.class);
        verify(mapper,never()).ensureNewRegisteredUserWallet(anyLong(),eq(0),any());
    }
}
