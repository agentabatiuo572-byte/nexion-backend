package ffdd.opsconsole.finance.purchase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Supplier;
import ffdd.opsconsole.shared.exception.BizException;

/** Server-only scope entered after provider proof and intent row lock, never from request fields. */
public final class VerifiedPurchasePayment {
    private VerifiedPurchasePayment() {}
    private record Funds(long owner,String method,String intentNo,BigDecimal subtotal,java.time.Instant pricingInstant,java.time.Instant paidInstant) {}
    private static final ThreadLocal<Funds> CURRENT=new ThreadLocal<>();
    static <T> T settle(long owner,String method,String intentNo,BigDecimal subtotal,Supplier<T> action) {
        return settle(owner,method,intentNo,subtotal,java.time.Instant.now(),action);
    }
    static <T> T settle(long owner,String method,String intentNo,BigDecimal subtotal,java.time.Instant pricingInstant,Supplier<T> action) {
        return settle(owner,method,intentNo,subtotal,pricingInstant,java.time.Instant.now(),action);
    }
    static <T> T settle(long owner,String method,String intentNo,BigDecimal subtotal,java.time.Instant pricingInstant,java.time.Instant paidInstant,Supplier<T> action) {
        if(CURRENT.get()!=null)throw new IllegalStateException("PURCHASE_PAYMENT_NESTED_SCOPE");
        CURRENT.set(new Funds(owner,method,intentNo,subtotal,pricingInstant,java.util.Objects.requireNonNull(paidInstant)));
        try{return action.get();}finally{CURRENT.remove();}
    }
    public static boolean active(){return CURRENT.get()!=null;}
    public static String method(){return CURRENT.get()==null?"UVEL_WALLET":CURRENT.get().method();}
    public static String intentNo(){if(!active())throw new IllegalStateException("PURCHASE_PAYMENT_SCOPE_REQUIRED");return CURRENT.get().intentNo();}
    public static java.time.Instant pricingInstant(){if(!active())throw new IllegalStateException("PURCHASE_PAYMENT_SCOPE_REQUIRED");return CURRENT.get().pricingInstant();}
    public static java.time.Instant paidInstant(){if(!active())throw new IllegalStateException("PURCHASE_PAYMENT_SCOPE_REQUIRED");return CURRENT.get().paidInstant();}
    public static void require(long owner,BigDecimal subtotal){
        Funds funds=CURRENT.get();
        if(funds==null||funds.owner()!=owner||subtotal==null
                ||funds.subtotal().compareTo(subtotal.setScale(2,RoundingMode.HALF_UP))!=0)
            throw new BizException(409,"PURCHASE_PAYMENT_PRICING_CHANGED");
    }
}
