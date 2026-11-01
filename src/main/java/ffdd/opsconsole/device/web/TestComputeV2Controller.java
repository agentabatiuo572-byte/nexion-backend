package ffdd.opsconsole.device.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.device.application.TestCloudExecutorService;
import ffdd.opsconsole.device.application.TestComputeV2Service;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Explicit loopback-only trusted-worker contract; never installed in the public app security chain. */
@RestController
@RequestMapping("/api/test/cloud-executors/v2")
@ConditionalOnProperty(name="nexion.compute-v2.enabled",havingValue="true")
public class TestComputeV2Controller {
    private final TestComputeV2Service service;
    private final ObjectMapper json;
    private final ObjectMapper completionJson;
    public TestComputeV2Controller(TestComputeV2Service service,ObjectMapper json){
        this.service=service;this.json=json;this.completionJson=json.copy();
        this.completionJson.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(TestComputeV2Service.MAX_COMPLETION_BODY_BYTES).build());
    }
    @PostMapping("/next-task")
    public Object next(Authentication auth,HttpServletRequest request){var b=body(request,Set.of("runner"));return ApiResult.ok(service.next(identity(auth),text(b,"runner")));}
    @PostMapping("/tasks/{taskNo}/heartbeat")
    public Object heartbeat(Authentication auth,@PathVariable String taskNo,HttpServletRequest request){
        var b=body(request,Set.of("runner","generation","claimToken"));
        return ApiResult.ok(service.heartbeat(identity(auth),text(b,"runner"),task(taskNo),number(b,"generation"),text(b,"claimToken")));
    }
    @PostMapping("/tasks/{taskNo}/complete")
    public Object complete(Authentication auth,@PathVariable String taskNo,HttpServletRequest request){
        var b=body(request,Set.of("runner","generation","claimToken","proofNonce","inputHash","modelDigest","resultHash","resultArtifactBase64","proofTimestamp"),
                TestComputeV2Service.MAX_COMPLETION_BODY_BYTES);
        return ApiResult.ok(service.complete(identity(auth),text(b,"runner"),task(taskNo),new TestComputeV2Service.Complete(
                number(b,"generation"),text(b,"claimToken"),text(b,"proofNonce"),text(b,"inputHash"),text(b,"modelDigest"),
                text(b,"resultHash"),text(b,"resultArtifactBase64"),number(b,"proofTimestamp"))));
    }
    @PostMapping("/tasks/{taskNo}/exited")
    public Object exited(Authentication auth,@PathVariable String taskNo,HttpServletRequest request){
        var b=body(request,Set.of("runner","generation","claimToken"));
        return ApiResult.ok(service.exited(identity(auth),text(b,"runner"),task(taskNo),number(b,"generation"),text(b,"claimToken")));
    }
    @GetMapping("/tasks/{taskNo}/receipt")
    public Object receipt(Authentication auth,@PathVariable String taskNo,@RequestHeader("X-Compute-Runner") String runner,
            @RequestHeader("X-Compute-Generation") long generation,@RequestHeader("X-Compute-Claim") String claim){
        if(generation<=0)throw invalid();return ApiResult.ok(service.receipt(identity(auth),runner,task(taskNo),generation,claim));
    }
    @PostMapping("/tasks/{taskNo}/observed-exit")
    public Object observedExit(Authentication auth,@PathVariable String taskNo,HttpServletRequest request){
        var b=body(request,Set.of("runner","generation","claimToken","observation","signature"));
        if(!b.path("observation").isObject())throw invalid();
        return ApiResult.ok(service.observedExit(identity(auth),text(b,"runner"),task(taskNo),number(b,"generation"),text(b,"claimToken"),b.get("observation"),text(b,"signature")));
    }
    private TestCloudExecutorService.Identity identity(Authentication auth){
        if(auth==null || !(auth.getPrincipal() instanceof TestCloudExecutorService.Identity value))throw new BizException(401,"TEST_CLOUD_AUTH_INVALID");return value;
    }
    private JsonNode body(HttpServletRequest request,Set<String> fields){
        return body(request,fields,20000);
    }
    private JsonNode body(HttpServletRequest request,Set<String> fields,int limit){
        try{byte[] bytes=request.getInputStream().readNBytes(limit+1);if(bytes.length>limit)throw new BizException(413,"COMPUTE_V2_BODY_TOO_LARGE");
            JsonNode b=(limit>20000?completionJson:json).readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if(b==null||!b.isObject())throw invalid();Set<String> actual=new HashSet<>();b.fieldNames().forEachRemaining(actual::add);
            if(!actual.equals(fields))throw invalid();return b;
        }catch(java.io.IOException malformed){throw invalid();}
    }
    private String text(JsonNode b,String key){if(!b.path(key).isTextual())throw invalid();return b.get(key).textValue();}
    private long number(JsonNode b,String key){if(!b.path(key).isIntegralNumber()||!b.path(key).canConvertToLong()||b.get(key).longValue()<=0)throw invalid();return b.get(key).longValue();}
    private String task(String value){if(value==null||!value.matches("CV2-[0-9A-F]{32}"))throw invalid();return value;}
    private BizException invalid(){return new BizException(400,"COMPUTE_V2_BODY_INVALID");}
}
