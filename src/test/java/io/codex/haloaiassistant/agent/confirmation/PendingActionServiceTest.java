package io.codex.haloaiassistant.agent.confirmation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PendingActionServiceTest {

    @Test
    void pendingActionCanOnlyBeClaimedOnce() throws Exception {
        PendingActionService service = new PendingActionService();
        String id = service.create("createArticle", "test", "test", RiskLevel.MEDIUM,
                new ObjectMapper().createObjectNode().put("title", "demo"))
                .getConfirmationId();

        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = IntStream.range(0, 32)
                    .mapToObj(i -> (Callable<PendingAction>) () -> service.confirm(id))
                    .toList();
            long claimed = pool.invokeAll(tasks).stream()
                    .filter(future -> {
                        try {
                            return future.get() != null;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .count();
            assertEquals(1, claimed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pendingActionIsOnlyVisibleAndClaimableByRequester() {
        PendingActionService service = new PendingActionService();
        var payload = new ObjectMapper().createObjectNode();
        payload.put("title", "demo");
        payload.put("_requester", "alice");
        String id = service.create("createArticle", "test", "test", RiskLevel.MEDIUM, payload)
                .getConfirmationId();

        assertNull(service.get(id, "bob"));
        assertEquals(false, service.cancel(id, "bob"));
        assertEquals(0, service.listPending("bob").size());
        assertEquals(1, service.listPending("alice").size());
        assertEquals("alice", service.confirm(id).getRequester());
    }
}
