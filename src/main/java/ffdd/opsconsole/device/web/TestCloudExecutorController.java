package ffdd.opsconsole.device.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.TestCloudExecutorService;
import ffdd.opsconsole.device.application.TestCloudExecutorService.Identity;
import ffdd.opsconsole.device.application.TestCloudExecutorService.CompleteRequest;
import ffdd.opsconsole.device.application.TestCloudExecutorService.RollbackReviewRequest;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test/cloud-executors/v1")
@RequiredArgsConstructor
public class TestCloudExecutorController {
    private final TestCloudExecutorService service;
    private final ObjectMapper json;

    @GetMapping("/instances")
    public Object instances(Authentication auth,@RequestParam(defaultValue="0") long afterDeviceId,
                            @RequestParam(defaultValue="100") int limit) {
        return service.instances(identity(auth),afterDeviceId,limit);
    }

    @PostMapping("/next-task")
    public Object next(Authentication auth,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        Identity identity=identity(auth); requireKeys(body(request),Set.of()); return service.next(identity,key);
    }
    @PostMapping("/heartbeat")
    public Object heartbeat(Authentication auth,HttpServletRequest request) {
        Identity identity=identity(auth); requireKeys(body(request),Set.of()); return service.heartbeat(identity,null,null);
    }
    @PostMapping("/tasks/{taskNo}/heartbeat")
    public Object taskHeartbeat(Authentication auth,@PathVariable String taskNo,HttpServletRequest request) {
        Identity identity=identity(auth); JsonNode body=body(request); requireKeys(body,Set.of("claimToken"));
        return service.heartbeat(identity,taskNo,text(body,"claimToken"));
    }
    @PostMapping("/tasks/{taskNo}/complete")
    public Object complete(Authentication auth,@PathVariable String taskNo,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        Identity identity=identity(auth); JsonNode body=body(request);
        requireKeys(body,Set.of("specVersion","inputHash","modelDigest","resultArtifactBase64","resultHash","proofNonce","proofTimestamp","claimToken"));
        if(!body.path("proofTimestamp").isIntegralNumber() || !body.path("proofTimestamp").canConvertToLong()) throw invalid();
        return service.complete(identity,taskNo,key,new CompleteRequest(text(body,"specVersion"),text(body,"inputHash"),
                text(body,"modelDigest"),text(body,"resultArtifactBase64"),text(body,"resultHash"),text(body,"proofNonce"),
                body.get("proofTimestamp").longValue(),text(body,"claimToken")));
    }
    @GetMapping("/tasks/{taskNo}/receipt")
    public Object receipt(Authentication auth,@PathVariable String taskNo) { return service.receipt(identity(auth),taskNo); }

    @GetMapping("/tasks/{taskNo}/manifest")
    public Object manifest(Authentication auth,@PathVariable String taskNo,
            @RequestHeader(value="Idempotency-Key",required=false) String nextKey) {
        return service.manifest(identity(auth),taskNo,nextKey);
    }

    @GetMapping("/tasks/{taskNo}/rollback-review")
    public Object rollbackReview(Authentication auth,@PathVariable String taskNo,
            @RequestHeader(value="Idempotency-Key",required=false) String completeKey,
            @RequestHeader(value="X-UVEL-Original-Next-Key",required=false) String originalNextKey,
            @RequestParam String originalRequestHash,@RequestParam String resultHash) {
        return service.rollbackReview(identity(auth),taskNo,completeKey,originalNextKey,
                new RollbackReviewRequest(originalRequestHash,resultHash));
    }

    private Identity identity(Authentication auth) {
        if(auth==null || !(auth.getPrincipal() instanceof Identity identity)) throw new BizException(401,"TEST_CLOUD_AUTH_INVALID");
        service.requireCurrent(identity); return identity;
    }
    private JsonNode body(HttpServletRequest request) {
        try {
            byte[] bytes=request.getInputStream().readNBytes(20001);
            if(bytes.length>20000) throw new BizException(413,"TEST_CLOUD_BODY_TOO_LARGE");
            JsonNode body=json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if(body==null || !body.isObject()) throw invalid(); return body;
        } catch(java.io.IOException malformed) { throw invalid(); }
    }
    private void requireKeys(JsonNode body,Set<String> expected) {
        Set<String> actual=new HashSet<>(); body.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected)) throw invalid();
    }
    private String text(JsonNode body,String field) { if(!body.path(field).isTextual()) throw invalid(); return body.get(field).textValue(); }
    private BizException invalid() { return new BizException(400,"TEST_CLOUD_BODY_INVALID"); }
}
