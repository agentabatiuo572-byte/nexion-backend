package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.math.BigDecimal;

/** Fixed query choices only. Keys are bound parameters, never SQL identifiers. */
public final class SupportPaymentSourceSql {
    // Cold rows retain the original audit identity; the digest is checked before accepting a proof.
    private static final String HISTORY_AUDITS="""
        (SELECT id,action,resource_type resourceType,resource_id resourceId,biz_no bizNo,user_id customerId,
          actor_type actorType,actor_username actorUsername,result,detail_json detail,created_at auditAt,1 validDigest,
          actor_id actorId,method,path
          FROM nx_audit_log WHERE is_deleted=0
        UNION
        SELECT original_audit_id,JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')),
          JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType')),JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceId')),
          JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.bizNo')),JSON_EXTRACT(cold_payload,'$.userId'),
          JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorType')),JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.actorUsername')),
          JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.result')),JSON_EXTRACT(cold_payload,'$.detail'),original_created_at,
          CASE WHEN content_sha256=SHA2(CAST(cold_payload AS CHAR),256)
            AND JSON_EXTRACT(cold_payload,'$.id')=original_audit_id THEN 1 ELSE 0 END,
          JSON_EXTRACT(cold_payload,'$.actorId'),JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.method')),JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.path'))
          FROM nx_audit_log_archive)
        """;
    static final String HISTORY_PAYMENT_AUDITS="""
        <script>SELECT a.id,a.customerId,o.order_no orderNo,a.resourceId,a.resourceType,a.bizNo,a.actorType,a.action,a.result,a.detail,a.auditAt,a.validDigest,
          o.payment_no paymentNo,o.amount_usdt amount,o.paid_at paidAt,a.actorId,a.method,a.path,
          EXISTS(SELECT 1 FROM nx_user_device d WHERE d.id=COALESCE(JSON_EXTRACT(a.detail,'$.after.targetDeviceId'),JSON_EXTRACT(a.detail,'$.deviceId'))
            AND d.user_id=o.user_id AND d.source_order_no=o.order_no) deviceMatched
        FROM
        """+HISTORY_AUDITS+"""
        a JOIN nx_order o ON o.user_id=a.customerId AND
          ((a.action='APP_ORDER_WALLET_PAYMENT' AND o.order_no=a.resourceId)
            OR (a.action IN ('USER_TRADEIN_COMPLETED','USER_CAPACITY_REPLACEMENT_COMPLETED','USER_CAPACITY_KEEP_PURCHASE_COMPLETED') AND o.order_no=a.bizNo)
            OR (a.action='H2_TRIAL_CONVERTED' AND o.order_no=JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.orderNo'))))
        WHERE a.action IN ('APP_ORDER_WALLET_PAYMENT','USER_TRADEIN_COMPLETED','USER_CAPACITY_REPLACEMENT_COMPLETED',
          'USER_CAPACITY_KEEP_PURCHASE_COMPLETED','H2_TRIAL_CONVERTED') AND a.customerId IN
        <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach></script>
        """;
    static final String HISTORY_REFUND_ORIGINS="""
        <script>SELECT o.user_id customerId,o.order_no orderNo,o.amount_usdt amount,l.id ledgerId,l.created_at ledgerAt,
          (SELECT COUNT(DISTINCT a.id) FROM
        """+HISTORY_AUDITS+"""
          a JOIN nx_order_state_history h ON h.to_state='refunded'
            AND h.from_state IN ('paid','provisioning','provisioning_failed','activated')
            AND h.from_state=JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.fromState'))
            AND h.operator=a.actorUsername AND h.reason=JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.reason'))
            AND h.idempotency_key=JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.idempotencyKey'))
            AND TRIM(h.reason)&lt;&gt;'' AND TRIM(h.idempotency_key)&lt;&gt;''
          WHERE h.order_no=o.order_no AND a.validDigest=1 AND a.action='E4_ORDER_REFUNDED' AND a.resourceType='DEVICE_ORDER'
            AND a.resourceId=o.order_no AND a.bizNo=o.order_no AND a.actorType='ADMIN'
            AND a.actorUsername IS NOT NULL AND TRIM(a.actorUsername)&lt;&gt;'' AND a.result='SUCCESS'
            AND JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.orderNo'))=o.order_no
            AND JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.toState'))='refunded'
            AND JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.refundChannel'))='WALLET'
            AND JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.amount')) REGEXP '^[0-9]{1,12}([.][0-9]{1,6})?$'
            AND CAST(JSON_UNQUOTE(JSON_EXTRACT(a.detail,'$.amount')) AS DECIMAL(18,6))=o.amount_usdt
            AND h.created_at=l.created_at AND a.auditAt=l.created_at
            AND l.user_id=o.user_id AND l.biz_type='ORDER_REFUND' AND l.status='SUCCESS'
            AND l.direction='IN' AND l.asset='USDT' AND l.is_deleted=0 AND l.amount=o.amount_usdt) proofCount
        FROM nx_order o LEFT JOIN nx_wallet_ledger l ON l.biz_no=CONCAT('E4-REFUND-',o.order_no) AND l.biz_type='ORDER_REFUND'
        WHERE o.user_id IN <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach>
          AND (UPPER(COALESCE(o.order_status,''))='REFUNDED' OR UPPER(COALESCE(o.payment_status,''))='REFUNDED'
            OR EXISTS(SELECT 1 FROM nx_order_state_history h WHERE h.order_no=o.order_no AND h.to_state='refunded')
            OR EXISTS(SELECT 1 FROM
        """+HISTORY_AUDITS+"""
              a WHERE a.action='E4_ORDER_REFUNDED' AND a.result='SUCCESS' AND a.resourceType='DEVICE_ORDER'
              AND (a.resourceId=o.order_no OR a.bizNo=o.order_no)))</script>
        """;
    static final String HISTORY_LEDGERS="""
        <script>SELECT id,user_id customerId,biz_no businessId,biz_type ledgerType,asset currency,direction,status,
        is_deleted deleted,amount,created_at successAt FROM nx_wallet_ledger WHERE user_id IN
        <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach>
        AND id IN <foreach collection='ledgerIds' item='ledger' open='(' separator=',' close=')'>#{ledger}</foreach></script>
        """;
    private SupportPaymentSourceSql() {}

    private static final String HISTORY_KEYS="<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach>";
    private static void historyScope(Map<String,Object> p) {
        if(!(p.get("customerIds") instanceof List<?> ids) || ids.isEmpty()
            || ids.stream().anyMatch(id -> !positiveInteger(id)))throw new IllegalArgumentException("Explicit positive customer scope required");
    }
    private static boolean positiveInteger(Object value) {
        if(!(value instanceof Number))return false;
        try {return new BigDecimal(value.toString()).longValueExact()>0;}catch(ArithmeticException | NumberFormatException ex){return false;}
    }
    private static Source historySource(Map<String,Object> p) {
        historyScope(p);
        if(!(p.get("source") instanceof Source source) || source==Source.FREE_TRIAL || source==Source.UNMATCHED_LEDGER)
            throw new IllegalArgumentException("Unsupported payment source");
        if(!(p.get("keys") instanceof List<?> keys) || keys.isEmpty()
            || keys.stream().anyMatch(key -> !(key instanceof String text) || text.isBlank()))
            throw new IllegalArgumentException("Explicit source references required");
        return source;
    }
    // Root/marker references come only from decoded, scoped captures. Retain foreign owners and
    // deleted/unsuccessful rows so the existing financial predicates can reject contradictions.
    public static String historyBeforeBatch(Map<String,Object> p) {
        Source source=historySource(p);
        String suffix=switch(source) {
            case DEPOSIT_ORDER -> " WHERE d.deposit_no=#{key}";case CARD_TOPUP -> " WHERE payment_no=#{key}";
            case VIETQR -> " WHERE reconciliation_no=#{rawKey}";case HDPAY -> " WHERE h.merchant_order_id=#{key}";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,ORDER_REFUND -> " WHERE order_no=#{rawKey}";
            case TRIAL_CONVERT -> " WHERE claim_no=#{rawKey}";default -> throw new IllegalArgumentException("Unsupported payment source");
        };
        String column=switch(source) {
            case DEPOSIT_ORDER -> "d.deposit_no";case CARD_TOPUP -> "payment_no";
            case VIETQR -> "CONCAT('D1-VIETQR-',reconciliation_no)";case HDPAY -> "h.merchant_order_id";
            case TRIAL_CONVERT -> "CONCAT(claim_no,':CHARGE')";default -> "order_no";
        };
        var request=new HashMap<>(p);request.put("key",switch(source){case VIETQR -> "D1-VIETQR-BATCH";case TRIAL_CONVERT -> "BATCH:CHARGE";case ORDER_REFUND -> "E4-REFUND-BATCH";default -> "BATCH";});
        String original=before(request);
        if(!original.endsWith(suffix))throw new IllegalStateException("History root projection changed");
        return "<script>"+original.substring(0,original.length()-suffix.length())+" WHERE "+column+" IN "+HISTORY_KEYS+"</script>";
    }
    public static String historySettledBatch(Map<String,Object> p) {
        Source source=historySource(p);
        String column=switch(source) {
            case DEPOSIT_ORDER -> "d.deposit_no";case CARD_TOPUP -> "p.payment_no";
            case VIETQR -> "CONCAT('D1-VIETQR-',r.reconciliation_no)";case HDPAY -> "i.intent_no";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP -> "o.order_no";case TRIAL_CONVERT -> "CONCAT(c.claim_no,':CHARGE')";
            case ORDER_REFUND -> "l.biz_no";default -> throw new IllegalArgumentException("Unsupported payment source");
        };
        String original=SupportFundReadMapper.PaymentSourceSql.batch(p).stripTrailing();
        if(!original.endsWith("</script>"))throw new IllegalStateException("History settled projection changed");
        return original.substring(0,original.length()-9)+" AND "+column+" IN "+HISTORY_KEYS+"</script>";
    }
    public static String historyMarkerBatch(Map<String,Object> p) {
        historyScope(p);
        if(!(p.get("marker") instanceof SupportPaymentSourceMapper.Marker marker))throw new IllegalArgumentException("Unsupported history marker");
        String original,suffix,column;
        switch(marker) {
            case CREGIS_EVENT -> {original=CREGIS_EVENT;suffix=" WHERE project_id=#{partition} AND cid=#{cid}";column=null;}
            case PAYMENT -> {original=PAYMENT;suffix=" WHERE order_no=#{orderNo}";column="order_no";}
            case CARD_SETTLEMENT -> {original=CARD_SETTLEMENT;suffix=" WHERE payment_no=#{key}";column="payment_no";}
            case INTENT -> {original=INTENT;suffix=" WHERE intent_no=#{key}";column="intent_no";}
            case DEVICE -> {original=DEVICE;suffix=" WHERE id=#{id}";column="id";}
            default -> throw new IllegalArgumentException("Unsupported history marker");
        }
        if(!(p.get("refs") instanceof List<?> refs) || refs.isEmpty())throw new IllegalArgumentException("Explicit marker references required");
        for(Object value:refs) {
            if(!(value instanceof Map<?,?> ref))throw new IllegalArgumentException("Invalid marker reference");
            boolean valid=switch(marker) {
                case CREGIS_EVENT -> ref.keySet().equals(Set.of("projectId","cid")) && positiveInteger(ref.get("projectId")) && positiveInteger(ref.get("cid"));
                case DEVICE -> ref.keySet().equals(Set.of("id")) && positiveInteger(ref.get("id"));
                default -> ref.keySet().equals(Set.of("key")) && ref.get("key") instanceof String key && !key.isBlank();
            };
            if(!valid)throw new IllegalArgumentException("Invalid marker reference");
        }
        if(!original.endsWith(suffix))throw new IllegalStateException("History marker projection changed");
        String predicate=marker==SupportPaymentSourceMapper.Marker.CREGIS_EVENT
            ? "<foreach collection='refs' item='ref' open='(' separator=' OR ' close=')'>(project_id=#{ref.projectId} AND cid=#{ref.cid})</foreach>"
            : column+" IN <foreach collection='refs' item='ref' open='(' separator=',' close=')'>#{ref."+(marker==SupportPaymentSourceMapper.Marker.DEVICE?"id":"key")+"}</foreach>";
        return "<script>"+original.substring(0,original.length()-suffix.length())+" WHERE "+predicate+"</script>";
    }
    static final String TRIAL_FUND_SOURCE="""
        SELECT c.id claimId,c.claim_no claimNo,c.user_id customerId,c.status claimStatus,c.is_deleted claimDeleted,
          c.settlement_amount_usdt chargeUsdt,c.remainder_usdt remainderUsdt,c.shadow_accrued_nex shadowNex,
          c.settled_at settledAt,c.version claimVersion,d.id deviceId,d.source_order_no orderNo,
          d.source_environment deviceEnvironment,d.status deviceStatus,d.ownership_status ownershipStatus,
          d.source_channel sourceChannel,d.is_deleted deviceDeleted,o.id orderId,o.order_type orderType,
          o.payment_status paymentStatus,o.amount_usdt amountUsdt,o.paid_at paidAt,o.is_deleted orderDeleted,
          u.sandbox userSandbox,u.is_deleted userDeleted,u.status userStatus
        FROM nx_trial_claim c
        JOIN nx_user_device d ON d.id=c.user_device_id AND d.user_id=c.user_id
        JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=c.user_id
        JOIN nx_user u ON u.id=c.user_id
        WHERE c.user_id=#{customerId} AND c.claim_no=#{claimNo} FOR SHARE OF c,d,o,u
        """;
    static final String PROOF="SELECT capture_mode captureMode,capture_schema_version schemaVersion,source_partition sourcePartition,source_fact_json sourceFactJson,JSON_EXTRACT(attribution_evidence_json,'$.beforeSource') beforeSourceJson,JSON_UNQUOTE(JSON_EXTRACT(attribution_evidence_json,'$.captureMode')) evidenceCaptureMode,JSON_UNQUOTE(JSON_EXTRACT(attribution_evidence_json,'$.schemaVersion')) evidenceSchemaVersion FROM nx_support_payment_attribution WHERE fact_id=#{factId}";
    static final String CREGIS_EVENT="SELECT id,CONCAT('nx_cregis_deposit_event:',id) sourceId,user_id customerId,project_id projectId,cid,status,net_amount amount,ledger_id ledgerId,credited_at successAt FROM nx_cregis_deposit_event WHERE project_id=#{partition} AND cid=#{cid}";
    static final String LEDGER="SELECT id,CONCAT('nx_wallet_ledger:',id) sourceId,user_id customerId,biz_no businessId,biz_type ledgerType,status,created_at successAt,is_deleted deleted,asset currency,direction,amount,balance_after balanceAfter,remark,updated_at updatedAt FROM nx_wallet_ledger WHERE biz_no=#{key}";
    static final String PAYMENT="SELECT id,CONCAT('nx_payment_record:',id) sourceId,user_id customerId,payment_no businessId,order_no orderNo,provider,payment_status status,paid_at successAt,wallet_ledger_id ledgerId,amount_usdt amount,currency,is_deleted deleted FROM nx_payment_record WHERE order_no=#{orderNo}";
    static final String CARD_SETTLEMENT="SELECT id,CONCAT('nx_topup_card_settlement:',id) sourceId,user_id customerId,payment_no businessId,order_no orderNo,provider,provider_payment_id providerPaymentId,amount_usdt amount,status,is_deleted deleted FROM nx_topup_card_settlement WHERE payment_no=#{key}";
    static final String INTENT="SELECT id,CONCAT('nx_vietqr_intent:',id) sourceId,user_id customerId,intent_no businessId,status,credited_usdt amount,payment_rail rail,settlement_target_type target,is_deleted deleted FROM nx_vietqr_intent WHERE intent_no=#{key}";
    static final String REFUND_BILL="SELECT id,CONCAT('nx_wallet_bill:',id) sourceId,user_id customerId,bill_no businessId,type,token,direction,deleted FROM nx_wallet_bill WHERE bill_no=#{key}";
    static final String DEVICE="SELECT id,user_id customerId,source_order_no orderNo,is_deleted deleted FROM nx_user_device WHERE id=#{id}";

    public static String before(Map<String,Object> p) {
        Source source=(Source)p.get("source");
        String sql=switch(source) {
            case DEPOSIT_ORDER -> "SELECT d.id,d.user_id customerId,d.deposit_no businessId,d.status,d.credited_at successAt,d.ledger_id ledgerId,d.amount amount,d.asset currency,d.is_deleted deleted FROM nx_deposit_order d WHERE d.deposit_no=#{key}";
            case CARD_TOPUP -> "SELECT id,user_id customerId,payment_no businessId,order_no orderNo,payment_status status,paid_at providerPaidAt,wallet_ledger_id ledgerId,amount_usdt amount,currency,provider,provider_payment_id providerPaymentId,is_deleted deleted FROM nx_payment_record WHERE payment_no=#{key}";
            case VIETQR -> "SELECT id,user_id customerId,CONCAT('D1-VIETQR-',reconciliation_no) businessId,intent_no intentNo,view_type viewType,status,credited_usdt amount,received_at providerPaidAt,CAST(version AS CHAR) sourceVersion,is_deleted deleted FROM nx_vietqr_reconciliation WHERE reconciliation_no=#{rawKey}";
            case HDPAY -> "SELECT h.id,i.user_id customerId,h.merchant_order_id businessId,h.settlement_status status,h.settled_at successAt,h.settled_usdt amount,h.wallet_ledger_biz_no ledgerBusinessId,CAST(h.version AS CHAR) sourceVersion,i.is_deleted deleted FROM nx_hdpay_payin_order h JOIN nx_vietqr_intent i ON i.intent_no=h.merchant_order_id WHERE h.merchant_order_id=#{key}";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,ORDER_REFUND -> order("#{rawKey}");
            case TRIAL_CONVERT -> "SELECT id,user_id customerId,CONCAT(claim_no,':CHARGE') businessId,status,settled_at successAt,settlement_amount_usdt amount,user_device_id deviceId,CAST(version AS CHAR) sourceVersion,is_deleted deleted FROM nx_trial_claim WHERE "+(((String)p.get("key")).startsWith("USER:")?"user_id=#{customerId}":"claim_no=#{rawKey}");
            default -> throw new IllegalArgumentException("Unsupported payment source");
        };
        return bindRaw(sql,p);
    }
    private static String order(String key) {
        return "SELECT id,CONCAT('nx_order:',id) sourceId,user_id customerId,order_no businessId,order_no orderNo,order_type orderType,payment_no paymentNo,payment_status status,paid_at successAt,amount_usdt amount,is_deleted deleted FROM nx_order WHERE order_no="+key;
    }
    public static String settled(Map<String,Object> p) {
        Source source=(Source)p.get("source");
        String sql=switch(source) {
            case DEPOSIT_ORDER -> tail(SupportPaymentFactSql.DEPOSITS,"d.deposit_no=#{key}");
            case CARD_TOPUP -> tail(SupportPaymentFactSql.CARDS
                .replace("p.paid_at providerPaidAt,","p.provider provider,p.provider_payment_id providerPaymentId,p.order_no sourceOrderNo,p.paid_at providerPaidAt,"),"p.payment_no=#{key}");
            case VIETQR -> tail(SupportPaymentFactSql.VIETQR.substring(0,SupportPaymentFactSql.VIETQR.indexOf("AND NOT ("))+"</script>","r.reconciliation_no=#{rawKey}");
            case HDPAY -> tail(SupportPaymentFactSql.HDPAY,"i.intent_no=#{key}");
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP -> orders("o.order_no=#{key}");
            case TRIAL_CONVERT -> tail(SupportPaymentFactSql.TRIALS,"c.claim_no=#{rawKey}");
            case ORDER_REFUND -> tail(SupportPaymentFactSql.REFUNDS,"l.biz_no=#{key}");
            default -> throw new IllegalArgumentException("Unsupported payment source");
        };
        sql=switch(source) {
            case DEPOSIT_ORDER -> sql.replace("SELECT 'DEPOSIT' kind,","SELECT d.id sourceRootId,'DEPOSIT' kind,");
            case CARD_TOPUP -> sql.replace("SELECT 'DEPOSIT' kind,","SELECT p.id sourceRootId,'DEPOSIT' kind,");
            case VIETQR -> sql.replace("SELECT 'DEPOSIT' kind,","SELECT r.id sourceRootId,'DEPOSIT' kind,");
            case HDPAY -> sql.replace("SELECT 'DEPOSIT' kind,","SELECT h.id sourceRootId,i.id intentId,'DEPOSIT' kind,");
            case TRIAL_CONVERT -> sql.replace("SELECT 'DEVICE_PURCHASE' kind,","SELECT o.id sourceRootId,d.id deviceId,c.id claimId,'DEVICE_PURCHASE' kind,");
            case ORDER_REFUND -> sql.replace("SELECT 'DEVICE_PURCHASE_REFUND' kind,","SELECT o.id sourceRootId,'DEVICE_PURCHASE_REFUND' kind,");
            default -> sql.replace("SELECT 'DEVICE_PURCHASE' kind,","SELECT o.id sourceRootId,'DEVICE_PURCHASE' kind,");
        };
        return bindRaw(sql,p);
    }
    // A separate row locking read supplies payment confirmation; nested COUNT/MIN are not current reads.
    private static String orders(String predicate) {
        String sql=SupportPaymentFactSql.ORDERS.replace("o.order_no orderNo,","o.payment_no paymentNo,o.order_no orderNo,");
        int start=sql.indexOf("CASE WHEN o.order_type IN ('TRADE_IN','CAPACITY_KEEP') THEN 1");
        int end=sql.indexOf(SupportPaymentFactSql.LEDGER,start);
        return tail(sql.substring(0,start)+"1 sourceLinked,NULL sourceConfirmationAt,\n"+sql.substring(end),predicate);
    }
    public static String refunds(Map<String,Object> p) {
        p.put("source",Source.ORDER_REFUND);p.put("key","E4-REFUND-"+p.get("orderNo"));return settled(p);
    }
    public static String trialOrder(Map<String,Object> p) {
        return tail(SupportPaymentFactSql.TRIALS.replace("SELECT 'DEVICE_PURCHASE' kind,","SELECT o.id sourceRootId,d.id deviceId,c.id claimId,'DEVICE_PURCHASE' kind,"),"o.order_no=#{orderNo}");
    }
    public static String currentBefore(Map<String,Object> p) {
        Source source=(Source)p.get("source");String sql=before(p);
        String table=switch(source) {case DEPOSIT_ORDER -> "d";case HDPAY -> "h";
            case CARD_TOPUP -> "nx_payment_record";case VIETQR -> "nx_vietqr_reconciliation";
            case TRIAL_CONVERT -> "nx_trial_claim";default -> "nx_order";};
        return sql.substring(0,sql.indexOf(" WHERE "))+" WHERE "+table+".id=#{id} FOR SHARE OF "+table;
    }
    public static String currentMarker(Map<String,Object> p) {
        var marker=(SupportPaymentSourceMapper.Marker)p.get("marker");
        String sql=switch(marker) {case CREGIS_EVENT -> CREGIS_EVENT;
            case LEDGER -> LEDGER;case PAYMENT -> PAYMENT;case CARD_SETTLEMENT -> CARD_SETTLEMENT;
            case INTENT -> INTENT;case REFUND_BILL -> REFUND_BILL;case DEVICE -> DEVICE;};
        return sql.substring(0,sql.indexOf(" WHERE "))+" WHERE id=#{id} FOR SHARE";
    }
    public static String currentSettled(Map<String,Object> p) {
        Source source=(Source)p.get("source");String sql=settled(p);
        String root=switch(source) {case DEPOSIT_ORDER -> "d";case CARD_TOPUP -> "p";
            case VIETQR -> "r";case HDPAY -> "h";default -> "o";};
        sql=sql.replace("JOIN nx_wallet_ledger l ON ","JOIN nx_wallet_ledger l ON l.id=#{ids.ledgerId} AND ")
            .replace("JOIN nx_wallet_ledger l\n  ON ","JOIN nx_wallet_ledger l ON l.id=#{ids.ledgerId} AND ");
        String predicates=root+".id=#{ids.sourceRootId} AND l.id=#{ids.ledgerId}";
        String locked=root+",l";
        if(source==Source.HDPAY) {predicates+=" AND i.id=#{ids.intentId}";locked+=",i";}
        if(source==Source.TRIAL_CONVERT) {predicates+=" AND d.id=#{ids.deviceId} AND c.id=#{ids.claimId}";locked+=",d,c";}
        return sql.replace("</script>"," AND "+predicates+" FOR SHARE OF "+locked+"</script>");
    }
    public static String insertLedger(Map<String,Object> p) {
        Source source=(Source)p.get("source");
        String type=ledgerType(source),direction=ledgerDirection(source),status=source==Source.TRIAL_CONVERT?"POSTED":"SUCCESS";
        String fields="biz_no,user_id,biz_type,asset,direction,amount,balance_after,status,remark";
        String values="#{ledger.key},#{ledger.customer},'"+type+"','USDT','"+direction+"',#{ledger.amount},#{ledger.balanceAfter},'"+status+"',#{ledger.remark}";
        if(source!=Source.DEPOSIT_ORDER) {
            String now=source==Source.WALLET_ORDER?"NOW(6)":"NOW()";
            fields+=",created_at,updated_at,is_deleted";values+=","+now+","+now+",0";
        }
        return "INSERT INTO nx_wallet_ledger ("+fields+") VALUES ("+values+")";
    }
    public static String ledgerType(Source source) {
        return switch(source) {case DEPOSIT_ORDER -> "CHAIN_TOPUP";case CARD_TOPUP -> "CARD_TOPUP";
            case VIETQR,HDPAY -> "VIETQR_DEPOSIT";case WALLET_ORDER -> "ORDER_PURCHASE";
            case TRADE_IN -> "TRADE_IN_PURCHASE";case CAPACITY_KEEP -> "DEVICE_PURCHASE";
            case TRIAL_CONVERT -> "TRIAL_CHARGE";case ORDER_REFUND -> "ORDER_REFUND";
            case GENESIS_PRIMARY -> "GENESIS_PURCHASE";case GENESIS_SECONDARY -> "GENESIS_SECONDARY_BUY";
            default -> throw new IllegalArgumentException("Unsupported payment source");};
    }
    public static String ledgerDirection(Source source) {
        return switch(source) {case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY,ORDER_REFUND -> "IN";
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT,GENESIS_PRIMARY,GENESIS_SECONDARY -> "OUT";
            default -> throw new IllegalArgumentException("Unsupported payment source");};
    }
    public static String ledgerKey(Source source,String key) {return source==Source.GENESIS_SECONDARY?key+"-BUY":key;}
    private static String tail(String sql,String predicate) { return sql.replace("</script>"," AND "+predicate+"</script>"); }
    private static String bindRaw(String sql,Map<String,Object> p) {
        Object key=p.get("key");
        String raw=rawKey((Source)p.get("source"),key.toString());
        p.put("rawKey",raw);
        return sql;
    }
    public static String rawKey(Source source,String key) {
        return switch(source) {
            case VIETQR -> strip(key,"D1-VIETQR-",false);
            case TRIAL_CONVERT -> key.startsWith("USER:")?key:strip(key,":CHARGE",true);
            case ORDER_REFUND -> strip(key,"E4-REFUND-",false);
            default -> key;
        };
    }
    private static String strip(String key,String part,boolean suffix) {
        if (!(suffix?key.endsWith(part):key.startsWith(part)) || key.length()==part.length())
            throw new IllegalArgumentException("Invalid canonical payment key");
        return suffix?key.substring(0,key.length()-part.length()):key.substring(part.length());
    }
}
