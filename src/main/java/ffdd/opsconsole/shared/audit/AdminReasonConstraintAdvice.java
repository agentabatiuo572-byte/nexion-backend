package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Server-authoritative A2 reason guard. It is a lowest-precedence controller advisor so Spring
 * method security executes first: an unauthorized caller receives 403 without learning whether
 * governance configuration exists. It selects only explicit reason/reasonText DTO contracts,
 * the operation-reason header/query contract, or {@link A2ReasonRequired}; URL names are never
 * treated as a sensitivity signal, and nested/list DTOs cannot bypass validation.
 */
@Component
@RequiredArgsConstructor
public class AdminReasonConstraintAdvice extends StaticMethodMatcherPointcutAdvisor {
    static final String OPERATION_REASON_HEADER = "X-Operation-Reason";
    private static final String A2_BOOTSTRAP_PATH = "/api/admin/platform/audit/mechanism-params/ttl";
    private static final int MAX_SCAN_DEPTH = 12;
    private final A2RuntimePolicy policy;

    @jakarta.annotation.PostConstruct
    void initializeAdvisor() {
        setOrder(Ordered.LOWEST_PRECEDENCE);
        setAdvice((MethodInterceptor) this::invoke);
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        Package pkg = targetClass.getPackage();
        return pkg != null && pkg.getName().startsWith("ffdd.opsconsole")
                && AnnotatedElementUtils.hasAnnotation(targetClass, RestController.class);
    }

    private Object invoke(MethodInvocation invocation) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request != null && authenticated() && protectedAdminRequest(request)) {
            validateAdminRequest(invocation.getArguments(), request, invocation.getMethod());
        }
        return invocation.proceed();
    }

    boolean protectedAdminRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/admin/")) return false;
        // Authentication/session lifecycle writes have their own credential and audit controls.
        // Requiring an A2 business reason here would break login/logout/password rotation.
        if (uri.equals("/api/admin/auth") || uri.startsWith("/api/admin/auth/")) return false;
        return true;
    }

    void validateAdminRequest(Object[] arguments, HttpServletRequest request) {
        validateAdminRequest(arguments, request, null);
    }

    private void validateAdminRequest(Object[] arguments, HttpServletRequest request, Method handlerMethod) {
        String path = request.getRequestURI();
        if (A2_BOOTSTRAP_PATH.equals(path) && policy.reasonPolicyMissing()) {
            Object body = arguments == null || arguments.length == 0 ? null : java.util.Arrays.asList(arguments);
            int minimum = A2RuntimePolicy.parseReasonMinChars(
                    findNamedText(body, "value", new IdentityHashMap<>(), 0));
            List<String> reasons = new ArrayList<>();
            addProvided(reasons, request.getHeader(OPERATION_REASON_HEADER));
            addProvided(reasons, request.getParameter("reason"));
            addProvided(reasons, request.getParameter("reasonText"));
            collectReasons(body, reasons, new IdentityHashMap<>(), 0);
            validateReasons(reasons, minimum);
            return;
        }
        List<String> reasons = new ArrayList<>();
        addProvided(reasons, request.getHeader(OPERATION_REASON_HEADER));
        addProvided(reasons, request.getParameter("reason"));
        addProvided(reasons, request.getParameter("reasonText"));
        collectReasons(arguments, reasons, new IdentityHashMap<>(), 0);
        boolean explicitlyProtected = handlerMethod != null
                && (AnnotatedElementUtils.hasAnnotation(handlerMethod, A2ReasonRequired.class)
                || declaresReasonContract(handlerMethod));
        // Ordinary admin calls without a reason/reasonText/header/query/annotation contract are
        // outside A2 reason governance. URL names such as export/download are deliberately ignored.
        if (reasons.isEmpty() && !explicitlyProtected) return;
        validateReasons(reasons, policy.reasonMinChars());
    }

    private boolean declaresReasonContract(Method method) {
        for (Parameter parameter : method.getParameters()) {
            org.springframework.web.bind.annotation.RequestParam query = parameter.getAnnotation(
                    org.springframework.web.bind.annotation.RequestParam.class);
            String queryName = query == null ? "" : (query.name().isBlank() ? query.value() : query.name());
            if (reasonName(queryName)) return true;
            org.springframework.web.bind.annotation.RequestHeader header = parameter.getAnnotation(
                    org.springframework.web.bind.annotation.RequestHeader.class);
            String headerName = header == null ? "" : (header.name().isBlank() ? header.value() : header.name());
            if (OPERATION_REASON_HEADER.equalsIgnoreCase(headerName)) return true;
            if (typeDeclaresReasonField(parameter.getType(), new java.util.HashSet<>(), 0)) return true;
        }
        return false;
    }

    private boolean typeDeclaresReasonField(Class<?> type, java.util.Set<Class<?>> seen, int depth) {
        if (type == null || depth > MAX_SCAN_DEPTH || scalarType(type) || !seen.add(type)) return false;
        Package pkg = type.getPackage();
        if (pkg == null || !pkg.getName().startsWith("ffdd.opsconsole")) return false;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (reasonName(field.getName())
                        || typeDeclaresReasonField(field.getType(), seen, depth + 1)) return true;
            }
        }
        return false;
    }

    private boolean scalarType(Class<?> type) {
        return CharSequence.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type)
                || type.isPrimitive() || type == Boolean.class || type.isEnum()
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type);
    }

    Object validateReasonBearingBody(Object body) {
        List<String> reasons = new ArrayList<>();
        collectReasons(body, reasons, new IdentityHashMap<>(), 0);
        if (reasons.isEmpty()) return body;
        int minimum = policy.reasonMinChars();
        for (String reason : reasons) A2RuntimePolicy.validateReason(reason, minimum);
        return body;
    }

    Object validateReasonBearingBody(Object body, String requestPath) {
        if (!A2_BOOTSTRAP_PATH.equals(requestPath) || !policy.reasonPolicyMissing()) {
            if (policy.reasonPolicyMissing()) throw new BizException(503, "A2_REASON_POLICY_UNAVAILABLE");
            return validateReasonBearingBody(body);
        }
        String proposed = findNamedText(body, "value", new IdentityHashMap<>(), 0);
        int minimum = A2RuntimePolicy.parseReasonMinChars(proposed);
        List<String> reasons = new ArrayList<>();
        collectReasons(body, reasons, new IdentityHashMap<>(), 0);
        validateReasons(reasons, minimum);
        return body;
    }

    private void validateReasons(List<String> reasons, int minimum) {
        if (reasons.isEmpty()) throw new BizException(422, "REASON_REQUIRED");
        for (String reason : reasons) A2RuntimePolicy.validateReason(reason, minimum);
    }

    private void collectReasons(Object value, List<String> target, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > MAX_SCAN_DEPTH || scalar(value)) return;
        if (seen.put(value, Boolean.TRUE) != null) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (reasonName(key)) target.add(entry.getValue() instanceof String text ? text : null);
                else collectReasons(entry.getValue(), target, seen, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectReasons(item, target, seen, depth + 1));
            return;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                collectReasons(Array.get(value, i), target, seen, depth + 1);
            }
            return;
        }
        Package pkg = value.getClass().getPackage();
        if (pkg == null || !pkg.getName().startsWith("ffdd.opsconsole")) return;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    if (reasonName(field.getName())) target.add(fieldValue instanceof String text ? text : null);
                    else collectReasons(fieldValue, target, seen, depth + 1);
                } catch (RuntimeException | IllegalAccessException ex) {
                    throw new BizException(422, "REASON_CONTRACT_UNREADABLE");
                }
            }
        }
    }

    private String findNamedText(Object value, String name, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > MAX_SCAN_DEPTH || scalar(value) || seen.put(value, Boolean.TRUE) != null) return null;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (name.equals(String.valueOf(entry.getKey()))) return entry.getValue() instanceof String text ? text : null;
                String nested = findNamedText(entry.getValue(), name, seen, depth + 1);
                if (nested != null) return nested;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String nested = findNamedText(item, name, seen, depth + 1);
                if (nested != null) return nested;
            }
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                String nested = findNamedText(Array.get(value, i), name, seen, depth + 1);
                if (nested != null) return nested;
            }
        } else {
            Package pkg = value.getClass().getPackage();
            if (pkg != null && pkg.getName().startsWith("ffdd.opsconsole")) {
                for (Field field : value.getClass().getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object fieldValue = field.get(value);
                        if (name.equals(field.getName())) return fieldValue instanceof String text ? text : null;
                        String nested = findNamedText(fieldValue, name, seen, depth + 1);
                        if (nested != null) return nested;
                    } catch (RuntimeException | IllegalAccessException ex) {
                        throw new BizException(422, "REASON_CONTRACT_UNREADABLE");
                    }
                }
            }
        }
        return null;
    }

    private boolean scalar(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof java.time.temporal.TemporalAccessor;
    }

    private boolean reasonName(String name) {
        return "reason".equalsIgnoreCase(name) || "reasonText".equalsIgnoreCase(name);
    }

    private void addProvided(List<String> reasons, String value) {
        if (value != null) reasons.add(value);
    }

    private boolean authenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
