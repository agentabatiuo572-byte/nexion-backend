package ffdd.opsconsole.content.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Direct FCM HTTP v1 and APNs HTTP/2. Provider acceptance never asserts device delivery. */
@Component
public class DirectPushClient {
    public record Result(String status, String code, String taskId, int retryAfterSeconds) {
        public Result(String status, String code, String taskId) { this(status, code, taskId, 0); }
    }
    private static final URI OAUTH = URI.create("https://oauth2.googleapis.com/token");
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private final ObjectMapper json;
    private final Environment env;
    private final HttpClient http;
    private final TokenCache cache = new TokenCache();
    // Accessed only under this client's synchronized authentication methods/response reset.
    private static final class TokenCache {
        String oauthToken, oauthIdentity, apnsToken, apnsIdentity;
        long oauthExpiry, apnsExpiry;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DirectPushClient(ObjectMapper json, Environment env) {
        this(json, env, HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    DirectPushClient(ObjectMapper json, Environment env, HttpClient http) { this.json=json; this.env=env; this.http=http; }

    public String configurationState() {
        if (!enabled()) return "DISABLED";
        return "FCM=" + configurationState("FCM") + ";APNS=" + configurationState("APNS");
    }
    public String configurationState(String provider) {
        if (!enabled()) return "DISABLED";
        try {
            if ("FCM".equals(provider)) {
                String project = property("fcm.project-id");
                if (!project.matches("[a-z][a-z0-9-]{4,62}")) return "MISSING_OR_INVALID_CONFIG";
                JsonNode key = serviceAccount();
                if (!"service_account".equals(key.path("type").asText()) || !key.path("client_email").asText().matches("[^@\\s]+@[^@\\s]+")
                        || !project.equals(key.path("project_id").asText())) return "MISSING_OR_INVALID_CONFIG";
                privateKey(key.path("private_key").asText(), "RSA");
            } else if ("APNS".equals(provider)) {
                if (!property("apns.key-id").matches("[A-Z0-9]{10}") || !property("apns.team-id").matches("[A-Z0-9]{10}")
                        || !property("apns.topic").matches("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
                        || !Set.of("sandbox","production").contains(property("apns.environment"))) return "MISSING_OR_INVALID_CONFIG";
                var key = (java.security.interfaces.ECPrivateKey) privateKey(readSecureFile("apns.private-key-file"), "EC");
                if (key.getParams().getOrder().bitLength()!=256) return "MISSING_OR_INVALID_CONFIG";
            } else return "UNSUPPORTED_PROVIDER";
            return "CONFIGURED";
        } catch (Exception invalid) { return "MISSING_OR_INVALID_CONFIG"; }
    }
    private boolean enabled() { return env.getProperty("native-push.enabled",Boolean.class,false); }
    private String property(String name) { return env.getProperty("native-push."+name, ""); }
    private String readSecureFile(String name) throws Exception {
        Path path=Path.of(property(name));
        if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path)>65536) throw new IllegalArgumentException("INVALID_KEY_FILE");
        return Files.readString(path,StandardCharsets.UTF_8);
    }
    private JsonNode serviceAccount() throws Exception { return json.readTree(readSecureFile("fcm.service-account-file")); }

    public Result send(long deliveryId, long notificationId, String provider, String token, String language) {
        String state=configurationState(provider);
        if (!"CONFIGURED".equals(state)) return new Result("BLOCKED",state,null);
        if (!NativePushService.validNativeToken(provider,"FCM".equals(provider)?"android":"ios",token))
            return new Result("INVALID_TOKEN","INVALID_NATIVE_TOKEN",null);
        boolean sending=false;
        try {
            Map<String,String> alert=Map.of("title","UVEL","body",message(language));
            Map<String,String> data=Map.of("notificationId",Long.toString(notificationId),"deliveryId",Long.toString(deliveryId));
            HttpRequest request;
            if ("FCM".equals(provider)) {
                String access=accessToken();
                String body=json.writeValueAsString(Map.of("message",Map.of("token",token,"notification",alert,"data",data,
                        "android",Map.of("priority","high","ttl","3600s","notification",Map.of("channel_id","uvel_notifications")))));
                request=request(URI.create("https://fcm.googleapis.com/v1/projects/"+property("fcm.project-id")+"/messages:send"),body)
                        .header("Authorization","Bearer "+access).build();
            } else {
                String body=json.writeValueAsString(Map.of("aps",Map.of("alert",alert,"sound","default"),
                        "notificationId",data.get("notificationId"),"deliveryId",data.get("deliveryId")));
                String host="sandbox".equals(property("apns.environment"))?"api.sandbox.push.apple.com":"api.push.apple.com";
                request=request(URI.create("https://"+host+"/3/device/"+token),body).version(HttpClient.Version.HTTP_2)
                        .header("authorization","bearer "+apnsJwt()).header("apns-topic",property("apns.topic"))
                        .header("apns-push-type","alert").header("apns-priority","10")
                        .header("apns-expiration",Long.toString(Instant.now().plusSeconds(3600).getEpochSecond())).build();
            }
            sending=true;
            HttpResponse<byte[]> response=http.send(request, ignored -> new LimitedResponse());
            return "FCM".equals(provider)?fcmResponse(response):apnsResponse(response);
        } catch (AuthFailure failure) { return failure.result; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return new Result(sending?"UNKNOWN":"RETRY", "PROVIDER_INTERRUPTED",null);
        } catch (Exception failure) {
            // Exceptions may embed bearer tokens, private material or full device URLs. Do not log them.
            return new Result(sending?"UNKNOWN":"BLOCKED", sending?"PROVIDER_OUTCOME_UNKNOWN":"PROVIDER_AUTH_UNAVAILABLE",null);
        }
    }
    private HttpRequest.Builder request(URI uri,String body) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
    }

    private synchronized String accessToken() throws Exception {
        JsonNode account=serviceAccount();
        String identity=property("fcm.project-id")+account.toString();
        long now=Instant.now().getEpochSecond();
        if (identity.equals(cache.oauthIdentity) && cache.oauthToken!=null && now<cache.oauthExpiry) return cache.oauthToken;
        String assertion=jwt(Map.of("alg","RS256","typ","JWT"),Map.of("iss",account.path("client_email").asText(),
                "scope",SCOPE,"aud",OAUTH.toString(),"iat",now,"exp",now+3600),privateKey(account.path("private_key").asText(),"RSA"),"SHA256withRSA");
        HttpRequest request=HttpRequest.newBuilder(OAUTH).timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="
                        +URLEncoder.encode(assertion,StandardCharsets.UTF_8))).build();
        HttpResponse<byte[]> response;
        try { response=http.send(request,ignored -> new LimitedResponse()); }
        catch (java.io.IOException unavailable) { throw new AuthFailure(new Result("RETRY","FCM_OAUTH_UNAVAILABLE",null)); }
        if (response.statusCode()!=200) throw new AuthFailure(new Result(response.statusCode()==429 || response.statusCode()>=500?"RETRY":"BLOCKED",
                "FCM_OAUTH_HTTP_"+response.statusCode(),null,retryAfter(response)));
        JsonNode value=json.readTree(response.body());
        String access=value.path("access_token").asText(); long expires=value.path("expires_in").asLong();
        if (!"Bearer".equalsIgnoreCase(value.path("token_type").asText()) || !access.matches("[A-Za-z0-9._~+/-]{10,8192}=*") || expires<=60)
            throw new AuthFailure(new Result("BLOCKED","FCM_OAUTH_INVALID_RESPONSE",null));
        cache.oauthToken=access; cache.oauthIdentity=identity; cache.oauthExpiry=now+Math.min(expires,3600)-60;
        return access;
    }
    private synchronized String apnsJwt() throws Exception {
        String pem=readSecureFile("apns.private-key-file");
        String identity=property("apns.key-id")+property("apns.team-id")+pem;
        long now=Instant.now().getEpochSecond();
        if (identity.equals(cache.apnsIdentity) && cache.apnsToken!=null && now<cache.apnsExpiry) return cache.apnsToken;
        cache.apnsToken=jwt(Map.of("alg","ES256","kid",property("apns.key-id")),Map.of("iss",property("apns.team-id"),"iat",now),
                privateKey(pem,"EC"),"SHA256withECDSAinP1363Format");
        cache.apnsIdentity=identity; cache.apnsExpiry=now+3000; return cache.apnsToken;
    }
    private String jwt(Map<String,Object> header,Map<String,Object> claims,PrivateKey key,String algorithm) throws Exception {
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String input=encoder.encodeToString(json.writeValueAsBytes(header))+"."+encoder.encodeToString(json.writeValueAsBytes(claims));
        Signature signature=Signature.getInstance(algorithm); signature.initSign(key); signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input+"."+encoder.encodeToString(signature.sign());
    }
    static PrivateKey privateKey(String pem,String type) throws Exception {
        String content=pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        return KeyFactory.getInstance(type).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(content)));
    }
    private Result fcmResponse(HttpResponse<byte[]> response) throws Exception {
        int status=response.statusCode();
        if (status==429 || status==500 || status==503) return new Result("RETRY","FCM_HTTP_"+status,null,Math.max(60,retryAfter(response)));
        if (status==401) synchronized(this) { cache.oauthToken=null; }
        JsonNode value;
        try { value=json.readTree(response.body()); }
        catch (Exception malformed) { return new Result(status==200?"UNKNOWN":"BLOCKED","FCM_HTTP_"+status+"_INVALID_RESPONSE",null); }
        if (value==null) return new Result(status==200?"UNKNOWN":"BLOCKED","FCM_HTTP_"+status+"_INVALID_RESPONSE",null);
        if (status==200) {
            String name=value.path("name").asText();
            String prefix="projects/"+property("fcm.project-id")+"/messages/";
            return name.startsWith(prefix) && name.length()>prefix.length() && name.length()<=512
                    ?new Result("ACCEPTED","FCM_ACCEPTED",name):new Result("UNKNOWN","FCM_MISSING_RECEIPT",null);
        }
        String code="";
        for (JsonNode detail:value.path("error").path("details"))
            if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(detail.path("@type").asText())) code=detail.path("errorCode").asText();
        if ("UNREGISTERED".equals(code)) return new Result("INVALID_TOKEN","FCM_"+code,null);
        return new Result("BLOCKED","FCM_HTTP_"+status,null);
    }
    private Result apnsResponse(HttpResponse<byte[]> response) throws Exception {
        int status=response.statusCode();
        if (response.version()!=HttpClient.Version.HTTP_2) return new Result("UNKNOWN","APNS_HTTP2_REQUIRED",null);
        if (status==200) {
            String id=response.headers().firstValue("apns-id").orElse("");
            return id.matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
                    ?new Result("ACCEPTED","APNS_ACCEPTED",id):new Result("UNKNOWN","APNS_MISSING_RECEIPT",null);
        }
        if (status==429 || status==500 || status==503) return new Result("RETRY","APNS_HTTP_"+status,null,Math.max(60,retryAfter(response)));
        String reason;
        try { reason=json.readTree(response.body()).path("reason").asText(); }
        catch (Exception malformed) { return new Result("BLOCKED","APNS_HTTP_"+status+"_INVALID_RESPONSE",null); }
        if (status==410 && "Unregistered".equals(reason))
            return new Result("INVALID_TOKEN","APNS_"+reason,null);
        if (status==400 && "BadDeviceToken".equals(reason)) return new Result("BLOCKED","APNS_BAD_DEVICE_TOKEN",null);
        if (status==403 && "ExpiredProviderToken".equals(reason)) synchronized(this) {
            cache.apnsToken=null; cache.apnsExpiry=0;
        }
        // BadDeviceToken may mean a wrong sandbox/production environment. Topic/auth errors also preserve bindings.
        return new Result("BLOCKED","APNS_HTTP_"+status,null);
    }
    static int retryAfter(HttpResponse<?> response) {
        String value=response.headers().firstValue("Retry-After").orElse("");
        try { return (int)Math.max(0,Math.min(86400,Long.parseLong(value))); }
        catch (NumberFormatException ignored) {
            try { return (int)Math.max(0,Math.min(86400,Duration.between(Instant.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME)).getSeconds())); }
            catch (Exception invalid) { return 0; }
        }
    }
    static String message(String language) {
        String locale=language==null?"en":language.toLowerCase(java.util.Locale.ROOT);
        if (locale.startsWith("zh")) return "您有一条新通知，请打开 UVEL 查看。";
        if (locale.startsWith("vi")) return "Bạn có thông báo mới. Mở UVEL để xem.";
        return "You have a new notification. Open UVEL to view it.";
    }
    private static final class AuthFailure extends Exception {
        final Result result; AuthFailure(Result result) { this.result=result; }
    }
    /** Cancel oversized responses while receiving; the request timeout covers body completion. */
    static final class LimitedResponse implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> complete=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return complete; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value; value.request(Long.MAX_VALUE); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer:buffers) {
                if (bytes.size()+buffer.remaining()>16384) { subscription.cancel(); complete.completeExceptionally(new java.io.IOException("PROVIDER_RESPONSE_TOO_LARGE")); return; }
                byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
        }
        public void onError(Throwable error) { complete.completeExceptionally(error); }
        public void onComplete() { complete.complete(bytes.toByteArray()); }
    }
}
