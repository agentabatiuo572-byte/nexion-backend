package ffdd.opsconsole.shared.rocketmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.remoting.RPCHook;
import org.junit.jupiter.api.Test;

class RocketMqAclHookFactoryTest {
    @Test
    void returnsNullWhenAclIsDisabled() {
        RocketMqAclProperties properties = new RocketMqAclProperties();
        properties.setEnabled(false);

        assertThat(RocketMqAclHookFactory.createOrNull(properties)).isNull();
    }

    @Test
    void rejectsEnabledAclWithoutAccessKeyOrSecretKey() {
        RocketMqAclProperties properties = new RocketMqAclProperties();
        properties.setEnabled(true);
        properties.setAccessKey("access-key-1");

        Throwable thrown = catchThrowable(() -> RocketMqAclHookFactory.createOrNull(properties));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .contains("secret-key")
                .doesNotContain("access-key-1");
    }

    @Test
    void createsAclHookWithSessionCredentials() {
        RocketMqAclProperties properties = new RocketMqAclProperties();
        properties.setEnabled(true);
        properties.setAccessKey("access-key-1");
        properties.setSecretKey("secret-key-1");
        properties.setSecurityToken("token-1");

        RPCHook hook = RocketMqAclHookFactory.createOrNull(properties);

        assertThat(hook).isInstanceOf(AclClientRPCHook.class);
        AclClientRPCHook aclHook = (AclClientRPCHook) hook;
        assertThat(aclHook.getSessionCredentials().getAccessKey()).isEqualTo("access-key-1");
        assertThat(aclHook.getSessionCredentials().getSecretKey()).isEqualTo("secret-key-1");
        assertThat(aclHook.getSessionCredentials().getSecurityToken()).isEqualTo("token-1");
    }

    @Test
    void describesAclWithoutLeakingSecrets() {
        RocketMqAclProperties properties = new RocketMqAclProperties();
        properties.setEnabled(true);
        properties.setAccessKey("access-key-1");
        properties.setSecretKey("secret-key-1");

        String description = RocketMqAclHookFactory.describe(properties);

        assertThat(description).contains("enabled=true");
        assertThat(description).contains("accessKey=acc***");
        assertThat(description).doesNotContain("secret-key-1");
    }
}
