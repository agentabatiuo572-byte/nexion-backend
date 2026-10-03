package ffdd.opsconsole.device.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.application.TestComputeWorkerService;
import ffdd.opsconsole.device.application.TestComputeWorkerService.CompleteRequest;
import ffdd.opsconsole.device.application.TestComputeWorkerService.Grant;
import ffdd.opsconsole.device.application.TestComputeWorkerService.ContinuousIdentity;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test/compute-workers")
@RequiredArgsConstructor
public class TestComputeWorkerController {
    private final AppTaskAssignmentService assignments;
    private final TestComputeWorkerService worker;
    private final ObjectMapper objectMapper;

    @PostMapping("/v1/tasks/{taskNo}/claim")
    public ApiResult<Map<String, Object>> claim(@PathVariable String taskNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth, HttpServletRequest request) {
        Grant grant = grant(auth, taskNo);
        if (body(request).size() != 0) throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        return assignments.testWorkerClaim(grant, taskNo, key);
    }

    @PostMapping("/v1/tasks/{taskNo}/complete")
    public ApiResult<Map<String, Object>> complete(@PathVariable String taskNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth, HttpServletRequest request) {
        Grant grant = grant(auth, taskNo);
        return assignments.testWorkerComplete(grant, taskNo, key, completion(body(request)));
    }

    private CompleteRequest completion(JsonNode json) {
        Set<String> keys = new HashSet<>(); json.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of("specVersion", "inputHash", "resultHash", "resultArtifactBase64", "proofNonce", "proofTimestamp"))) {
            throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        }
        for (String field : Set.of("specVersion", "inputHash", "resultHash", "resultArtifactBase64", "proofNonce")) {
            if (!json.get(field).isTextual()) throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        }
        if (!json.get("proofTimestamp").isIntegralNumber() || !json.get("proofTimestamp").canConvertToLong()) {
            throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        }
        return new CompleteRequest(json.get("specVersion").textValue(), json.get("inputHash").textValue(),
                json.get("resultHash").textValue(), json.get("resultArtifactBase64").textValue(),
                json.get("proofNonce").textValue(), json.get("proofTimestamp").longValue());
    }

    @PostMapping("/v1/tasks/{taskNo}/release")
    public ApiResult<Map<String, Object>> release(@PathVariable String taskNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth, HttpServletRequest request) {
        Grant grant = grant(auth, taskNo);
        if (body(request).size() != 0) throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        return assignments.testWorkerRelease(grant, taskNo, key);
    }

    @PostMapping("/v2/next-task")
    public ApiResult<Map<String, Object>> next(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication auth, HttpServletRequest request) {
        ContinuousIdentity identity = continuousIdentity(auth);
        if (body(request).size() != 0) throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        return assignments.continuousWorkerNext(identity, key);
    }

    @PostMapping("/v2/tasks/{taskNo}/complete")
    public ApiResult<Map<String, Object>> continuousComplete(@PathVariable String taskNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth, HttpServletRequest request) {
        return assignments.continuousWorkerComplete(continuousIdentity(auth), taskNo, key, completion(body(request)));
    }

    @PostMapping("/v2/tasks/{taskNo}/release")
    public ApiResult<Map<String, Object>> continuousRelease(@PathVariable String taskNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth, HttpServletRequest request) {
        ContinuousIdentity identity = continuousIdentity(auth);
        if (body(request).size() != 0) throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
        return assignments.continuousWorkerRelease(identity, taskNo, key);
    }

    @GetMapping("/v2/tasks/{taskNo}/receipt")
    public ApiResult<Map<String, Object>> receipt(@PathVariable String taskNo, Authentication auth) {
        return assignments.continuousWorkerReceipt(continuousIdentity(auth), taskNo);
    }

    private ContinuousIdentity continuousIdentity(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof ContinuousIdentity identity)) throw new BizException(401, "TEST_COMPUTE_WORKER_AUTH_INVALID");
        worker.requireContinuousCurrent(identity);
        return identity;
    }

    private Grant grant(Authentication auth, String taskNo) {
        if (auth == null || !(auth.getPrincipal() instanceof Grant grant)) throw new BizException(401, "TEST_COMPUTE_WORKER_AUTH_INVALID");
        worker.requireTaskPath(grant, taskNo);
        return grant;
    }

    private JsonNode body(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) throw new BizException(413, "TEST_COMPUTE_BODY_TOO_LARGE");
            JsonNode json = objectMapper.readerFor(JsonNode.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if (json == null || !json.isObject()) throw new BizException(400, "TEST_COMPUTE_BODY_INVALID");
            return json;
        } catch (IOException malformed) { throw new BizException(400, "TEST_COMPUTE_BODY_INVALID"); }
    }
}
