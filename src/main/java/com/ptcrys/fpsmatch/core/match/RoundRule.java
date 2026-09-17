package com.ptcrys.fpsmatch.core.match;

import java.util.Optional;

@FunctionalInterface
public interface RoundRule<W, R> {

    Optional<RoundResult<W, R>> evaluate(RoundLifecycle<W, R> lifecycle);
}
