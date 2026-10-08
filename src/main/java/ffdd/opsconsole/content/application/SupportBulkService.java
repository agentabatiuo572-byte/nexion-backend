package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportBulk;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportBulkMapper;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationService
@RequiredArgsConstructor
public class SupportBulkService {
    private final SupportBulkMapper mapper;
    private final SupportBindingMapper bindings;
    private final SupportOwnershipService ownership;
    private final SupportActivityService activity;
    private final FinanceSupportReadService finance;
    private final SupportAttachmentService attachments;
    private final SupportHumanMessageService humanMessages;
    private final OpsConversationService conversations;
    private final AdminIdempotencyService idempotency;
    private final ProductionSupportPathGuard production;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper json;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    @Value("${nexion.support.bulk.preview-ttl-seconds:300}") private long previewTtlSeconds;

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SupportBulk.Preview preview(SupportBulkRequest.Preview request) {
        production.requireOpsWriteAllowed();
        Long actor=authenticatedActor();
        if(request==null || !Set.of("EXPLICIT","SINGLE","PAGE","CROSS_PAGE","ALL_FILTERED").contains(request.selectionMode()==null?"":request.selectionMode()))
            throw invalid("SUPPORT_BULK_SELECTION_INVALID");
        boolean all="ALL_FILTERED".equals(request.selectionMode());
        var ids=ids(request.customerIds());var excludedIds=ids(request.excludedIds());
        if(all && !ids.isEmpty() || !all && ids.isEmpty()) throw invalid("SUPPORT_BULK_SELECTION_INVALID");
        var filters=request.filters();validateFilters(filters);
        if(previewTtlSeconds<1 || previewTtlSeconds>3600) throw invalid("SUPPORT_BULK_PREVIEW_POLICY_INVALID");
        var coverage=activity.checkpoint();var rules=bindings.rules();
        if(rules==null || coverage==null) throw new BizException(503,"SUPPORT_BULK_SOURCE_UNAVAILABLE");
        var query=SupportWorkbenchService.query(ownership.queryScope(ReadMode.PERSONAL,null,null),null,rules,coverage);
        query.put("ids",all?null:List.copyOf(ids));
        var candidates=mapper.candidates(query);
        var accepted=new ArrayList<SupportBulk.Customer>();var excluded=new ArrayList<SupportBulk.Excluded>();
        var found=new HashSet<Long>();
        // ponytail: per-customer authoritative reads, batch financial/tag projection if measured volumes need it.
        for(var row:candidates) {
            Long customer=number(row,"customerId");found.add(customer);
            String reason=excludedIds.contains(customer)?"EXPLICITLY_EXCLUDED":exclusion(row,filters);
            if(reason==null) accepted.add(new SupportBulk.Customer(customer,number(row,"assignmentId")));
            else excluded.add(new SupportBulk.Excluded(customer,reason));
        }
        for(Long customer:ids) if(!found.contains(customer)) excluded.add(new SupportBulk.Excluded(customer,"NOT_CURRENT_CUSTOMER"));
        ownership.requireSendingActor(actor);
        String selection=UUID.randomUUID().toString();LocalDateTime now=utcNow(),expires=now.plusSeconds(previewTtlSeconds);
        mapper.insertPreview(selection,actor,request.selectionMode(),encode(filters),encode(excluded),accepted.size(),coverage.observedThroughAt(),expires);
        for(var customer:accepted) mapper.insertRecipient(selection,customer.id(),customer.expectedAssignmentId(),clientId(selection,customer.id()));
        return new SupportBulk.Preview(selection,actor,filters,List.copyOf(accepted),List.copyOf(excluded),accepted.size(),
            coverage.observedThroughAt().toInstant(ZoneOffset.UTC).toString(),expires.toInstant(ZoneOffset.UTC).toString());
    }

    public ApiResult<Map<String,Object>> create(String key,SupportBulkRequest.Create request) {
        production.requireOpsWriteAllowed();validateCreate(key,request);Long actor=authenticatedActor();
        String digest=hash(encode(request));String scope="M3_SUPPORT_BULK_CREATE:"+actor;
        var recovery=idempotency.recoveryResult(scope,key,digest,ApiResult.class);
        if(recovery.status()==AdminIdempotencyService.RecoveryStatus.MISMATCH) throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        Map<String,Object> prior=mapper.jobByCommand(actor,key.trim());
        if(prior!=null) {
            if(!digest.equals(prior.get("requestHash"))) throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            return ApiResult.ok(detail(text(prior,"id")));
        }
        if(recovery.status()==AdminIdempotencyService.RecoveryStatus.UNKNOWN) throw conflict("IDEMPOTENCY_RESULT_UNKNOWN");
        ApiResult<?> receipt=idempotency.executeRetained(scope,key,digest,ApiResult.class,()-> {
            requireOwner(requireJob(mapper.job(request.selectionId())),actor);
            // Only creation locks the frozen collection; delivery transactions remain one customer each.
            var recipients=mapper.allRecipients(request.selectionId());
            recipients.forEach(row->ownership.lockCustomer(number(row,"customerId")));
            var job=requireJob(mapper.jobForUpdate(request.selectionId()));requireOwner(job,actor);
            ownership.requireSendingActor(actor);
            if(!"DRAFT".equals(job.get("state")) || !time(job.get("expiresAt")).isAfter(utcNow()))
                throw conflict("SUPPORT_BULK_PREVIEW_EXPIRED");
            if(recipients.isEmpty()) throw invalid("SUPPORT_BULK_SELECTION_EMPTY");
            if(request.assetId()!=null) requireAsset(request.assetId(),actor);
            for(var row:recipients) {
                Long customer=number(row,"customerId");var current=mapper.conversation(customer);
                String no=current==null?null:text(current,"conversationNo");
                String operation=no==null?"CREATE":"REPLY:"+no;
                String attachment="IMAGE".equals(request.kind())?attachmentId(request.selectionId(),customer):null;
                mapper.route(request.selectionId(),customer,operation,no,attachment);
                String excluded=current==null?null:conversationExclusion(current);
                if(excluded!=null) outcome(request.selectionId(),customer,"SKIPPED","KNOWN",null,no,excluded,false,0);
            }
            if(mapper.queue(request.selectionId(),key.trim(),digest,encode(request),request.assetId())!=1)
                throw conflict("SUPPORT_BULK_VERSION_CONFLICT");
            finishLocked(request.selectionId(),false);
            record("SUPPORT_BULK_CREATED",request.selectionId(),actor,key,request.reason());
            return ApiResult.ok(Map.of("batchId",request.selectionId()));
        });
        return ApiResult.ok(detail(batchId(receipt)));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<Map<String,Object>> page(long pageNum,int pageSize) {
        SupportWorkbenchService.validatePage(pageNum,pageSize);Long actor=authenticatedActor();boolean supervisor=reader(actor);
        var query=collectionQuery(actor,supervisor);query.put("offset",(pageNum-1)*pageSize);query.put("limit",pageSize);
        var records=mapper.scopedJobs(query).stream().map(job->jobView(job,actor,query)).toList();
        return new PageResult<>(mapper.scopedJobCount(query),pageNum,pageSize,records);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(String batch) {
        // Also covers retained-command self-invocation from create/retry/recover.
        var read=new TransactionTemplate(transactions);
        read.setReadOnly(true);
        return read.execute(status-> {
            uuid(batch);Long actor=authenticatedActor();reader(actor);
            var query=objectQuery(actor);query.put("batch",batch);
            var job=requireJob(mapper.job(batch));requireReadableJob(job,actor,query);
            return jobView(job,actor,query);
        });
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<Map<String,Object>> recipients(String batch,long pageNum,int pageSize) {
        uuid(batch);SupportWorkbenchService.validatePage(pageNum,pageSize);Long actor=authenticatedActor();reader(actor);
        var query=objectQuery(actor);query.put("batch",batch);query.put("offset",(pageNum-1)*pageSize);query.put("limit",pageSize);
        var job=requireJob(mapper.job(batch));requireReadableJob(job,actor,query);
        // Never serialize request_json, which includes private content, operator metadata and attachment references.
        var rows=mapper.scopedRecipients(query).stream().map(this::recipientView).toList();
        return new PageResult<>(mapper.scopedRecipientCount(query),pageNum,pageSize,rows);
    }

    public ApiResult<Map<String,Object>> cancel(String batch,String key,SupportBulkRequest.Mutation request) {
        return mutate(batch,key,request,false);
    }
    public ApiResult<Map<String,Object>> retry(String batch,String key,SupportBulkRequest.Mutation request) {
        return mutate(batch,key,request,true);
    }

    private ApiResult<Map<String,Object>> mutate(String batch,String key,SupportBulkRequest.Mutation request,boolean retry) {
        production.requireOpsWriteAllowed();uuid(batch);validateMutation(key,request);Long actor=authenticatedActor();
        String scope="M3_SUPPORT_BULK_"+(retry?"RETRY:":"CANCEL:")+actor;
        String digest=hash(encode(Arrays.asList(batch,request)));
        ApiResult<?> receipt=idempotency.executeRetained(scope,key,digest,ApiResult.class,()-> {
            var job=requireJob(mapper.jobForUpdate(batch));requireOwner(job,actor);
            // Cancellation takes no customer/admin locks: it cannot reverse the send lock order.
            if(bindings.eligibleAgentSnapshot(actor)!=1 || mapper.writerGrantSnapshot(actor)<1)
                throw new BizException(403,"SUPPORT_WRITE_FORBIDDEN");
            if(!Objects.equals(number(job,"version"),request.expectedVersion())) throw conflict("SUPPORT_BULK_VERSION_CONFLICT");
            if("DRAFT".equals(job.get("state"))) throw conflict("SUPPORT_BULK_NOT_SUBMITTED");
            if(retry && truth(job.get("cancelRequested"))) throw conflict("SUPPORT_BULK_CANCELLED");
            for(var snapshot:mapper.allRecipients(batch)) {
                var row=mapper.recipientForUpdate(batch,number(snapshot,"customerId"));
                if("SENT".equals(row.get("state")) || "CANCELLED".equals(row.get("state"))) continue;
                if(reconcile(job,row)) continue;
                if(needsReview(row)) continue;
                if(retry) {
                    if("FAILED".equals(row.get("state")) && truth(row.get("retryable")))
                        outcome(batch,number(row,"customerId"),"PENDING","KNOWN",null,null,null,false,0);
                } else {
                    outcome(batch,number(row,"customerId"),"CANCELLED","KNOWN",null,null,"CANCELLED",false,0);
                }
            }
            mapper.transition(batch,retry?"QUEUED":"CANCELLED",!retry);
            finishLocked(batch,!retry);record(retry?"SUPPORT_BULK_RETRIED":"SUPPORT_BULK_CANCELLED",batch,actor,key,request.reason());
            return ApiResult.ok(Map.of("batchId",batch));
        });
        return ApiResult.ok(detail(batchId(receipt)));
    }

    /** Primary command recovery also resolves permanent creation facts after an UNKNOWN receipt. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> recover(String key) {
        SupportBindingService.validateCommand(key,"recover bulk result");Long actor=authenticatedActor();reader(actor);
        var job=mapper.jobByCommand(actor,key.trim());
        return job==null?Map.of("status","UNKNOWN"):Map.of("status","SUCCEEDED","result",detail(text(job,"id")));
    }

    @Scheduled(fixedDelayString="${nexion.support.bulk.scan-delay-ms:1000}")
    public void runPending() {
        if(!production.productionSupportAutomationAllowed()) return;
        for(var row:mapper.pending()) {
            try { processRecipient(text(row,"batchId"),number(row,"customerId")); }
            catch(RuntimeException ex) {
                LoggerFactory.getLogger(SupportBulkService.class).warn("Bulk recipient recovery unavailable: {}",ex.getClass().getSimpleName());
            }
        }
    }

    /** Runnable individually for isolated fixtures; no authentication context is created for the stored actor. */
    public void processRecipient(String batch,Long customer) {
        uuid(batch);SupportWorkbenchService.requireSafeId(customer);production.requireOpsWriteAllowed();
        try {
            prepareRecipient(batch,customer);
            transaction().executeWithoutResult(status-> {
                // Shared binding/message writes use the same customer mutex before every other lock.
                ownership.lockCustomer(customer);
                var job=requireJob(mapper.jobForUpdate(batch));var row=mapper.recipientForUpdate(batch,customer);
                if(row==null || !"PENDING".equals(row.get("state"))) return;
                if(reconcile(job,row)) {finishLocked(batch,truth(job.get("cancelRequested")));return;}
                if(needsReview(row)) return;
                if(truth(job.get("cancelRequested"))) {
                    outcome(batch,customer,"CANCELLED","KNOWN",null,null,"CANCELLED",false,0);finishLocked(batch,true);return;
                }
                if(!Set.of("QUEUED","RUNNING").contains(text(job,"state"))) return;
                if("QUEUED".equals(job.get("state"))) mapper.transition(batch,"RUNNING",false);
                Long actor=number(job,"actorId");
                deliverySnapshot(job,row);
                var content=decode(text(job,"contentJson"),SupportBulkRequest.Create.class);
                if(row.get("requestJson")==null) throw conflict("SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED");
                Object request=frozenRequest(row);
                if(content.assetId()!=null) attachments.materializeBulkForActor(content.assetId(),actor,customer,number(row,"expectedAssignmentId"),text(row,"attachmentId"));
                // The canonical payload is preserved across all retries, including its first actual version.
                OpsConversationService.MessageCommandResult result="CREATE".equals(row.get("operation"))
                    ?conversations.initiateWithMessageIdForActor(actor,text(row,"clientMessageId"),(ConversationInitiateRequest)request)
                    :conversations.replyWithMessageIdForActor(actor,text(row,"conversationNo"),text(row,"clientMessageId"),(ConversationReplyRequest)request);
                if(result.result().getCode()!=0 || result.messageId()==null) throw new BizException(
                    result.result().getCode()==0?409:result.result().getCode(),result.result().getMessage());
                var conversation=result.result().getData();
                outcome(batch,customer,"SENT","KNOWN",result.messageId(),conversation.conversationNo(),null,false,1);
                finishLocked(batch,false);
                OpsConversationAfterCommitPublisher.publish(events,ConversationMessageEvent.builder()
                    .conversationNo(conversation.conversationNo()).messageId(result.messageId())
                    .eventType("CREATE".equals(row.get("operation"))?ConversationMessageEvent.EventType.INITIATE:ConversationMessageEvent.EventType.MESSAGE)
                    .senderType("AGENT").senderName(conversation.ownerAgentName()).body(conversation.lastMessage()).ts(utcNow())
                    .ownerAgentId(conversation.ownerAgentId()).ownerAgentName(conversation.ownerAgentName()).build());
            });
        } catch(RuntimeException failure) {
            rememberFailure(batch,customer,failure);
        }
    }

    /** Persist the first actual delivery DTO before any send, so crashes or competing runners cannot replace it. */
    public void prepareRecipient(String batch,Long customer) {
        uuid(batch);SupportWorkbenchService.requireSafeId(customer);production.requireOpsWriteAllowed();
        transaction().executeWithoutResult(status-> {
            ownership.lockCustomer(customer);
            var job=requireJob(mapper.jobForUpdate(batch));var row=mapper.recipientForUpdate(batch,customer);
            if(row==null || !"PENDING".equals(row.get("state"))) return;
            if(reconcile(job,row)) {finishLocked(batch,truth(job.get("cancelRequested")));return;}
            if(needsReview(row)) return;
            if(truth(job.get("cancelRequested"))) {
                outcome(batch,customer,"CANCELLED","KNOWN",null,null,"CANCELLED",false,0);finishLocked(batch,true);return;
            }
            if(!Set.of("QUEUED","RUNNING").contains(text(job,"state"))) return;
            var actual=deliverySnapshot(job,row);
            if(row.get("requestJson")==null) {
                var content=decode(text(job,"contentJson"),SupportBulkRequest.Create.class);
                mapper.freezeRequest(batch,customer,encode(conversationRequest(job,row,content,actual)));
            }
            if("QUEUED".equals(job.get("state"))) mapper.transition(batch,"RUNNING",false);
        });
    }

    private Map<String,Object> deliverySnapshot(Map<String,Object> job,Map<String,Object> row) {
        Long customer=number(row,"customerId"),actor=number(job,"actorId");
        var assignment=ownership.requireWriterForActor(actor,customer,false);
        if(!Objects.equals(assignment.id(),number(row,"expectedAssignmentId"))) throw conflict("SUPPORT_ASSIGNMENT_CHANGED");
        production.requireAllowed(customer);
        if(!Boolean.TRUE.equals(mapper.maintenanceEnabled(customer))) throw conflict("SUPPORT_MAINTENANCE_STOPPED");
        var actual=mapper.conversation(customer);
        if("CREATE".equals(row.get("operation"))) {
            if(actual!=null) throw conflict("SUPPORT_BULK_CONVERSATION_CHANGED");
        } else {
            if(actual==null || !Objects.equals(text(actual,"conversationNo"),text(row,"conversationNo")))
                throw conflict("SUPPORT_BULK_CONVERSATION_CHANGED");
            String excluded=conversationExclusion(actual);if(excluded!=null) throw conflict(excluded);
        }
        return actual;
    }

    private void rememberFailure(String batch,Long customer,RuntimeException failure) {
        transaction().executeWithoutResult(status-> {
            // This recovery transaction only touches batch/results and message facts, never the customer mutex.
            var job=requireJob(mapper.jobForUpdate(batch));var row=mapper.recipientForUpdate(batch,customer);
            if(row==null || "SENT".equals(row.get("state"))) return;
            if(reconcile(job,row)) {finishLocked(batch,truth(job.get("cancelRequested")));return;}
            if(needsReview(row)) {
                finishLocked(batch,truth(job.get("cancelRequested")));return;
            }
            if("CANCELLED".equals(row.get("state")) || truth(job.get("cancelRequested"))) {
                outcome(batch,customer,"CANCELLED","KNOWN",null,null,"CANCELLED",false,0);
            } else if(failure instanceof BizException b) {
                String code=b.getMessage();boolean skipped=b.getCode()==401 || b.getCode()==403 || b.getCode()==404
                    || Set.of("SUPPORT_ASSIGNMENT_CHANGED","SUPPORT_MAINTENANCE_STOPPED","SUPPORT_AGENT_UNAVAILABLE",
                        "SUPPORT_CUSTOMER_NOT_FOUND","SUPPORT_WRITE_FORBIDDEN","SUPPORT_PRODUCTION_PATH_FORBIDDEN").contains(code);
                outcome(batch,customer,skipped?"SKIPPED":"FAILED","KNOWN",null,null,code,!skipped && (b.getCode()==503 || "SUPPORT_SKU_UNAVAILABLE".equals(code)),1);
            } else {
                // A database commit exception may have committed. Preserve uncertainty until a real message lookup.
                outcome(batch,customer,"PENDING","UNKNOWN",null,null,"RESULT_UNKNOWN",false,1);
            }
            finishLocked(batch,truth(job.get("cancelRequested")));
        });
    }

    private boolean reconcile(Map<String,Object> job,Map<String,Object> row) {
        if(row.get("requestJson")==null) {
            // JOB serialization and the reserved bulk_ namespace exclude another unrecorded bulk writer.
            // An orphan metadata fact still needs review; absence proves that no message committed.
            boolean exists=humanMessages.hasCommittedAdminFact(number(job,"actorId"),text(row,"clientMessageId"));
            if(exists) manualReview(job,row);
            else if("UNKNOWN".equals(row.get("resultCertainty"))) knownUncommitted(job,row);
            return false;
        }
        Object request=frozenRequest(row);
        Optional<SupportHumanMessageService.CommittedAdminMessage> committed;
        try {
            committed=humanMessages.findCommittedAdmin(number(job,"actorId"),text(row,"clientMessageId"),number(row,"customerId"),text(row,"operation"),request);
        } catch(BizException ex) {
            if(ex.getCode()!=409 || !"SUPPORT_CLIENT_MESSAGE_CONFLICT".equals(ex.getMessage())) throw ex;
            manualReview(job,row);return false;
        }
        if(committed.isEmpty()) {
            if("UNKNOWN".equals(row.get("resultCertainty"))) knownUncommitted(job,row);
            return false;
        }
        outcome(text(job,"id"),number(row,"customerId"),"SENT","KNOWN",committed.get().messageId(),committed.get().conversationNo(),null,false,0);
        return true;
    }

    private void manualReview(Map<String,Object> job,Map<String,Object> row) {
        outcome(text(job,"id"),number(row,"customerId"),"PENDING","UNKNOWN",null,null,"SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED",false,0);
        row.put("state","PENDING");row.put("resultCertainty","UNKNOWN");row.put("failureCode","SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED");
    }
    private void knownUncommitted(Map<String,Object> job,Map<String,Object> row) {
        outcome(text(job,"id"),number(row,"customerId"),"PENDING","KNOWN",null,null,null,false,0);
        row.put("state","PENDING");row.put("resultCertainty","KNOWN");row.put("failureCode",null);
    }
    private static boolean needsReview(Map<String,Object> row) {
        return "UNKNOWN".equals(row.get("resultCertainty")) && "SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED".equals(row.get("failureCode"));
    }

    private Object conversationRequest(Map<String,Object> job,Map<String,Object> row,SupportBulkRequest.Create content,Map<String,Object> actual) {
        if(row.get("requestJson")!=null) return frozenRequest(row);
        Long actor=number(job,"actorId"),customer=number(row,"customerId"),assignment=number(row,"expectedAssignmentId");
        if("CREATE".equals(row.get("operation"))) return new ConversationInitiateRequest("advisor",customer,String.valueOf(actor),null,
            content.content(),content.reason(),String.valueOf(actor),content.kind(),text(row,"attachmentId"),content.intent(),text(row,"clientMessageId"),
            assignment,List.of(),content.skuId(),content.linkTarget());
        return new ConversationReplyRequest(content.content(),text(actual,"status"),number(actual,"version"),content.reason(),String.valueOf(actor),
            List.of(),null,content.kind(),text(row,"attachmentId"),content.intent(),text(row,"clientMessageId"),assignment,content.skuId(),content.linkTarget());
    }
    private Object frozenRequest(Map<String,Object> row) {
        return "CREATE".equals(row.get("operation"))?decode(text(row,"requestJson"),ConversationInitiateRequest.class)
            :decode(text(row,"requestJson"),ConversationReplyRequest.class);
    }

    private void finishLocked(String batch,boolean cancelled) {
        var counts=counts(batch);var job=requireJob(mapper.job(batch));
        String state=counts.pending()>0?("QUEUED".equals(job.get("state"))?"QUEUED":"RUNNING"):(cancelled?"CANCELLED":"COMPLETED");
        mapper.transition(batch,state,cancelled);
    }
    private SupportBulk.Counts counts(String batch) {
        var row=mapper.counts(batch);var result=new SupportBulk.Counts(number(row,"total"),number(row,"pending"),number(row,"sent"),
            number(row,"failed"),number(row,"skipped"),number(row,"cancelled"),number(row,"unknown"));
        if(result.total()!=number(requireJob(mapper.job(batch)),"frozenCount")) throw new IllegalStateException("SUPPORT_BULK_FROZEN_COUNT_MISMATCH");
        return result;
    }

    private Map<String,Object> jobView(Map<String,Object> job,Long actor,Map<String,Object> query) {
        String batch=text(job,"id");var result=new LinkedHashMap<String,Object>();
        var batchQuery=new HashMap<>(query);batchQuery.put("batch",batch);
        result.put("batchId",batch);result.put("selectionId",batch);result.put("actorId",job.get("actorId"));
        for(String name:List.of("state","version","createdAt","updatedAt","commandKey")) result.put("commandKey".equals(name)?"key":name,job.get(name));
        long visible=mapper.scopedRecipientCount(batchQuery);
        boolean sender=Objects.equals(actor,number(job,"actorId")) && (Boolean.TRUE.equals(query.get("senderSummary"))
                || ((ReadScope)query.get("scope")).mode()==ReadMode.PERSONAL);
        var row=mapper.scopedCounts(batchQuery);
        result.put("counts",sender?counts(batch):new SupportBulk.Counts(number(row,"total"),number(row,"pending"),number(row,"sent"),
                number(row,"failed"),number(row,"skipped"),number(row,"cancelled"),number(row,"unknown")));
        result.put("frozenCount",sender?job.get("frozenCount"):visible);
        boolean restricted=number(job,"frozenCount")>visible;
        result.put("visibleCount",visible);result.put("contentRestricted",restricted);
        // The sender's shared draft contains no individual customer's identity or private attachment URL.
        if(!restricted && job.get("contentJson")!=null) {
            var content=decode(text(job,"contentJson"),SupportBulkRequest.Create.class);
            result.put("intent",content.intent());result.put("kind",content.kind());result.put("content",content.content());
            result.put("skuId",content.skuId());result.put("linkTarget",content.linkTarget());result.put("assetId",content.assetId());
        }
        return SupportWorkbenchService.wire(result);
    }
    private Map<String,Object> recipientView(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String name:List.of("batchId","customerId","expectedAssignmentId","clientMessageId","state","resultCertainty","messageId",
            "conversationNo","failureCode","retryable","attempts","createdAt","updatedAt")) result.put(name,row.get(name));
        result.put("retryable",truth(row.get("retryable")));return SupportWorkbenchService.wire(result);
    }

    private String exclusion(Map<String,Object> row,SupportBulkRequest.Filters f) {
        if(bindings.canonicalCustomer(number(row,"customerId"))!=1) return "ISOLATED_CUSTOMER";
        if(!truth(row.get("enabled"))) return "MAINTENANCE_STOPPED";
        if(f==null) return null;
        boolean unknown=Boolean.TRUE.equals(f.includeUnknown());
        if(f.keyword()!=null && !Objects.toString(row.get("nickname"),"").contains(f.keyword())
                && !Objects.toString(row.get("customerId"),"").equals(f.keyword())) return "FILTER_MISMATCH";
        if("UNKNOWN".equals(row.get("accountState")) && f.accountState()!=null && !"UNKNOWN".equals(f.accountState())) {
            if(!unknown)return "ACTIVITY_UNKNOWN";
        } else if(!match(text(row,"accountState"),f.accountState(),unknown)) return row.get("accountState")==null?"ACTIVITY_UNKNOWN":"FILTER_MISMATCH";
        if(!match(text(row,"level"),f.level(),unknown)) return row.get("level")==null?"LEVEL_UNKNOWN":"FILTER_MISMATCH";
        if(f.maintenanceState()!=null) {
            String state="DUE".equals(f.maintenanceState())?(!truth(row.get("maintenanceConfigured")) || row.get("due")==null?null:truth(row.get("due"))?"DUE":"NOT_DUE")
                :truth(row.get("enabled"))?"ENABLED":"STOPPED";
            if(!match(state,f.maintenanceState(),unknown)) return state==null?"MAINTENANCE_UNKNOWN":"FILTER_MISMATCH";
        }
        if(!range(row.get("registeredAt"),f.registeredFrom(),f.registeredTo(),unknown)) return row.get("registeredAt")==null?"REGISTRATION_UNKNOWN":"FILTER_MISMATCH";
        if(!range(row.get("lastEffectiveAt"),f.activityFrom(),f.activityTo(),unknown)) return row.get("lastEffectiveAt")==null?"ACTIVITY_UNKNOWN":"FILTER_MISMATCH";
        if(f.tagIds()!=null) for(String tag:f.tagIds()) if(mapper.hasTag(number(row,"customerId"),tag)==0)return "FILTER_MISMATCH";
        if(moneyFilter(f)) {
            Map<String,Object> totals;
            try {totals=finance.totals(number(row,"customerId"));}
            catch(RuntimeException ex) {return ex instanceof BizException b && b.getCode()==403?"FINANCE_FORBIDDEN":"FINANCE_ERROR";}
            @SuppressWarnings("unchecked") var currencies=(List<Map<String,Object>>)totals.get("byCurrency");
            var currency=currencies.stream().filter(c->f.currency().equals(c.get("currency"))).findFirst().orElse(Map.of());
            if(!amount(currency,"creditedDepositTotal",f.depositMin(),f.depositMax(),unknown)
                || !amount(currency,"successfulWithdrawalPrincipalTotal",f.withdrawalMin(),f.withdrawalMax(),unknown))
                return currency.isEmpty() || currency.get("creditedDepositTotal")==null || currency.get("successfulWithdrawalPrincipalTotal")==null?"FINANCE_UNKNOWN":"FILTER_MISMATCH";
        }
        return null;
    }

    private static boolean amount(Map<String,Object> row,String field,String minimum,String maximum,boolean includeUnknown) {
        if(minimum==null && maximum==null) return true;
        var statuses=row.get("fieldStatuses");
        if(!(statuses instanceof Map<?,?> fields) || !"READY".equals(fields.get(field)) || row.get(field)==null) return includeUnknown;
        var value=new BigDecimal(row.get(field).toString());
        return (minimum==null || value.compareTo(decimal(minimum))>=0) && (maximum==null || value.compareTo(decimal(maximum))<=0);
    }
    private static boolean match(String actual,String expected,boolean includeUnknown) {return expected==null || actual==null?expected==null || includeUnknown:actual.equals(expected);}
    private static boolean range(Object value,String from,String to,boolean includeUnknown) {
        if(from==null && to==null) return true;if(value==null)return includeUnknown;
        LocalDateTime at=time(value);return (from==null || !at.isBefore(parseTime(from))) && (to==null || at.isBefore(parseTime(to)));
    }
    private static boolean moneyFilter(SupportBulkRequest.Filters f) {return f.depositMin()!=null || f.depositMax()!=null || f.withdrawalMin()!=null || f.withdrawalMax()!=null;}
    private static String conversationExclusion(Map<String,Object> current) {
        if(truth(current.get("converted")))return "CONVERSATION_CONVERTED";
        if(truth(current.get("archived")))return "CONVERSATION_ARCHIVED";
        if(Set.of("CLOSED","TRANSFERRED").contains(text(current,"status")))return "CONVERSATION_NOT_WRITABLE";
        return null;
    }

    private void requireAsset(String asset,Long actor) {
        uuid(asset);var found=mapper.asset(asset);
        if(found==null || !actor.equals(number(found,"actorId"))) throw new BizException(404,"ATTACHMENT_NOT_FOUND");
        if(!"READY".equals(found.get("state")) || !time(found.get("expiresAt")).isAfter(utcNow())) throw conflict("ATTACHMENT_NOT_READY");
    }
    private Long authenticatedActor() {return attachments.actor("ADMIN");}
    private Map<String,Object> collectionQuery(Long actor,boolean supervisor) {
        var query=new HashMap<String,Object>();
        // Former senders retain their own summary; customer rows still require current service qualification in SQL.
        query.put("scope",supervisor?ownership.defaultQueryScope(null,null):new ReadScope(actor,ReadMode.PERSONAL,null,null));
        query.put("managedScope",null);query.put("personalScope",null);
        query.put("senderSummary",!supervisor);
        return query;
    }
    private Map<String,Object> objectQuery(Long actor) {
        var query=new HashMap<String,Object>();
        // These are requested identities, not grants: every SQL arm checks current roles/qualification/ownership.
        query.put("scope",new ReadScope(actor,ReadMode.ALL,null,null));
        query.put("managedScope",new ReadScope(actor,ReadMode.MANAGED,null,null));
        query.put("personalScope",new ReadScope(actor,ReadMode.PERSONAL,null,null));
        query.put("senderSummary",true);
        return query;
    }
    private void requireReadableJob(Map<String,Object> job,Long actor,Map<String,Object> query) {
        if(!Objects.equals(actor,number(job,"actorId")) && mapper.scopedRecipientCount(query)==0)
            throw new BizException(404,"SUPPORT_BULK_NOT_FOUND");
    }
    private boolean reader(Long actor) {
        boolean supervisor=ownership.supervisor(actor);
        if(mapper.readerGrant(actor,supervisor)<1) throw new BizException(403,"SUPPORT_READ_FORBIDDEN");
        return supervisor;
    }
    private static Map<String,Object> requireJob(Map<String,Object> job) {if(job==null)throw new BizException(404,"SUPPORT_BULK_NOT_FOUND");return job;}
    private static void requireOwner(Map<String,Object> job,Long actor) {if(!actor.equals(number(job,"actorId")))throw new BizException(404,"SUPPORT_BULK_NOT_FOUND");}
    private void outcome(String batch,Long customer,String state,String certainty,Long message,String no,String failure,boolean retryable,int attempt) {
        mapper.outcome(batch,customer,state,certainty,message,no,failure,retryable,attempt);
    }
    private TransactionTemplate transaction() {
        var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;
    }
    private void record(String action,String batch,Long actor,String key,String reason) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("SUPPORT_BULK").resourceId(batch).bizNo(batch)
            .actorType("ADMIN").actorId(actor).actorUsername("admin:"+actor).result("SUCCESS").riskLevel("HIGH")
            .detail(Map.of("batchId",batch,"reason",reason.trim(),"commandKey",key.trim())).build());
    }
    private static void validateCreate(String key,SupportBulkRequest.Create request) {
        if(request==null)throw invalid("SUPPORT_BULK_CONTENT_REQUIRED");uuid(request.selectionId());SupportBindingService.validateCommand(key,request.reason());
        if(!Set.of("SERVICE","MAINTENANCE").contains(request.intent()==null?"":request.intent())
            || !Set.of("TEXT","IMAGE","SKU","LINK").contains(request.kind()==null?"":request.kind())
            || !SupportHumanMessageService.validContent(request.kind(),request.content())
            || ("IMAGE".equals(request.kind())!=(request.assetId()!=null))
            || ("SKU".equals(request.kind())!=(request.skuId()!=null && !request.skuId().isBlank()))
            || ("LINK".equals(request.kind())!=(request.linkTarget()!=null)))throw invalid("SUPPORT_BULK_CONTENT_INVALID");
        if(request.assetId()!=null)uuid(request.assetId());
        if(request.skuId()!=null && !request.skuId().matches("[A-Za-z0-9_-]{1,64}"))throw invalid("SUPPORT_SKU_INVALID");
        if(request.linkTarget()!=null && (!Set.of("HOME","WALLET","SUPPORT").contains(request.linkTarget().type()==null?"":request.linkTarget().type())
            || request.linkTarget().params()!=null && !request.linkTarget().params().isEmpty()))throw invalid("SUPPORT_LINK_INVALID");
    }
    private static void validateMutation(String key,SupportBulkRequest.Mutation request) {
        if(request==null)throw invalid("SUPPORT_BULK_EXPECTATION_REQUIRED");SupportWorkbenchService.requireSafeId(request.expectedVersion());SupportBindingService.validateCommand(key,request.reason());
    }
    private static void validateFilters(SupportBulkRequest.Filters f) {
        if(f==null)return;
        for(String value:Arrays.asList(f.accountState(),f.level(),f.maintenanceState())) if(value!=null && (value.isBlank() || value.length()>64))throw invalid("SUPPORT_BULK_FILTER_INVALID");
        if(f.accountState()!=null && !Set.of("ACTIVE","DORMANT","UNKNOWN").contains(f.accountState())
            || f.maintenanceState()!=null && !Set.of("ENABLED","STOPPED","DUE").contains(f.maintenanceState())
            || f.keyword()!=null && f.keyword().length()>200)throw invalid("SUPPORT_BULK_FILTER_INVALID");
        if(f.tagIds()!=null && (f.tagIds().size()>100 || f.tagIds().stream().anyMatch(t->t==null || t.isBlank() || t.length()>100)))throw invalid("SUPPORT_BULK_TAG_INVALID");
        timeRange(f.registeredFrom(),f.registeredTo());timeRange(f.activityFrom(),f.activityTo());
        amountRange(f.depositMin(),f.depositMax());amountRange(f.withdrawalMin(),f.withdrawalMax());
        if(moneyFilter(f) && (f.currency()==null || !f.currency().matches("[A-Z][A-Z0-9]{0,15}")))throw invalid("SUPPORT_BULK_CURRENCY_REQUIRED");
    }
    private static void timeRange(String from,String to) {var start=parseTime(from);var end=parseTime(to);if(start!=null && end!=null && !start.isBefore(end))throw invalid("SUPPORT_BULK_TIME_RANGE_INVALID");}
    private static void amountRange(String minimum,String maximum) {var min=decimal(minimum);var max=decimal(maximum);if(min!=null && max!=null && min.compareTo(max)>0)throw invalid("SUPPORT_BULK_AMOUNT_RANGE_INVALID");}
    private static BigDecimal decimal(String value) {if(value==null)return null;if(value.length()>128 || !value.matches("[0-9]+(\\.[0-9]+)?"))throw invalid("SUPPORT_BULK_AMOUNT_INVALID");return new BigDecimal(value);}
    private static LocalDateTime parseTime(String value) {
        if(value==null)return null;
        try {var at=LocalDateTime.ofInstant(Instant.parse(value),ZoneOffset.UTC);if(at.getYear()<1000 || at.getYear()>9999)throw new DateTimeException("range");return at;}
        catch(DateTimeException ex){throw invalid("SUPPORT_BULK_TIME_RANGE_INVALID");}
    }
    private static Set<Long> ids(List<Long> values) {
        if(values==null)return Set.of();var ids=new TreeSet<Long>();
        for(Long id:values){SupportWorkbenchService.requireSafeId(id);if(!ids.add(id))throw invalid("SUPPORT_BULK_SELECTION_DUPLICATE");}return ids;
    }
    private static String clientId(String batch,Long customer) {return "bulk_"+hash(batch+":"+customer);}
    private static String attachmentId(String batch,Long customer) {return UUID.nameUUIDFromBytes((batch+":"+customer).getBytes(StandardCharsets.UTF_8)).toString();}
    private static void uuid(String value) {if(value==null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("SUPPORT_BULK_ID_INVALID");}
    private static String text(Map<String,Object> row,String name) {return row.get(name)==null?null:row.get(name).toString();}
    private static Long number(Map<String,Object> row,String name) {return ((Number)row.get(name)).longValue();}
    private static boolean truth(Object value) {return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0;}
    private static LocalDateTime time(Object value) {return value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
    private static LocalDateTime utcNow() {return LocalDateTime.now(ZoneOffset.UTC);}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("SUPPORT_BULK_PAYLOAD_INVALID",ex);}}
    private <T> T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception ex){throw new IllegalStateException("SUPPORT_BULK_PAYLOAD_INVALID",ex);}}
    private String batchId(ApiResult<?> receipt) {if(receipt.getCode()!=0)throw new BizException(receipt.getCode(),receipt.getMessage());return String.valueOf(((Map<?,?>)receipt.getData()).get("batchId"));}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static BizException invalid(String code) {return new BizException(422,code);}
    private static BizException conflict(String code) {return new BizException(409,code);}
}
