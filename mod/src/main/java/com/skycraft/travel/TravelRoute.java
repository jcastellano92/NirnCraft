package com.skycraft.travel;

import java.util.List;
import java.util.Optional;

/**
 * A travel link between two {@link TravelNode}s along a named road, sea lane, river, or Dwemer shaft.
 */
public record TravelRoute(
        String from,
        String to,
        String province,
        TravelMode mode,
        int fee,
        int durationSeconds,
        String path,
        boolean bidirectional,
        List<String> why
) {
    public boolean connects(String originId, String destId) {
        if (from.equals(originId) && to.equals(destId)) return true;
        return bidirectional && to.equals(originId) && from.equals(destId);
    }

    public Optional<String> otherEnd(String nodeId) {
        if (from.equals(nodeId)) return Optional.of(to);
        if (bidirectional && to.equals(nodeId)) return Optional.of(from);
        return Optional.empty();
    }
}
