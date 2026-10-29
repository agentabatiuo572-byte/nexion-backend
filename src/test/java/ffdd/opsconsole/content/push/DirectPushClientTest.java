package ffdd.opsconsole.content.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DirectPushClientTest {
    final ObjectMapper json=new ObjectMapper();
    final MockEnvironment env=new MockEnvironment();
    final HttpClient http=mock(HttpClient.class);
    final DirectPushClient provider=new DirectPushClient(json,env,http);
    @TempDir Path files;
    final String token="fcm:"+"x".repeat(150);

    KeyPair fcm() throws Exception {
        env.setProperty("native-push.enabled","true");
        env.setProperty("native-push.fcm.project-id","uvel-test");
        var gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); var key=gen.generateKeyPair();
        Path file=files.resolve("service-account.json");
        Files.writeString(file,json.writeValueAsString(Map.of("type","service_account","project_id","uvel-test",
                "client_email","push@uvel-test.iam.gserviceaccount.com","private_key",pem(key.getPrivate()))));
        env.setProperty("native-push.fcm.service-account-file",file.toString()); return key;
    }
    KeyPair apns() throws Exception {
        env.setProperty("native-push.enabled","true");
        env.setProperty("native-push.apns.key-id","ABCDE12345"); env.setProperty("native-push.apns.team-id","FGHIJ67890");
        env.setProperty("native-push.apns.topic","com.uvel.mobile"); env.setProperty("native-push.apns.environment","sandbox");
        var gen=KeyPairGenerator.getInstance("EC"); gen.initialize(new ECGenParameterSpec("secp256r1")); var key=gen.generateKeyPair();
        Path file=files.resolve("AuthKey.p8"); Files.writeString(file,pem(key.getPrivate()));
        env.setProperty("native-push.apns.private-key-file",file.toString()); return key;
    }
    @Test void disabledBadConfigMismatchedProjectAndLegacyProviderFailClosedWithoutHttp() throws Exception {
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("BLOCKED");
        env.setProperty("native-push.enabled","true"); assertThat(provider.configurationState("APNS")).isEqualTo("MISSING_OR_INVALID_CONFIG");
        fcm(); env.setProperty("native-push.fcm.project-id","different-project");
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("BLOCKED");
        assertThat(provider.send(1,2,"UNIPUSH","cid123456","en").status()).isEqualTo("BLOCKED");
        apns(); env.setProperty("native-push.apns.environment","");
        assertThat(provider.send(1,2,"APNS","a".repeat(64),"en").status()).isEqualTo("BLOCKED"); verifyNoInteractions(http);
    }
    @Test void fcmOauthJwtExactScopeRealProjectReceiptGenericPayloadAndTokenCache() throws Exception {
        var key=fcm(); List<HttpRequest> calls=new ArrayList<>();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            HttpRequest request=inv.getArgument(0); calls.add(request); String body=body(request);
            if (request.uri().getHost().equals("oauth2.googleapis.com")) {
                String jwt=java.net.URLDecoder.decode(body.substring(body.indexOf("&assertion=")+11),StandardCharsets.UTF_8);
                verifyJwt(jwt,key.getPublic(),"SHA256withRSA");
                var claims=json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
                assertThat(claims.path("scope").asText()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
                assertThat(claims.path("aud").asText()).isEqualTo("https://oauth2.googleapis.com/token");
                assertThat(claims.path("iss").asText()).isEqualTo("push@uvel-test.iam.gserviceaccount.com");
                return oauth();
            }
            assertThat(request.uri().toString()).isEqualTo("https://fcm.googleapis.com/v1/projects/uvel-test/messages:send");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer access-token-for-controlled-test");
            var message=json.readTree(body).path("message");
            assertThat(message.path("data").size()).isEqualTo(2);
            assertThat(message.path("data").path("notificationId").asText()).isEqualTo("2");
            assertThat(message.path("data").path("deliveryId").asText()).isEqualTo("1");
            assertThat(message.path("android").path("notification").path("channel_id").asText()).isEqualTo("uvel_notifications");
            assertThat(message.path("notification").path("body").asText()).isEqualTo(DirectPushClient.message("vi"));
            assertThat(body).doesNotContain("password","amount","route","support text");
            return response(200,"{\"name\":\"projects/uvel-test/messages/actual-provider-id\"}",Map.of());
        });
        assertThat(provider.send(1,2,"FCM",token,"vi").taskId()).isEqualTo("projects/uvel-test/messages/actual-provider-id");
        assertThat(provider.send(1,2,"FCM",token,"vi").status()).isEqualTo("ACCEPTED");
        assertThat(calls).hasSize(3);
    }
    @Test void apnsHttp2Es256P1363SignatureExplicitEnvironmentTopicExpiryAndAlert() throws Exception {
        var key=apns(); List<String> tokens=new ArrayList<>();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            HttpRequest request=inv.getArgument(0);
            assertThat(request.version()).contains(HttpClient.Version.HTTP_2);
            assertThat(request.uri().getHost()).isEqualTo("api.sandbox.push.apple.com");
            assertThat(request.headers().firstValue("apns-topic")).contains("com.uvel.mobile");
            assertThat(request.headers().firstValue("apns-push-type")).contains("alert");
            long expiry=Long.parseLong(request.headers().firstValue("apns-expiration").orElseThrow());
            assertThat(expiry-java.time.Instant.now().getEpochSecond()).isBetween(3590L,3600L);
            String jwt=request.headers().firstValue("authorization").orElseThrow().substring(7); tokens.add(jwt);
            verifyJwt(jwt,key.getPublic(),"SHA256withECDSAinP1363Format");
            assertThat(Base64.getUrlDecoder().decode(jwt.split("\\.")[2])).hasSize(64);
            var header=json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
            assertThat(header.path("kid").asText()).isEqualTo("ABCDE12345");
            var claims=json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
            assertThat(claims.path("iss").asText()).isEqualTo("FGHIJ67890");
            var data=json.readTree(body(request));
            assertThat(data.path("notificationId").asText()).isEqualTo("2");
            assertThat(data.path("aps").path("alert").path("body").asText()).isEqualTo(DirectPushClient.message("zh"));
            return response(200,"",Map.of("apns-id","01234567-1234-1234-1234-012345678901"));
        });
        assertThat(provider.send(1,2,"APNS","a".repeat(64),"zh").status()).isEqualTo("ACCEPTED");
        assertThat(provider.send(1,2,"APNS","a".repeat(64),"zh").status()).isEqualTo("ACCEPTED");
        assertThat(tokens.get(1)).isEqualTo(tokens.get(0));
    }
    @Test void fcmClassifiesExplicitTokenFailureTransientAuthConfigAmbiguityAndRetryAfter() throws Exception {
        fcm(); var next=new java.util.concurrent.atomic.AtomicReference<>(response(404,"{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}",Map.of()));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv ->
                ((HttpRequest)inv.getArgument(0)).uri().getHost().equals("oauth2.googleapis.com")?oauth():next.get());
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("INVALID_TOKEN");
        next.set(response(429,"not json",Map.of("Retry-After","1200")));
        var result=provider.send(1,2,"FCM",token,"en"); assertThat(result.status()).isEqualTo("RETRY"); assertThat(result.retryAfterSeconds()).isEqualTo(1200);
        next.set(response(400,"{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}",Map.of()));
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("BLOCKED");
        next.set(response(403,"{}",Map.of())); assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("BLOCKED");
        next.set(response(200,"{\"name\":\"projects/wrong/messages/id\"}",Map.of()));
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("UNKNOWN");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("ambiguous"));
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("UNKNOWN");
    }
    @Test void apnsDoesNotRetireTokensForTopicAuthOrMalformedSuccess() throws Exception {
        apns(); var next=new java.util.concurrent.atomic.AtomicReference<>(response(400,"{\"reason\":\"BadTopic\"}",Map.of()));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> next.get());
        for (String reason:List.of("BadTopic","DeviceTokenNotForTopic","InvalidProviderToken","BadDeviceToken")) {
            next.set(response(400,"{\"reason\":\""+reason+"\"}",Map.of()));
            assertThat(provider.send(1,2,"APNS","a".repeat(64),"en").status()).isEqualTo("BLOCKED");
        }
        next.set(response(410,"{\"reason\":\"Unregistered\"}",Map.of()));
        assertThat(provider.send(1,2,"APNS","a".repeat(64),"en").status()).isEqualTo("INVALID_TOKEN");
        next.set(response(503,"",Map.of("Retry-After","500000")));
        assertThat(provider.send(1,2,"APNS","a".repeat(64),"en").retryAfterSeconds()).isEqualTo(86400);
        next.set(response(200,"",Map.of())); assertThat(provider.send(1,2,"APNS","a".repeat(64),"en").status()).isEqualTo("UNKNOWN");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("response lost"));
        assertThat(provider.send(1,2,"APNS","a".repeat(64),"en").status()).isEqualTo("UNKNOWN");
    }
    @Test void expiredApnsProviderJwtIsReplacedOnBlockedRecoveryAndReusedAfterAcceptance() throws Exception {
        apns(); List<String> auth=new ArrayList<>();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            HttpRequest request=inv.getArgument(0); auth.add(request.headers().firstValue("authorization").orElseThrow());
            return auth.size()==1?response(403,"{\"reason\":\"ExpiredProviderToken\"}",Map.of())
                    :response(200,"",Map.of("apns-id","01234567-1234-1234-1234-012345678901"));
        });
        assertThat(provider.send(1,2,"APNS","ab".repeat(16),"en").status()).isEqualTo("BLOCKED");
        assertThat(provider.send(1,2,"APNS","ab".repeat(64),"en").status()).isEqualTo("ACCEPTED");
        assertThat(provider.send(1,2,"APNS","ab".repeat(64),"en").status()).isEqualTo("ACCEPTED");
        assertThat(auth.get(1)).isNotEqualTo(auth.get(0)); assertThat(auth.get(2)).isEqualTo(auth.get(1));
    }
    @Test void oauthFailureIsNeverNativeAcceptanceAndTransientPreflightMayRetry() throws Exception {
        fcm(); var denied=response(403,"{}",Map.of());
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(denied);
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("BLOCKED");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("oauth offline"));
        assertThat(provider.send(1,2,"FCM",token,"en").status()).isEqualTo("RETRY");
    }
    @Test void oversizedResponseIsBoundedAndDateRetryAfterSupported() {
        var body=new DirectPushClient.LimitedResponse(); var subscription=mock(Flow.Subscription.class); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[16385]))); verify(subscription).cancel();
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
        String future=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(10).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        assertThat(DirectPushClient.retryAfter(response(429,"{}",Map.of("Retry-After",future)))).isBetween(590,600);
    }
    private HttpResponse<byte[]> oauth() { return response(200,"{\"access_token\":\"access-token-for-controlled-test\",\"token_type\":\"Bearer\",\"expires_in\":3600}",Map.of()); }
    private HttpResponse<byte[]> response(int status,String body,Map<String,String> headers) {
        HttpResponse<byte[]> result=mock(HttpResponse.class); when(result.statusCode()).thenReturn(status);
        when(result.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8)); when(result.version()).thenReturn(HttpClient.Version.HTTP_2);
        Map<String,List<String>> values=new HashMap<>(); headers.forEach((k,v)->values.put(k,List.of(v)));
        when(result.headers()).thenReturn(HttpHeaders.of(values,(k,v)->true)); return result;
    }
    private static String pem(PrivateKey key) { return "-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(key.getEncoded())+"\n-----END PRIVATE KEY-----"; }
    private static void verifyJwt(String jwt,PublicKey key,String algorithm) throws Exception {
        String[] parts=jwt.split("\\."); Signature verifier=Signature.getInstance(algorithm); verifier.initVerify(key);
        verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
    private static String body(HttpRequest request) {
        var result=new StringBuilder(); request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { result.append(StandardCharsets.UTF_8.decode(b)); }
            public void onError(Throwable t) { throw new AssertionError(t); }
            public void onComplete() { }
        }); return result.toString();
    }
}
