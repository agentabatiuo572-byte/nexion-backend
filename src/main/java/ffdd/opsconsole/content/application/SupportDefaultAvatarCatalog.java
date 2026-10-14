package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import org.springframework.web.multipart.MultipartFile;

/** Local reviewed synthetic portraits. A catalog failure never fabricates a photograph. */
public class SupportDefaultAvatarCatalog {
    private static final String ROOT="support/default-avatars/";
    private final ClassLoader resources;
    private final SecureRandom random=new SecureRandom();
    public SupportDefaultAvatarCatalog(){this(SupportDefaultAvatarCatalog.class.getClassLoader());}
    SupportDefaultAvatarCatalog(ClassLoader resources){this.resources=resources;}

    public static String gender(String value) {
        if(value==null)return "UNSPECIFIED";
        if(!Set.of("MALE","FEMALE","UNSPECIFIED").contains(value))throw new BizException(422,"AVATAR_GENDER_INVALID");
        return value;
    }

    public Optional<Selection> select(String gender,SupportAttachmentPolicy.View limits) {
        String pool=gender(gender);
        if(!limits.available())throw new BizException(422,"AVATAR_POLICY_UNCONFIGURED");
        try(InputStream manifest=resources.getResourceAsStream(ROOT+"manifest.json")) {
            if(manifest==null)throw new IOException("Manifest missing");
            byte[] json=manifest.readNBytes(256*1024+1);
            if(json.length>256*1024)throw new IOException("Manifest too large");
            var root=new ObjectMapper().readTree(json);
            if(root.path("schemaVersion").asInt()!=1 || !"FICTIONAL_SYNTHETIC_ADULT_PORTRAITS".equals(root.path("subjectKind").asText())
                    || !root.path("entries").isArray())throw new IOException("Manifest untrusted");
            var candidates=new ArrayList<Selection>();var ids=new HashSet<String>();var hashes=new HashSet<String>();
            for(var entry:root.path("entries")) {
                String id=entry.path("poolEntryId").asText(),file=entry.path("file").asText(),hash=entry.path("sha256").asText(),kind=entry.path("pool").asText();
                if(!entry.path("reviewed").isBoolean() || !entry.path("reviewed").asBoolean() || !"PASS".equals(entry.path("review").path("visual").asText())
                        || !Set.of("MALE","FEMALE").contains(kind) || !file.matches("[a-z0-9-]+\\.(png|jpg|jpeg)")
                        || !id.matches("[a-z0-9-]{1,64}") || !hash.matches("[a-f0-9]{64}")
                        || !"SOURCE.md".equals(entry.path("source").asText()) || !ids.add(id) || !hashes.add(hash))throw new IOException("Entry untrusted");
                try(InputStream input=resources.getResourceAsStream(ROOT+file)) {
                    if(input==null)throw new IOException("Portrait missing: "+id);
                    byte[] bytes=input.readNBytes(Math.toIntExact(limits.maxBytes())+1);
                    if(bytes.length!=entry.path("bytes").asLong() || !hash.equals(sha256(bytes)))throw new IOException("Portrait integrity failed: "+id);
                    var encoded=SupportAttachmentService.decode(new ImageFile(file,bytes),limits);
                    if(encoded.width()!=entry.path("width").asInt() || encoded.height()!=entry.path("height").asInt())throw new IOException("Portrait dimensions failed: "+id);
                    if("UNSPECIFIED".equals(pool) || pool.equals(kind))candidates.add(new Selection(id,hash,encoded));
                }
            }
            if(candidates.isEmpty())throw new IOException("Portrait pool empty");
            try(InputStream source=resources.getResourceAsStream(ROOT+"SOURCE.md")){if(source==null)throw new IOException("Source record missing");}
            return Optional.of(candidates.get(random.nextInt(candidates.size())));
        } catch(IOException|BizException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Default avatar catalog unavailable",ex);
            return Optional.empty();
        }
    }

    static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public record Selection(String poolEntryId,String sourceHash,SupportAttachmentService.Encoded image) {}
    record ImageFile(String name,byte[] bytes) implements MultipartFile {
        public String getName(){return "file";} public String getOriginalFilename(){return name;}
        public String getContentType(){return name.endsWith(".png")?"image/png":"image/jpeg";} public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;} public byte[] getBytes(){return bytes.clone();}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File target)throws IOException{Files.write(target.toPath(),bytes);}
    }
}
