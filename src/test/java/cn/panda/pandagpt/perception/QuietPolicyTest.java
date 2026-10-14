package cn.panda.pandagpt.perception;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class QuietPolicyTest {
    private QuietPolicy.Candidate candidate(String key, long expires, boolean urgent) {
        return new QuietPolicy.Candidate(
                urgent ? QuietPolicy.Kind.LOCAL_RISK : QuietPolicy.Kind.READY,
                key,
                cn.panda.pandagpt.i18n.Msg.of(key),
                "IRON_PICKAXE",
                UUID.randomUUID(),
                null,
                expires,
                urgent ? 100 : 20,
                urgent);
    }

    @Test
    void silenceIsNormalAndCandidatesAreDeduplicatedAcrossPolling() {
        var policy = new QuietPolicy(2, 100);
        assertNull(policy.poll(1000, false, false, c -> true));
        policy.offer(candidate("ready", 10000, false), 1000);
        assertNotNull(policy.poll(1000, false, false, c -> true));
        policy.offer(candidate("ready", 10000, false), 2000);
        assertNull(policy.poll(2000, false, false, c -> true));
    }

    @Test
    void busyPlayersGetOneUsefulCandidateLaterButExpiredOrInvalidSuggestionsAreDiscarded() {
        var policy = new QuietPolicy(2, 100);
        policy.offer(candidate("table", 5000, false), 1000);
        assertNull(policy.poll(1000, false, true, c -> true));
        assertNotNull(policy.poll(2000, false, false, c -> true));
        policy.offer(candidate("blocked", 3000, false), 2500);
        assertNull(policy.poll(4000, false, false, c -> true));
        policy.offer(candidate("materials", 10000, false), 5000);
        assertNull(policy.poll(6000, false, false, c -> false));
        assertNull(policy.pending());
    }

    @Test
    void routineBudgetAndSpacingDoNotSuppressUrgentRisksAndQuietModeCancelsPending() {
        var policy = new QuietPolicy(2, 1000);
        policy.offer(candidate("one", 100000, false), 1000);
        assertNotNull(policy.poll(1000, false, false, c -> true));
        policy.offer(candidate("two", 100000, false), 1500);
        assertNull(policy.poll(1500, false, false, c -> true));
        assertNotNull(policy.poll(2000, false, false, c -> true));
        policy.offer(candidate("three", 100000, false), 4000);
        assertNull(policy.poll(4000, false, false, c -> true));
        policy.offer(candidate("lava", 100000, true), 4001);
        assertNotNull(policy.poll(4001, false, true, c -> true));
        policy.offer(candidate("another", 100000, false), 5000);
        assertNull(policy.poll(5000, true, false, c -> true));
        assertNull(policy.pending());
    }
}
