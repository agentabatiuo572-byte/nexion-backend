package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only exact mutation receipts. No runtime authority is granted by this source. */
public final class SharedMutationJournal {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SCOPE = List.of("candidate", "windowId", "resourceIdentity");
    private static final String ROLE_MAPPER = "ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper";
    private SharedMutationJournal() {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    private static String env(String key) { return Objects.requireNonNull(System.getenv(key), key); }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static Map<String,Object> reference(Path path) throws Exception {
        return Map.of("path", path.toAbsolutePath().toString(), "sha256", sha(path));
    }
    private static JsonNode context() throws Exception {
        Path path = Path.of(env("CS_ENHANCE_ACTOR_CONTEXT")).toAbsolutePath();
        require(sha(path).equals(env("CS_ENHANCE_ACTOR_CONTEXT_SHA256")), "Actor context bytes changed");
        JsonNode context = JSON.readTree(path.toFile());
        for (String key : SCOPE) require(context.hasNonNull(key), "Context scope missing: " + key);
        require(context.path("purpose").asText().equals("BUSINESS_PHASE"), "Admitted business phase context required");
        var nativeNames=Map.of("taskId","WORKFLOW_TASK_ID","stepId","WORKFLOW_STEP_ID","checkId","WORKFLOW_CHECK_ID","runId","WORKFLOW_RUN_ID","repo","WORKFLOW_REPO","snapshotHash","WORKFLOW_SNAPSHOT_HASH");
        for (var entry:nativeNames.entrySet()) require(context.path("identity").path(entry.getKey()).asText().equals(env(entry.getValue())),"Raw Native identity mismatch: "+entry.getKey());
        Path baseline=Path.of(context.path("phaseSharedBefore").path("path").asText());
        require(sha(baseline).equals(context.path("phaseSharedBefore").path("sha256").asText()), "Pre-Spring complete baseline missing or changed");
        require(Instant.now().isBefore(Instant.parse(context.path("hardDeadline").asText())), "Hard cleanup deadline expired");
        return context;
    }
    private static void durable(Path path, Object value) throws Exception {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.createDirectories(path.getParent());
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        require(Arrays.equals(bytes, Files.readAllBytes(path)), "Durable mutation evidence readback failed");
    }
    private static Map<String,String> row(JdbcTemplate jdbc, String table, String selector, Object value, boolean lock) {
        require(List.of("nx_admin_role", "nx_admin_role_permission", "nx_support_rules").contains(table), "Unscoped table");
        require(selector.equals("id") || (table.equals("nx_admin_role") && selector.equals("role_code")), "Unscoped selector");
        var rows = jdbc.query("SELECT * FROM " + table + " WHERE " + selector + "=?" + (lock ? " FOR UPDATE" : ""),
                (rs, ordinal) -> {
                    var result = new LinkedHashMap<String,String>();
                    for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) result.put(rs.getMetaData().getColumnName(i), rs.getString(i));
                    return result;
                }, value);
        require(rows.size()==1, "Exact existing original row required: " + table + "/" + value);
        return rows.get(0);
    }
    private static Map<String,Object> databaseTime(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6) AS `utc_time`, NOW(6) AS session_time, @@session.time_zone AS session_zone, CONNECTION_ID() AS connection_id", (rs, ordinal) -> {
            var result = new LinkedHashMap<String,Object>();
            result.put("utc", rs.getString("utc_time")); result.put("session", rs.getString("session_time"));
            result.put("sessionZone", rs.getString("session_zone")); result.put("connectionId", rs.getString("connection_id"));
            return result;
        });
    }
    private static void plainPath(Path path,boolean directory)throws Exception{
        Path absolute=path.toAbsolutePath().normalize();
        var attributes=Files.readAttributes(absolute,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        require(!attributes.isSymbolicLink()&&!attributes.isOther()&&(directory?attributes.isDirectory():attributes.isRegularFile())&&absolute.toRealPath().equals(absolute),"Plain non-reparse evidence path required: "+path.getFileName());
    }
    private static boolean directoryPresent(Path path)throws Exception{
        try{Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);}
        catch(java.nio.file.NoSuchFileException absent){return false;}
        plainPath(path,true);return true;
    }
    private static void creatorDirectories(Path phase)throws Exception{
        plainPath(phase,true);
        for(String name:List.of("fixture-actors","fixture-actor-intents","fixture-actor-outcomes")){
            Path folder=phase.resolve(name);if(!directoryPresent(folder))continue;
            plainPath(folder,true);try(var files=Files.list(folder)){files.toList();}
        }
    }
    private static String namedOperation(Path path){var matcher=java.util.regex.Pattern.compile("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:[.-].*)$").matcher(path.getFileName().toString().toLowerCase(java.util.Locale.ROOT));return matcher.matches()?matcher.group(1):"";}
    private static String namedActorId(Path path){var matcher=java.util.regex.Pattern.compile("-([0-9]+)\\.json(?:\\.|$)").matcher(path.getFileName().toString());return matcher.find()?new java.math.BigInteger(matcher.group(1)).toString():"";}
    private static JsonNode tryEvidenceBody(Path path){try{plainPath(path,false);return JSON.readTree(path.toFile());}catch(Exception unreadable){return null;}}
    private static void uniqueCreator(Path selected,JsonNode proof)throws Exception{
        selected=selected.toAbsolutePath().normalize();String operation=proof.path("operationId").asText(),id=proof.path("adminId").asText();
        require(operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")&&selected.getFileName().toString().equals(operation+"-"+id+".json"),"Canonical exact creator operation/path required");
        Path directory=selected.getParent().getParent(),intentPath=directory.resolve("fixture-actor-intents").resolve(operation+".json");
        creatorDirectories(directory);
        require(Path.of(proof.path("intent").path("path").asText()).toAbsolutePath().normalize().equals(intentPath),"Exact creation intent path required");
        try(var files=Files.list(selected.getParent())){for(Path file:files.toList()){
            JsonNode body=tryEvidenceBody(file);boolean claims=operation.equals(namedOperation(file))||id.equals(namedActorId(file));
            if(body!=null)claims=claims||operation.equals(body.path("operationId").asText())||id.equals(body.path("adminId").asText())||id.equals(body.path("actor").path("id").asText())||id.equals(body.path("create").path("committedReadback").path("id").asText());
            if(claims)require(file.toAbsolutePath().normalize().equals(selected)&&body!=null&&body.equals(proof),"AMBIGUOUS_CREATOR_OPERATION_OR_ID "+operation+"/"+id);
        }}
        for(String folder:List.of("fixture-actor-outcomes","fixture-actor-intents")){Path dir=directory.resolve(folder);if(!directoryPresent(dir))continue;try(var files=Files.list(dir)){for(Path file:files.toList()){
            JsonNode body=tryEvidenceBody(file);boolean claims=operation.equals(namedOperation(file))||(body!=null&&operation.equals(body.path("operationId").asText()));if(!claims)continue;
            require(folder.equals("fixture-actor-intents")&&file.toAbsolutePath().normalize().equals(intentPath)&&body!=null&&body.path("event").asText().equals("CREATE_INTENT")&&body.path("operationId").asText().equals(operation),"AMBIGUOUS_CREATOR_TERMINAL_OR_PARTIAL "+operation);
        }}}
    }
    private static Map<String,Object> creator(long actorId, JsonNode context) throws Exception {
        require(actorId>0 && !(context.path("identity").path("runId").asText().equals("932129b0-d190-478a-9124-62b3e18a95fd")&&List.of(4275L,4276L,4277L,4278L).contains(actorId)), "Proven current creator actor required");
        Path dir = Path.of(env("CS_ENHANCE_EVIDENCE_DIR"), "fixture-actors");
        creatorDirectories(dir.toAbsolutePath().normalize().getParent());
        var matches = new ArrayList<Path>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode proof = tryEvidenceBody(file);if(proof==null){require(!Long.toString(actorId).equals(namedActorId(file)),"Partial creator claims exact actor ID");continue;}
                if (!Long.toString(actorId).equals(proof.path("adminId").asText())) continue;
                require(proof.path("schemaVersion").asInt()==3 && proof.path("event").asText().equals("CREATED"), "Creator must be durable CREATED event");
                for (String key : SCOPE) require(proof.path(key).equals(context.path(key)), "Creator scope mismatch: " + key);
                require(proof.path("runId").asText().equals(context.path("identity").path("runId").asText()) && proof.path("snapshotHash").asText().equals(context.path("identity").path("snapshotHash").asText()), "Creator Native scope mismatch");
                require(proof.path("create").path("successfulExactResponse").asBoolean(false) && proof.path("create").path("committedReadback").isObject(), "Creator response/commit missing");
                require(List.of("COMMIT_RETURNED","SUCCESSFUL_HTTP_RESPONSE_AND_COMMITTED_AUDIT").contains(proof.path("create").path("transactionOutcome").asText()), "Ambiguous actor creation");
                require(Long.toString(actorId).equals(proof.path("create").path("committedReadback").path("id").asText()),"Committed actor ID mismatch");
                matches.add(file);
            }
        }
        require(matches.size()==1, "Exactly one persisted creator body required for actor " + actorId);
        uniqueCreator(matches.get(0),JSON.readTree(matches.get(0).toFile()));
        var result = new LinkedHashMap<String,Object>(reference(matches.get(0)));
        result.put("actorId", Long.toString(actorId));
        return result;
    }
    private static Map<String,Object> source(String suite, String locator, String statement, Object[] args) throws Exception {
        require(suite.matches("[A-Za-z0-9_]+") && locator!=null && !locator.isBlank(), "Exact source location required");
        Path path = Path.of(env("WORKFLOW_REPO"), "src/test/java/ffdd/opsconsole/content/application", suite + ".java");
        var source = new LinkedHashMap<String,Object>(reference(path));
        source.put("locator", locator); source.put("statement", statement); source.put("parameters", Arrays.asList(args));
        return source;
    }
    private static Map<String,Object> envelope(JsonNode context, String run, String suite, String operationId) {
        var value = new LinkedHashMap<String,Object>(); value.put("schemaVersion", 3);
        value.put("run", run); value.put("suite", suite); value.put("operationId", operationId);
        value.put("at", Instant.now().toString()); value.put("contextPath", env("CS_ENHANCE_ACTOR_CONTEXT"));
        value.put("contextSha256", env("CS_ENHANCE_ACTOR_CONTEXT_SHA256"));
        for (String key : SCOPE) value.put(key, context.get(key));
        value.put("identity", context.path("identity")); value.put("runId",env("WORKFLOW_RUN_ID")); value.put("snapshotHash",env("WORKFLOW_SNAPSHOT_HASH"));
        for (String key : List.of("WORKFLOW_TASK_ID", "WORKFLOW_RUN_ID", "WORKFLOW_SNAPSHOT_HASH", "WORKFLOW_STEP_ID", "WORKFLOW_CHECK_ID", "WORKFLOW_REPO")) value.put(key, env(key));
        return value;
    }
    private static <T> T mutation(JdbcTemplate jdbc, String run, String suite, long actorId, String table,
            String selector, Object selectorValue, Map<String,Object> source, String kind,
            Callable<T> operation, Predicate<T> success) throws Exception {
        JsonNode ctx = context();
        if(!kind.equals("FIXTURE_PERMISSION_RESTORE")&&!kind.equals("CLEANUP_SQL"))require(Instant.now().isBefore(Instant.parse(ctx.path("businessDeadline").asText())),"New shared mutation after business deadline is forbidden");
        var record = envelope(ctx, run, suite, UUID.randomUUID().toString());
        record.put("table", table); record.put("kind", kind); record.put("source", source);
        if(table.equals("nx_support_rules"))record.put("auditTimeSource","UTC_TIMESTAMP(6)");
        if (!kind.equals("SPRING_RBAC_BOOTSTRAP")) record.put("actorProof", creator(actorId, ctx));
        else require(table.equals("nx_admin_role") && actorId==0, "Bootstrap scope mismatch");
        Path dir = Path.of(env("CS_ENHANCE_EVIDENCE_DIR"), "shared-mutations");
        Path intent = dir.resolve(record.get("operationId") + "-INTENT.json");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.setTimeout(20);
        boolean[] terminal={false};
        Callable<T> body=() -> {
            Map<String,String> before = row(jdbc, table, selector, selectorValue, true);
            record.put("rowId", before.get("id")); record.put("before", before);
            record.put("dbStarted", databaseTime(jdbc)); record.put("event", "SHARED_MUTATION_INTENT");
            durable(intent, record);
            require(TransactionSynchronizationManager.isSynchronizationActive(),"Actual transaction synchronization required");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){
                    record.put("event","SHARED_MUTATION_COMMITTED");record.put("transactionOutcome","COMMITTED");
                    record.put("commitReturned",true);record.put("commitEvidence","SPRING_AFTER_COMMIT");record.put("committedAt",Instant.now().toString());
                    record.put("at",Instant.now().toString());
                    try{record.put("intent",reference(intent));durable(dir.resolve(record.get("operationId")+"-COMMITTED.json"),record);terminal[0]=true;}
                    catch(Exception failure){throw new IllegalStateException("Committed shared mutation evidence failed",failure);}
                }
                @Override public void afterCompletion(int status){
                    if(status==STATUS_COMMITTED)return;
                    record.put("event",status==STATUS_ROLLED_BACK?"SHARED_MUTATION_ROLLED_BACK":"SHARED_MUTATION_UNRESOLVED");
                    record.put("transactionOutcome",status==STATUS_ROLLED_BACK?"ROLLED_BACK":"UNKNOWN");record.put("commitReturned",false);
                    record.put("at",Instant.now().toString());
                    try{record.put("intent",reference(intent));durable(dir.resolve(record.get("operationId")+(status==STATUS_ROLLED_BACK?"-ROLLED_BACK.json":"-UNRESOLVED.json")),record);terminal[0]=true;}
                    catch(Exception failure){throw new IllegalStateException("Shared transaction completion evidence failed",failure);}
                }
            });
            T result = operation.call(); require(success.test(result), "Mutation did not return an expected exact response");
            Map<String,String> after = row(jdbc, table, "id", before.get("id"), true);
            if(result instanceof Integer affected&&affected==0)require(before.equals(after),"Zero affected-row result cannot hide an actual full-row change");
            record.put("after", after); record.put("dbFinished", databaseTime(jdbc));
            record.put("effect",before.equals(after)?"NO_CHANGE":"CHANGED");
            if (table.equals("nx_support_rules")&&!before.equals(after)) {
                require(Long.parseLong(after.get("version"))==Long.parseLong(before.get("version"))+1, "Rule version must advance exactly once");
                require(Long.toString(actorId).equals(after.get("updated_by")), "Rule audit actor must be this proven operation actor");
                require(after.get("updated_at")!=null, "Rule audit timestamp missing");
            }
            record.put("successfulExactResponse", true);record.put("response", result==null ? null : JSON.valueToTree(result));return result;
        };
        boolean joined=TransactionSynchronizationManager.isActualTransactionActive();
        try {
            if(joined)return body.call();
            return tx.execute(status->{try{return body.call();}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException(failure);}});
        } catch (Exception failure) {
            if(joined||terminal[0])throw failure;
            record.put("event", "SHARED_MUTATION_UNRESOLVED");
            record.put("at",Instant.now().toString());
            record.put("transactionOutcome", "UNKNOWN");record.put("errorType", failure.getClass().getName());
            try { durable(dir.resolve(record.get("operationId") + "-UNRESOLVED.json"), record); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }

    public static int sql(JdbcTemplate jdbc, String run, String suite, long actorId,
            String locator, String sql, Object... args) {
        return sqlInternal(jdbc,run,suite,actorId,locator,sql,false,args);
    }
    public static int cleanupSql(JdbcTemplate jdbc,String run,String suite,long actorId,String locator,String sql,Object...args){
        return sqlInternal(jdbc,run,suite,actorId,locator,sql,true,args);
    }
    private static int sqlInternal(JdbcTemplate jdbc,String run,String suite,long actorId,String locator,String sql,boolean cleanup,Object...args){
        String normalized = sql.replaceAll("\\s+", " ").trim();
        String table; Object id;
        if (normalized.matches("(?i)UPDATE nx_support_rules SET .+ WHERE id=1")) {
            table="nx_support_rules"; id="1";
            require(normalized.contains("updated_by=?") && normalized.contains("updated_at=UTC_TIMESTAMP(6)"), "Direct rules SQL must assign actual actor and DB audit time");
        } else if (normalized.matches("(?i)UPDATE nx_admin_role_permission SET .+ WHERE id=\\?")) {
            table="nx_admin_role_permission"; require(args.length>0, "Permission ID parameter missing"); id=args[args.length-1];
        } else throw new IllegalStateException("Only exact shared rules/permission SQL is journaled");
        try { return mutation(jdbc, run, suite, actorId, table, "id", id, source(suite, locator, sql, args),
                cleanup?"CLEANUP_SQL":"FIXTURE_SQL", () -> jdbc.update(sql, args), changed -> changed==0||changed==1); }
        catch (Exception failure) { throw new IllegalStateException("Shared SQL journal failed",failure); }
    }

    public static int restorePermission(JdbcTemplate jdbc,String run,String suite,long actorId,String locator,long id){
        try{
            JsonNode ctx=context();Path dir=Path.of(env("CS_ENHANCE_EVIDENCE_DIR"),"shared-mutations");var events=new ArrayList<JsonNode>();
            Path baseline=Path.of(ctx.path("phaseSharedBefore").path("path").asText());require(sha(baseline).equals(ctx.path("phaseSharedBefore").path("sha256").asText()),"Original phase baseline changed");
            JsonNode before=JSON.readTree(baseline.toFile());var originals=new ArrayList<JsonNode>();for(JsonNode value:before.path("tables").path("nx_admin_role_permission").path("rows"))if(value.path("id").asText().equals(Long.toString(id)))originals.add(value);require(originals.size()==1,"Exactly one original phase permission row required");
            var intents=new LinkedHashMap<String,JsonNode>();var outcomes=new LinkedHashMap<String,JsonNode>();
            if(directoryPresent(dir))try(var files=Files.list(dir)){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).toList()){
                JsonNode event=JSON.readTree(file.toFile());if(!event.path("table").asText().equals("nx_admin_role_permission")||!event.path("rowId").asText().equals(Long.toString(id))||!event.path("run").asText().equals(run))continue;
                require(event.path("schemaVersion").asInt()==3&&event.path("identity").equals(ctx.path("identity"))&&event.path("contextSha256").asText().equals(env("CS_ENHANCE_ACTOR_CONTEXT_SHA256")),"Permission journal context mismatch");
                for(String key:SCOPE)require(event.path(key).equals(ctx.path(key)),"Permission journal scope mismatch");
                String operationId=event.path("operationId").asText(),eventKind=event.path("event").asText();require(!operationId.isBlank(),"Permission operation ID missing");
                if(eventKind.equals("SHARED_MUTATION_INTENT")){require(intents.put(operationId,event)==null,"Duplicate permission intent");continue;}
                require(List.of("SHARED_MUTATION_COMMITTED","SHARED_MUTATION_ROLLED_BACK").contains(eventKind)&&outcomes.put(operationId,event)==null,"Unresolved/duplicate permission outcome");
                Path intentPath=Path.of(event.path("intent").path("path").asText());require(sha(intentPath).equals(event.path("intent").path("sha256").asText()),"Permission intent bytes changed");
                JsonNode intent=JSON.readTree(intentPath.toFile());
                for(String key:List.of("operationId","identity","contextSha256","candidate","windowId","resourceIdentity","run","table","rowId","before","source","actorProof","dbStarted"))require(intent.path(key).equals(event.path(key)),"Permission immutable intent mismatch: "+key);
                require(intent.path("event").asText().equals("SHARED_MUTATION_INTENT"),"Permission outcome lacks actual intent");
                if(eventKind.equals("SHARED_MUTATION_ROLLED_BACK")){require(event.path("transactionOutcome").asText().equals("ROLLED_BACK")&&!event.path("commitReturned").asBoolean(true),"Actual permission rollback completion required");continue;}
                require(event.path("transactionOutcome").asText().equals("COMMITTED")&&event.path("commitReturned").asBoolean(false)&&event.path("commitEvidence").asText().equals("SPRING_AFTER_COMMIT")&&event.path("successfulExactResponse").asBoolean(false),"Committed permission mutation required");
                JsonNode actorReference=event.path("actorProof");long creatorId=Long.parseLong(actorReference.path("actorId").asText());require(JSON.valueToTree(creator(creatorId,ctx)).equals(actorReference),"Permission mutation creator body changed");
                JsonNode source=event.path("source");require(sha(Path.of(source.path("path").asText())).equals(source.path("sha256").asText())&&!source.path("locator").asText().isBlank()&&!source.path("statement").asText().isBlank()&&source.path("parameters").isArray(),"Exact permission source provenance required");
                require(event.path("before").path("id").asText().equals(Long.toString(id))&&event.path("after").path("id").asText().equals(Long.toString(id)),"Permission mutation PK changed");events.add(event);
            }}
            require(intents.keySet().equals(outcomes.keySet()),"Incomplete permission intent/outcome coverage");
            if(events.isEmpty()){
                require(originals.get(0).equals(JSON.valueToTree(row(jdbc,"nx_admin_role_permission","id",id,false))),"Unchanged permission without journal differs from original full row");return 0;
            }
            events.sort(java.util.Comparator.comparing(SharedMutationJournal::operationTime));JsonNode original=events.get(0).path("before"),last=events.get(events.size()-1).path("after");
            require(original.equals(originals.get(0)),"Permission first owned before differs from pre-Spring original");
            JsonNode cursor=original,previous=null;for(JsonNode event:events){if(previous!=null&&operationTime(previous).equals(operationTime(event)))require(previous.path("before").equals(previous.path("after"))&&event.path("before").equals(event.path("after")),"Permission changed operations have ambiguous database order");require(event.path("before").equals(cursor),"Permission mutation chain is incomplete");cursor=event.path("after");previous=event;}
            var columns=new ArrayList<String>();original.fieldNames().forEachRemaining(columns::add);
            String set=String.join(",",columns.stream().map(c->c+"=?").toList()),where=String.join(" AND ",columns.stream().map(c->c+" <=> ?").toList());
            var parameters=new ArrayList<Object>();for(JsonNode values:List.of(original,last))for(String column:columns)parameters.add(values.path(column).isNull()?null:values.path(column).asText());
            String statement="UPDATE nx_admin_role_permission SET "+set+" WHERE "+where;
            return mutation(jdbc,run,suite,actorId,"nx_admin_role_permission","id",id,source(suite,locator,statement,parameters.toArray()),"FIXTURE_PERMISSION_RESTORE",()->{
                require(JSON.valueToTree(row(jdbc,"nx_admin_role_permission","id",id,true)).equals(last),"Permission current full row changed outside owned operation");
                int changed=jdbc.update(statement,parameters.toArray());require(JSON.valueToTree(row(jdbc,"nx_admin_role_permission","id",id,true)).equals(original),"Permission complete original row not restored");return changed;
            },changed->changed==0||changed==1);
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Exact permission restoration failed",failure);}
    }

    private static LocalDateTime operationTime(JsonNode event){return LocalDateTime.parse(event.path("dbStarted").path("utc").asText().replace(' ','T'));}

    /** Register as a static @Bean in each shared IsolatedConfiguration, before the initializer is constructed. */
    public static BeanPostProcessor bootstrapJournal(DataSource dataSource) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                Class<?> mapper,rulesMapper;
                try { mapper=Class.forName(ROLE_MAPPER);rulesMapper=Class.forName("ffdd.opsconsole.content.mapper.SupportBindingMapper"); } catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
                if(rulesMapper.isInstance(bean))return Proxy.newProxyInstance(bean.getClass().getClassLoader(),new Class<?>[]{rulesMapper},(proxy,method,args)->{
                    if(!method.getName().equals("updateRules")){try{return method.invoke(bean,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
                    Path path=Path.of(env("WORKFLOW_REPO"),"src/main/java/ffdd/opsconsole/content/mapper/SupportBindingMapper.java");
                    var source=new LinkedHashMap<String,Object>(reference(path));source.put("locator","ffdd.opsconsole.content.mapper.SupportBindingMapper.updateRules");source.put("statement","SupportBindingMapper.updateRules");source.put("parameters",Arrays.asList(args));
                    return mutation(new JdbcTemplate(dataSource),"RULE_MAPPER","SupportBindingMapper",((Number)args[6]).longValue(),"nx_support_rules","id","1",source,"RULE_MAPPER",()->{
                        try{return (Integer)method.invoke(bean,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(failure.getCause());}
                    },changed->changed==0||changed==1);
                });
                if (!mapper.isInstance(bean)) return bean;
                return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[]{mapper}, (proxy, method, args) -> {
                    if (!method.getName().equals("ensureRole")) {
                        try { return method.invoke(bean,args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    }
                    Path mapperSource=Path.of(env("WORKFLOW_REPO"),"src/main/java/ffdd/opsconsole/auth/mapper/AdminRolePermissionMapper.java");
                    Path initializer=Path.of(env("WORKFLOW_REPO"),"src/main/java/ffdd/opsconsole/auth/application/AdminRbacBaselineInitializer.java");
                    var source=new LinkedHashMap<String,Object>(reference(mapperSource));
                    source.put("locator",ROLE_MAPPER+".ensureRole"); source.put("initializer",reference(initializer));
                    source.put("statement","AdminRolePermissionMapper.ensureRole INSERT ON DUPLICATE KEY UPDATE");
                    source.put("parameters",Arrays.asList(args));
                    return mutation(new JdbcTemplate(dataSource), "SPRING_BOOTSTRAP", "AdminRbacBaselineInitializer", 0,
                            "nx_admin_role", "role_code", args[0], source, "SPRING_RBAC_BOOTSTRAP", () -> {
                                try { return (Integer)method.invoke(bean,args); }
                                catch (InvocationTargetException failure) { throw new IllegalStateException("Original bootstrap mapper failed",failure.getCause()); }
                            }, changed -> changed==1 || changed==2 || changed==0);
                });
            }
        };
    }
}
