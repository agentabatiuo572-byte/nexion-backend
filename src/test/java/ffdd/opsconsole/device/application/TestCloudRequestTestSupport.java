package ffdd.opsconsole.device.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import java.util.function.Supplier;

/** Narrow fixture for tests exercising business decisions independently of the real transaction tests. */
public final class TestCloudRequestTestSupport {
    private TestCloudRequestTestSupport() { }
    public static TestCloudRequests passthrough() {
        var requests=mock(TestCloudRequests.class);
        doAnswer(call->((Supplier<?>)call.getArgument(0)).get()).when(requests).outside(any());
        doAnswer(call->((Supplier<?>)call.getArgument(0)).get()).when(requests).db(any());
        return requests;
    }
}
