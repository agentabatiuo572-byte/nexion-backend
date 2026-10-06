package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.SupportTicketCreationPolicyService.CreationPolicy;
import ffdd.opsconsole.content.application.SupportTicketCreationRejectedException;
import ffdd.opsconsole.shared.api.ApiResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupportTicketCreationExceptionHandler {
    @ExceptionHandler(SupportTicketCreationRejectedException.class)
    public ApiResult<CreationPolicy> rejected(SupportTicketCreationRejectedException exception,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setStatus(exception.getCode());
        return ApiResult.fail(exception.getCode(), exception.getMessage(), exception.policy());
    }
}
