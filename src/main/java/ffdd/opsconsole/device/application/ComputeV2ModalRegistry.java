package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import java.util.*;
import org.springframework.core.env.Environment;

/** Operator-installed, pinned model capabilities. Product labels cannot grant an executor capability. */
final class ComputeV2ModalRegistry {
    record Model(String taskClass,String runtimeKey,String modelName,String revision) { }
    record Entry(String taskClass,String runtimeKey,String modelName,String modelRevision,String modelDigest,
            String deviceKind,boolean ready,JsonNode workload) { }
    private static final String SMOL="HuggingFaceTB/SmolLM2-135M-Instruct";
    private static final String SMOL_REV="12fd25f77366fa6b3b4b768ec3050bf629380bac";
    private static final String BK="nota-ai/bk-sdm-tiny";
    private static final String BK_REV="0364108e53b7f7f4d2585e817a0b7a83dc261cfa";
    private static final Map<String,Model> SUPPORTED=Map.of(
            "bk-sdm-tiny-image-v1",new Model("IG","bk-sdm-tiny-image-v1",BK,BK_REV),
            "bk-sdm-tiny-video-v1",new Model("VG","bk-sdm-tiny-video-v1",BK,BK_REV),
            "ll-smollm2-135m-instruct-v1",new Model("LL","ll-smollm2-135m-instruct-v1",SMOL,SMOL_REV),
            "ft-smollm2-135m-lora-v1",new Model("FT","ft-smollm2-135m-lora-v1",SMOL,SMOL_REV),
            "sp-whisper-tiny-v1",new Model("SP","sp-whisper-tiny-v1","openai/whisper-tiny","169d4a4341b33bc18d8881c4b69c2e104e1cc0af"),
            PhoneComputeV2Access.RUNTIME,new Model("EM",PhoneComputeV2Access.RUNTIME,PhoneComputeV2Access.MODEL,PhoneComputeV2Access.REVISION));
    private final ObjectMapper json;
    private final Map<String,Entry> entries;

    ComputeV2ModalRegistry(Environment environment,ObjectMapper json) {
        this.json=json;
        String configured=environment.getProperty("nexion.compute-v2.modal-runtime-registry-json","[]");
        if(configured.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072) throw invalid();
        var installed=new HashMap<String,Entry>();
        try {
            JsonNode values=read(configured);
            if(values==null || !values.isArray() || values.size()>6) throw invalid();
            Set<String> fields=Set.of("taskClass","runtimeKey","modelName","modelRevision","modelDigest","deviceKind","ready","workload");
            for(JsonNode value:values) {
                if(!fields(value).equals(fields) || !value.path("ready").isBoolean()) throw invalid();
                Entry entry=json.treeToValue(value,Entry.class);
                requireKnown(entry.taskClass(),entry.runtimeKey(),entry.modelName(),entry.modelRevision());
                if(!(PhoneComputeV2Access.RUNTIME.equals(entry.runtimeKey())?"PHONE":"CLOUD").equals(entry.deviceKind()))throw invalid();
                if(entry.modelDigest()==null || !entry.modelDigest().matches("sha256:[a-f0-9]{64}")) throw invalid();
                validateBaseWorkload(entry.taskClass(),entry.workload());
                if(installed.putIfAbsent(entry.runtimeKey(),entry)!=null) throw invalid();
            }
        } catch(java.io.IOException malformed) { throw invalid(); }
        if(installed.values().stream().anyMatch(Entry::ready)
                && !environment.getProperty("nexion.compute-v2.modal-validator-token","").matches("[A-Za-z0-9_-]{32,128}"))
            throw new IllegalStateException("COMPUTE_V2_MODAL_VALIDATOR_NOT_CONFIGURED");
        entries=Map.copyOf(installed);
    }

    Optional<Entry> installed(String taskClass,String modelName,TaskSchedulingSettings settings,String deviceKind) {
        Entry entry=entries.get(settings.runtimeKey());
        return entry!=null && entry.ready() && entry.taskClass().equals(taskClass) && entry.modelName().equals(modelName)
                && entry.deviceKind().equals(deviceKind) && settings.runtimeKey().equals(settings.validatorKey()) ? Optional.of(entry) : Optional.empty();
    }

    Entry phoneRuntime(){Entry entry=entries.get(PhoneComputeV2Access.RUNTIME);
        if(entry==null || !entry.ready())throw new ffdd.opsconsole.shared.exception.BizException(409,"PHONE_EXECUTOR_UNAVAILABLE");return entry;}

    boolean ready(String taskClass,String modelName,String modelDigest) {
        return entries.values().stream().anyMatch(entry->entry.ready() && entry.taskClass().equals(taskClass)
                && entry.modelName().equals(modelName) && entry.modelDigest().equals(modelDigest));
    }

    JsonNode workload(Entry entry,String configured) {
        try {
            JsonNode supplied=read(configured);
            JsonNode work=supplied.isObject() && supplied.isEmpty() ? entry.workload() : supplied;
            validateBaseWorkload(entry.taskClass(),work);
            return work.deepCopy();
        } catch(java.io.IOException malformed) { throw invalid(); }
    }

    static void requireKnown(String taskClass,String runtimeKey,String modelName,String revision) {
        Model expected=SUPPORTED.get(runtimeKey);
        if(expected==null || !expected.taskClass().equals(taskClass) || !expected.modelName().equals(modelName)
                || !expected.revision().equals(revision)) throw invalid();
    }

    private static void validateBaseWorkload(String taskClass,JsonNode work) {
        validateWorkload(taskClass,work);
        if("EM".equals(taskClass))for(JsonNode row:work.get("inputIds"))
            if(row.size()>120 || row.get(0).intValue()!=101)throw invalid();
    }

    /** Same bounded workload contract as the installed Python executors, checked at the configuration boundary. */
    static void validateWorkload(String taskClass,JsonNode work) {
        if(work==null || !work.isObject() || work.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384) throw invalid();
        Set<String> actual=fields(work);
        switch(taskClass) {
            case "IG","VG" -> {
                Set<String> expected="IG".equals(taskClass)?Set.of("prompt","width","height","steps"):
                        Set.of("prompt","width","height","steps","frames","fps");
                if(!actual.equals(expected)) throw invalid();
                text(work,"prompt",1,1000);integer(work,"width",256,512);integer(work,"height",256,512);integer(work,"steps",8,32);
                if(work.get("width").intValue()%64!=0 || work.get("height").intValue()%64!=0) throw invalid();
                if("VG".equals(taskClass)){integer(work,"frames",4,8);integer(work,"fps",1,16);}
            }
            case "LL" -> {
                if(!actual.equals(Set.of("prompt","maxNewTokens"))) throw invalid();
                text(work,"prompt",1,2000);integer(work,"maxNewTokens",8,128);
            }
            case "FT" -> {
                if(!actual.equals(Set.of("trainingTexts","evaluationText","maxSequenceLength","steps","learningRate"))) throw invalid();
                JsonNode texts=work.get("trainingTexts");
                if(!texts.isArray() || texts.size()<2 || texts.size()>8) throw invalid();
                for(JsonNode item:texts)if(!item.isTextual() || item.textValue().length()<8 || item.textValue().length()>1024) throw invalid();
                text(work,"evaluationText",8,1024);
                for(JsonNode item:texts)if(item.equals(work.get("evaluationText"))) throw invalid();
                integer(work,"maxSequenceLength",32,128);integer(work,"steps",2,8);number(work,"learningRate",0.0001,0.01);
            }
            case "SP" -> {
                Set<String> base=Set.of("audioPath","audioSha256","language","maxNewTokens");
                Set<String> reference=Set.of("audioPath","audioSha256","language","maxNewTokens","referenceText","maxWordErrorRate");
                if(!actual.equals(base) && !actual.equals(reference)) throw invalid();
                text(work,"audioPath",1,256);text(work,"audioSha256",64,64);
                String path=work.get("audioPath").textValue();
                if(path.startsWith("/") || path.contains("\\") || path.contains(":") || !path.endsWith(".wav")
                        || Arrays.asList(path.split("/",-1)).contains("..")
                        || !work.get("audioSha256").textValue().matches("[a-f0-9]{64}")
                        || !"english".equals(work.path("language").asText())) throw invalid();
                integer(work,"maxNewTokens",16,128);
                if(actual.equals(reference)){text(work,"referenceText",1,4000);number(work,"maxWordErrorRate",0,0.5);}
            }
            case "EM" -> {
                if(!actual.equals(Set.of("inputIds","attentionMask","tokenTypeIds")))throw invalid();
                int length=-1;
                for(String key:List.of("inputIds","attentionMask","tokenTypeIds")) {
                    JsonNode rows=work.get(key);
                    if(!rows.isArray() || rows.size()!=2)throw invalid();
                    for(JsonNode row:rows) {
                        if(!row.isArray() || row.size()<2 || row.size()>128 || length!=-1 && row.size()!=length)throw invalid();
                        length=row.size();int nonzero=0;
                        for(JsonNode item:row) {
                            if(!item.isIntegralNumber() || !item.canConvertToInt() || item.intValue()<0
                                    || item.intValue()>("inputIds".equals(key)?30521:1))throw invalid();
                            nonzero+=item.intValue();
                        }
                        if("attentionMask".equals(key) && nonzero<2)throw invalid();
                    }
                }
            }
            default -> throw invalid();
        }
    }

    private JsonNode read(String value) throws java.io.IOException {
        return json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value);
    }
    private static Set<String> fields(JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    private static void text(JsonNode value,String key,int min,int max){JsonNode node=value.path(key);
        if(!node.isTextual() || node.textValue().isBlank() || node.textValue().length()<min || node.textValue().length()>max)throw invalid();}
    private static void integer(JsonNode value,String key,int min,int max){JsonNode node=value.path(key);
        if(!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue()<min || node.intValue()>max)throw invalid();}
    private static void number(JsonNode value,String key,double min,double max){JsonNode node=value.path(key);
        if(!node.isNumber() || !Double.isFinite(node.doubleValue()) || node.doubleValue()<min || node.doubleValue()>max)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("COMPUTE_V2_MODAL_REGISTRY_OR_WORKLOAD_INVALID");}
}
