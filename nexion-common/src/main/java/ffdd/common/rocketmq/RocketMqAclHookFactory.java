package ffdd.common.rocketmq;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.remoting.RPCHook;
import org.springframework.util.StringUtils;

public final class RocketMqAclHookFactory {
    private RocketMqAclHookFactory() {
    }

    public static RPCHook createOrNull(RocketMqAclProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return null;
        }

        String accessKey = trim(properties.getAccessKey());
        String secretKey = trim(properties.getSecretKey());
        if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            throw new IllegalStateException(
                    "RocketMQ ACL is enabled but nexion.outbox.rocketmq.acl.access-key or secret-key is not configured");
        }

        String securityToken = trim(properties.getSecurityToken());
        SessionCredentials credentials = StringUtils.hasText(securityToken)
                ? new SessionCredentials(accessKey, secretKey, securityToken)
                : new SessionCredentials(accessKey, secretKey);
        return new AclClientRPCHook(credentials);
    }

    public static String describe(RocketMqAclProperties properties) {
        if (properties == null || !properties.isEnabled()) {
            return "enabled=false";
        }
        return "enabled=true, accessKey=" + mask(properties.getAccessKey())
                + ", securityToken=" + StringUtils.hasText(trim(properties.getSecurityToken()));
    }

    private static String mask(String value) {
        String trimmed = trim(value);
        if (!StringUtils.hasText(trimmed)) {
            return "(blank)";
        }
        int visible = Math.min(3, trimmed.length());
        return trimmed.substring(0, visible) + "***";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
