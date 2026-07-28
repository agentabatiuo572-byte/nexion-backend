package ffdd.opsconsole.emergency.application;

import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;

/**
 * J4 到目标业务域的唯一写边界。
 *
 * <p>J4 只负责编排；目标域仍负责权限、幂等、CAS、审计和真实副作用。
 */
public interface J4DomainActionGateway {

    ApiResult<Map<String, Object>> validate(String domain, String reference);

    ApiResult<Map<String, Object>> execute(
            String domain,
            String reference,
            String idempotencyKey,
            String reason,
            String operator);
}
