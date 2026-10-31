package ffdd.opsconsole.auth.facade;

import java.util.Map;

/** Read-only lookup; null means this user/kind/key has no external payment intent. */
public interface ExternalPurchaseCommandQuery {
    Map<String,Object> find(long userId,String kind,String commandKey);
}
