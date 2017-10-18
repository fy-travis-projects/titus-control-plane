/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.common.util.spectator;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;

/**
 * Metric collector for a Finite State Machine (FSM). It reports a current state of an FSM, and the state transitions.
 * If FSM reaches the terminal state, the current state indicators are cleared, to prevent infinite accumulation.
 */
class FsmMetricsImpl<S> implements SpectatorExt.FsmMetrics<S> {

    private final List<StateHolder<S>> stateHolders;

    FsmMetricsImpl(Id rootId,
                   List<S> trackedStates,
                   Function<S, String> nameOf,
                   Function<S, Boolean> finalStateEval,
                   Registry registry) {
        Id currentStateId = registry.createId(rootId.name() + "currentState", rootId.tags());
        Id updatesId = registry.createId(rootId.name() + "updates", rootId.tags());
        this.stateHolders = trackedStates.stream()
                .map(s -> new StateHolder<>(updatesId, currentStateId, nameOf.apply(s), s, finalStateEval.apply(s), registry))
                .collect(Collectors.toList());
    }

    @Override
    public void transition(S nextState) {
        stateHolders.forEach(h -> h.moveToState(nextState));
    }

    private static class StateHolder<S> {
        private final S state;
        private final Counter stateCounter;
        private final AtomicInteger currentState;

        private StateHolder(Id updatesId, Id currentStateId, String stateName, S state, boolean isFinal, Registry registry) {
            this.state = state;
            this.stateCounter = registry.counter(updatesId.withTag("state", stateName));
            this.currentState = isFinal
                    ? new AtomicInteger()
                    : registry.gauge(currentStateId.withTag("state", stateName), new AtomicInteger());
        }

        private void moveToState(S nextState) {
            if (nextState.equals(state)) {
                if (currentState.getAndSet(1) == 0) { // Actual transition
                    stateCounter.increment();
                }
            } else {
                currentState.set(0);
            }
        }
    }
}