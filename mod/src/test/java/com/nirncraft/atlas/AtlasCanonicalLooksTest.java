package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AtlasCanonicalLooksTest {
    private record State(int id, List<Object> look, int flags, int material) {}

    // Equal keys model equivalent appearances; the remaining components stand in for separate ingest words.
    private static int[] ingest(List<State> states, List<State> arrival) {
        var aliases = new AtlasCanonicalLooks<List<Object>>();
        for (State state : arrival) aliases.add(state.look(), state.id());
        int[] words = new int[2 + states.size() * 3];
        words[0] = states.size();
        words[1] = 1;
        for (int i = 0; i < states.size(); i++) {
            State s = states.get(i);
            words[2 + i * 3] = aliases.id(s.look());
            words[3 + i * 3] = s.flags();
            words[4 + i * 3] = s.material();
        }
        return words;
    }

    @Test
    void reversedAndInterleavedArrivalKeepsAliasesAndIngestWitness() throws Exception {
        Object block = new Object(), model = new Object(), otherModel = new Object();
        List<State> states = List.of(
                new State(41, List.of(block, model, 1, 0, true), 2, 100),
                new State(7, List.of(block, model, 1, 0, true), 2, 100),
                new State(30, List.of(block, otherModel, 1, 0, true), 2, 101),
                new State(31, List.of(block, model, 2, 0, true), 2, 102),
                new State(32, List.of(block, model, 1, 7, true), 2, 103),
                new State(33, List.of(block, model, 1, 0, false), 4, 104));
        List<State> reversed = new ArrayList<>(states);
        Collections.reverse(reversed);
        int[] expected = ingest(states, states);
        assertArrayEquals(expected, ingest(states, reversed));
        assertEquals(7, expected[2]);
        assertEquals(7, expected[5]);
        assertEquals(30, expected[8]);
        assertEquals(31, expected[11]);
        assertEquals(32, expected[14]);
        assertEquals(33, expected[17]);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> ingest(states, List.of(states.get(2), states.get(0), states.get(4), states.get(1), states.get(5), states.get(3))));
            var second = pool.submit(() -> ingest(states, reversed));
            assertArrayEquals(expected, first.get());
            assertArrayEquals(expected, second.get());
            assertEquals(AtlasCanonicalLooks.fingerprint(expected, 0, 1, expected.length),
                    AtlasCanonicalLooks.fingerprint(second.get(), 0, 1, expected.length));
        }
    }

    @Test
    void noModelStatesRemainDistinctAndMissingKeysFail() {
        Object a = new Object(), b = new Object();
        var groups = new AtlasCanonicalLooks<Object>();
        groups.add(a, 90); groups.add(b, 11);
        assertEquals(90, groups.id(a));
        assertEquals(11, groups.id(b));
        assertThrows(IllegalArgumentException.class, () -> groups.id(new Object()));
    }

    @Test
    void resetAndModelSwapIsolateInFlightTables() throws Exception {
        var epochs = new AtlasCanonicalLooks.Epochs<String, String>();
        Object oldModels = new Object(), newModels = new Object();
        var old = epochs.forModels(oldModels);
        epochs.reset();
        var afterReset = epochs.forModels(oldModels);
        assertNotSame(old, afterReset);
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> old.blocks.putIfAbsent("block", Map.of("state", 7))).get();
        }
        assertTrue(afterReset.blocks.isEmpty());
        afterReset.blocks.put("block", Map.of("state", 11));
        var afterReload = epochs.forModels(newModels);
        assertNotSame(afterReset, afterReload);
        assertTrue(afterReload.blocks.isEmpty());
        // A caller paused after reading oldModels can resume after a new-model lookup, but cannot roll it back.
        assertSame(afterReset, epochs.forModels(oldModels));
        assertSame(afterReload, epochs.forModels(newModels));
        assertEquals(7, old.blocks.get("block").get("state"));
    }

    @Test
    void staleCallerCannotReplaceNewModelEpoch() throws Exception {
        var epochs = new AtlasCanonicalLooks.Epochs<String, String>();
        Object oldModels = new Object(), newModels = new Object();
        var captured = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale = pool.submit(() -> {
                Object observed = oldModels;
                captured.countDown();
                resume.await();
                return epochs.forModels(observed);
            });
            captured.await();
            var latest = epochs.forModels(newModels);
            latest.blocks.put("block", Map.of("state", 42));
            resume.countDown();
            assertNotSame(latest, stale.get());
            assertSame(latest, epochs.forModels(newModels));
            assertEquals(42, epochs.forModels(newModels).blocks.get("block").get("state"));
        }
    }
}
