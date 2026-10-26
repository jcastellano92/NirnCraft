#pragma once
#include <cstddef>
#include <vector>

// A bounded slice of a queue of work: the front of `items` (the oldest first) is applied until `over()` says the
// slice's time is up, at least `minN` of them (it always progresses); the rest stay in `items`, in order. Returns how
// many were applied. (World::packedDone: the render thread applied a whole backlog of pack results under the world's
// mutex, 0.5 to 2.2 s a frame, 2026-10-26.)
template <class T, class Apply, class Over>
size_t applySlice(std::vector<T>& items, size_t minN, Apply apply, Over over) {
    size_t i = 0;
    for (; i < items.size(); i++) {
        if (i >= minN && over()) break;
        apply(items[i]);
    }
    items.erase(items.begin(), items.begin() + std::ptrdiff_t(i));
    return i;
}
