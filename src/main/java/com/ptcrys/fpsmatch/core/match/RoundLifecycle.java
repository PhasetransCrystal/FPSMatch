package com.ptcrys.fpsmatch.core.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RoundLifecycle<W, R> {

    private final int waitingTicks;
    private final int roundTicks;
    private final int roundEndTicks;
    private final List<RoundRuleWithContext<W, R>> rules;
    private final Runnable onRoundStart;
    private final Consumer<RoundResult<W, R>> onRoundEnd;
    private final Runnable onNextRoundRequested;
    private final Supplier<RoundResult<W, R>> timeoutResult;
    private final Consumer<RoundContext> onWaitingTick;
    private final Consumer<RoundContext> onRoundTick;

    private RoundPhase phase = RoundPhase.WAITING;
    private RoundPhase phaseBeforePause = RoundPhase.WAITING;
    private int phaseElapsedTicks = 0;
    private int roundElapsedTicks = 0;
    private boolean paused = false;
    private boolean roundStarted = false;
    private boolean nextRoundRequested = false;
    private RoundResult<W, R> lastResult;
    private RoundContext context;

    private RoundLifecycle(Builder<W, R> builder) {
        this.waitingTicks = builder.waitingTicks;
        this.roundTicks = builder.roundTicks;
        this.roundEndTicks = builder.roundEndTicks;
        this.rules = List.copyOf(builder.rules);
        this.onRoundStart = builder.onRoundStart;
        this.onRoundEnd = builder.onRoundEnd;
        this.onNextRoundRequested = builder.onNextRoundRequested;
        this.timeoutResult = builder.timeoutResult;
        this.onWaitingTick = builder.onWaitingTick;
        this.onRoundTick = builder.onRoundTick;
    }

    public static <W, R> Builder<W, R> builder() {
        return new Builder<>();
    }

    public void tick() {
        tick(context);
    }

    public void tick(RoundContext context) {
        this.context = context;
        if (paused) {
            return;
        }
        switch (phase) {
            case WAITING -> tickWaiting();
            case ACTIVE_ROUND -> tickActiveRound();
            case ROUND_END_WAITING -> tickRoundEndWaiting();
            case PAUSED -> {}
        }
    }

    private void tickWaiting() {
        if (waitingTicks == 0) {
            startRound();
            tickActiveRound();
            return;
        }
        phaseElapsedTicks++;
        onWaitingTick.accept(context);
        if (phaseElapsedTicks >= waitingTicks) {
            startRound();
        }
    }

    private void tickActiveRound() {
        onRoundTick.accept(context);
        for (RoundRuleWithContext<W, R> rule : rules) {
            Optional<RoundResult<W, R>> result = rule.evaluate(this, context);
            if (result.isPresent()) {
                finishRound(result.get());
                return;
            }
        }
        if (roundElapsedTicks >= roundTicks) {
            RoundResult<W, R> result = timeoutResult.get();
            if (result != null) {
                finishRound(result);
                return;
            }
        }
        roundElapsedTicks++;
    }

    private void tickRoundEndWaiting() {
        phaseElapsedTicks++;
        if (phaseElapsedTicks >= roundEndTicks) {
            requestNextRound();
        }
    }

    public void resetForNextRound() {
        this.phase = RoundPhase.WAITING;
        this.phaseBeforePause = RoundPhase.WAITING;
        this.phaseElapsedTicks = 0;
        this.roundElapsedTicks = 0;
        this.paused = false;
        this.roundStarted = false;
        this.nextRoundRequested = false;
        this.lastResult = null;
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        if (paused) {
            this.phaseBeforePause = phase;
            this.phase = RoundPhase.PAUSED;
            return;
        }
        this.phase = phaseBeforePause;
    }

    public RoundPhase phase() {
        return phase;
    }

    public int phaseElapsedTicks() {
        return phaseElapsedTicks;
    }

    public int roundElapsedTicks() {
        return roundElapsedTicks;
    }

    public int roundTicks() {
        return roundTicks;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean hasRoundStarted() {
        return roundStarted;
    }

    public boolean isNextRoundRequested() {
        return nextRoundRequested;
    }

    public Optional<RoundResult<W, R>> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    public void setContext(RoundContext context) {
        this.context = context;
    }

    public RoundContext context() {
        return context;
    }

    private void startRound() {
        phase = RoundPhase.ACTIVE_ROUND;
        phaseElapsedTicks = 0;
        roundElapsedTicks = 0;
        roundStarted = true;
        onRoundStart.run();
    }

    private void finishRound(RoundResult<W, R> result) {
        lastResult = result;
        phase = RoundPhase.ROUND_END_WAITING;
        phaseElapsedTicks = 0;
        onRoundEnd.accept(result);
    }

    private void requestNextRound() {
        if (nextRoundRequested) {
            return;
        }
        nextRoundRequested = true;
        onNextRoundRequested.run();
    }

    public static class Builder<W, R> {

        private int waitingTicks;
        private int roundTicks;
        private int roundEndTicks;
        private final List<RoundRuleWithContext<W, R>> rules = new ArrayList<>();
        private Runnable onRoundStart = () -> {};
        private Consumer<RoundResult<W, R>> onRoundEnd = result -> {};
        private Runnable onNextRoundRequested = () -> {};
        private Supplier<RoundResult<W, R>> timeoutResult = () -> null;
        private Consumer<RoundContext> onWaitingTick = ctx -> {};
        private Consumer<RoundContext> onRoundTick = ctx -> {};

        public Builder<W, R> waitingTicks(int waitingTicks) {
            this.waitingTicks = Math.max(0, waitingTicks);
            return this;
        }

        public Builder<W, R> roundTicks(int roundTicks) {
            this.roundTicks = Math.max(0, roundTicks);
            return this;
        }

        public Builder<W, R> roundEndTicks(int roundEndTicks) {
            this.roundEndTicks = Math.max(0, roundEndTicks);
            return this;
        }

        public Builder<W, R> addRule(RoundRule<W, R> rule) {
            this.rules.add((lifecycle, ctx) -> rule.evaluate(lifecycle));
            return this;
        }

        public Builder<W, R> addRule(RoundRuleWithContext<W, R> rule) {
            this.rules.add(rule);
            return this;
        }

        public Builder<W, R> onRoundStart(Runnable onRoundStart) {
            this.onRoundStart = onRoundStart;
            return this;
        }

        public Builder<W, R> onRoundEnd(Consumer<RoundResult<W, R>> onRoundEnd) {
            this.onRoundEnd = onRoundEnd;
            return this;
        }

        public Builder<W, R> onNextRoundRequested(Runnable onNextRoundRequested) {
            this.onNextRoundRequested = onNextRoundRequested;
            return this;
        }

        public Builder<W, R> timeoutResult(Supplier<RoundResult<W, R>> timeoutResult) {
            this.timeoutResult = timeoutResult;
            return this;
        }

        public Builder<W, R> onWaitingTick(Consumer<RoundContext> onWaitingTick) {
            this.onWaitingTick = onWaitingTick;
            return this;
        }

        public Builder<W, R> onRoundTick(Consumer<RoundContext> onRoundTick) {
            this.onRoundTick = onRoundTick;
            return this;
        }

        public RoundLifecycle<W, R> build() {
            return new RoundLifecycle<>(this);
        }
    }
}
