package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;

/** A backend-only loopback verifier. A worker result is never payable on its own declaration. */
final class ComputeV2ModalVerifier {
    static final int MAX_BUNDLE_BYTES=32*1024*1024;
    static final int MAX_ENCODED_BUNDLE_BYTES=((MAX_BUNDLE_BYTES+2)/3)*4;
    private static final URI ENDPOINT=URI.create("http://127.0.0.1:11442/verify");
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new java.net.ProxySelector(){
                public List<java.net.Proxy> select(URI uri){return List.of(java.net.Proxy.NO_PROXY);}
                public void connectFailed(URI uri,java.net.SocketAddress address,java.io.IOException failure){ }
            }).build();
    record Submission(byte[] bytes,String taskBindingHash) { }
    private final Environment environment;
    private final ObjectMapper json;

    ComputeV2ModalVerifier(Environment environment,ObjectMapper json){
        this.environment=environment;this.json=json.copy();
        this.json.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(MAX_BUNDLE_BYTES).build());
    }

    Submission decode(String encoded,String resultHash,Map<String,Object> task) {
        if(encoded==null || encoded.length()>MAX_ENCODED_BUNDLE_BYTES || resultHash==null || !resultHash.matches("[a-f0-9]{64}"))throw invalid();
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length==0 || bytes.length>MAX_BUNDLE_BYTES || !Base64.getEncoder().encodeToString(bytes).equals(encoded)
                    || !TestCloudExecutorService.sha256(bytes).equals(resultHash))throw invalid();
            JsonNode bundle=json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if(bundle==null || !bundle.isObject() || !fields(bundle).equals(Set.of("result","artifactBase64")))throw invalid();
            JsonNode result=bundle.path("result");
            if(!result.isObject() || !result.path("schemaVersion").isInt() || result.path("schemaVersion").intValue()!=1
                    || !result.path("metrics").isObject())throw invalid();
            for(String field:List.of("taskClass","runtimeKey","modelRevision","inputHash"))
                if(!result.path(field).isTextual() || !Objects.equals(task.get(field),result.get(field).textValue()))throw invalid();
            String binding=result.path("taskBindingHash").asText();
            if(!binding.matches("[a-f0-9]{64}"))throw invalid();
            JsonNode artifact=result.path("artifact");
            String taskClass=task.get("taskClass").toString();
            String filename=switch(taskClass){case "IG"->"result.png";case "VG"->"result.mp4";case "LL"->"tokens.json";
                case "FT"->"adapter.zip";case "SP"->"transcript.json";case "EM"->"embedding.f32";default->throw invalid();};
            String mediaType=switch(taskClass){case "IG"->"image/png";case "VG"->"video/mp4";case "FT"->"application/zip";
                case "EM"->"application/octet-stream";default->"application/json";};
            if(!artifact.isObject() || !fields(artifact).equals(Set.of("path","mediaType","sha256","sizeBytes"))
                    || !filename.equals(artifact.path("path").asText()) || !mediaType.equals(artifact.path("mediaType").asText())
                    || !artifact.path("sizeBytes").isIntegralNumber() || !artifact.path("sizeBytes").canConvertToLong()
                    || !bundle.path("artifactBase64").isTextual())throw invalid();
            String artifactEncoded=bundle.get("artifactBase64").textValue();
            if(artifactEncoded.length()>MAX_ENCODED_BUNDLE_BYTES)throw invalid();
            byte[] artifactBytes=Base64.getDecoder().decode(artifactEncoded);
            if(artifactBytes.length==0 || artifactBytes.length>MAX_BUNDLE_BYTES || artifact.path("sizeBytes").longValue()!=artifactBytes.length
                    || "EM".equals(taskClass) && artifactBytes.length!=3072
                    || !Base64.getEncoder().encodeToString(artifactBytes).equals(artifactEncoded)
                    || !TestCloudExecutorService.sha256(artifactBytes).equals(artifact.path("sha256").asText()))throw invalid();
            return new Submission(bytes,binding);
        }catch(java.io.IOException | IllegalArgumentException malformed){throw invalid();}
    }

    byte[] verify(Submission submission,Map<String,Object> task,String resultHash,int executionTimeoutSec) {
        String token=environment.getProperty("nexion.compute-v2.modal-validator-token","");
        if(!token.matches("[A-Za-z0-9_-]{32,128}"))throw new BizException(503,"COMPUTE_V2_MODAL_VALIDATOR_NOT_CONFIGURED");
        try {
            byte[] body=json.writeValueAsBytes(Map.of("task",task,"bundleBase64",Base64.getEncoder().encodeToString(submission.bytes()),
                    "executionTimeoutSec",executionTimeoutSec));
            var request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(executionTimeoutSec+120L))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(65537);
                if(response.statusCode()!=200 || bytes.length>65536)throw new BizException(503,"COMPUTE_V2_MODAL_VALIDATOR_UNCONFIRMED");
                JsonNode verified=json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
                if(verified==null || !verified.isObject() || !verified.path("verified").isBoolean() || !verified.get("verified").booleanValue()
                        || !submission.taskBindingHash().equals(verified.path("taskBindingHash").asText())
                        || !resultHash.equals(verified.path("resultHash").asText()))throw invalid();
            }
            return submission.bytes();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new BizException(503,"COMPUTE_V2_MODAL_VALIDATION_INTERRUPTED");}
        catch(java.io.IOException unavailable){throw new BizException(503,"COMPUTE_V2_MODAL_VALIDATOR_UNCONFIRMED");}
    }
    private static Set<String> fields(JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    private static BizException invalid(){return new BizException(422,"COMPUTE_V2_MODAL_RESULT_INVALID");}
}
