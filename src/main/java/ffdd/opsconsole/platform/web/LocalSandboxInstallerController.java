package ffdd.opsconsole.platform.web;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Server-accessible placeholder resource for the explicit local-sandbox config fixture. */
@RestController
@Profile("local-sandbox")
public class LocalSandboxInstallerController {
    @GetMapping(value = "/local-sandbox/app/{file}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String file) {
        if (!"NexGrid-sandbox.apk".equals(file)) return ResponseEntity.notFound().build();
        byte[] body = "NexGrid local-sandbox installer placeholder; source=mock\n".getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"NexGrid-sandbox.apk\"")
                .body(body);
    }
}
