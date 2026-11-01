package ffdd.opsconsole.device.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.device.application.PhoneComputeV2Access;
import ffdd.opsconsole.device.application.TestComputeV2Service;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Current native App session supplies its installation; clients never choose a user/device or obtain fleet credentials. */
@RestController
@RequestMapping("/api/app/compute-v2/phone")
@ConditionalOnProperty(name="nexion.compute-v2.enabled",havingValue="true")
public class AppPhoneComputeV2Controller {
    private final TestComputeV2Service service;
    private final ObjectMapper json;
    public AppPhoneComputeV2Controller(TestComputeV2Service service,ObjectMapper json){this.service=service;this.json=json;}

    @PostMapping("/next")
    public Object next(Authentication auth,HttpServletRequest request) {
        var b=body(request,Set.of("runtimeKey","modelRevision","modelDigest","engineReady","modelSha256","enginePlatform"));
        if(!b.path("engineReady").isBoolean())throw invalid();
        return ApiResult.ok(service.phoneNext(auth,new PhoneComputeV2Access.EngineReady(text(b,"runtimeKey"),text(b,"modelRevision"),
                text(b,"modelDigest"),b.get("engineReady").booleanValue(),text(b,"modelSha256"),text(b,"enginePlatform"))));
    }
    @PostMapping("/heartbeat")
    public Object heartbeat(Authentication auth,HttpServletRequest request) {
        var b=body(request,Set.of("taskNo","generation","claimToken"));
        return ApiResult.ok(service.phoneHeartbeat(auth,task(b),number(b,"generation"),text(b,"claimToken")));
    }
    @PostMapping("/complete")
    public Object complete(Authentication auth,HttpServletRequest request) {
        var b=body(request,Set.of("taskNo","generation","claimToken","proofNonce","inputHash","modelDigest","resultHash","resultArtifactBase64","proofTimestamp"));
        return ApiResult.ok(service.phoneComplete(auth,task(b),new TestComputeV2Service.Complete(number(b,"generation"),text(b,"claimToken"),
                text(b,"proofNonce"),text(b,"inputHash"),text(b,"modelDigest"),text(b,"resultHash"),text(b,"resultArtifactBase64"),number(b,"proofTimestamp"))));
    }
    @PostMapping("/receipt")
    public Object receipt(Authentication auth,HttpServletRequest request) {
        var b=body(request,Set.of("taskNo","generation","claimToken"));
        return ApiResult.ok(service.phoneReceipt(auth,task(b),number(b,"generation"),text(b,"claimToken")));
    }
    @PostMapping("/exited")
    public Object exited(Authentication auth,HttpServletRequest request) {
        var b=body(request,Set.of("taskNo","generation","claimToken","workerExited","exitReason"));
        if(!b.path("workerExited").isBoolean())throw invalid();
        return ApiResult.ok(service.phoneExited(auth,task(b),number(b,"generation"),text(b,"claimToken"),
                b.get("workerExited").booleanValue(),text(b,"exitReason")));
    }
    private JsonNode body(HttpServletRequest request,Set<String> expected) {
        try {
            byte[] bytes=request.getInputStream().readNBytes(20001);
            if(bytes.length>20000)throw new BizException(413,"COMPUTE_V2_BODY_TOO_LARGE");
            JsonNode value=json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if(value==null || !value.isObject())throw invalid();
            var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
            if(!actual.equals(expected))throw invalid();return value;
        } catch(java.io.IOException malformed){throw invalid();}
    }
    private String text(JsonNode b,String key){if(!b.path(key).isTextual())throw invalid();return b.get(key).textValue();}
    private long number(JsonNode b,String key){if(!b.path(key).isIntegralNumber() || !b.path(key).canConvertToLong() || b.get(key).longValue()<=0)throw invalid();return b.get(key).longValue();}
    private String task(JsonNode b){String task=text(b,"taskNo");if(!task.matches("CV2-[0-9A-F]{32}"))throw invalid();return task;}
    private BizException invalid(){return new BizException(422,"COMPUTE_V2_PHONE_REQUEST_INVALID");}
}
