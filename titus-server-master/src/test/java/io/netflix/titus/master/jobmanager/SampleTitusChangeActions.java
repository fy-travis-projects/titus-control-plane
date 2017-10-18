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

package io.netflix.titus.master.jobmanager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netflix.titus.api.jobmanager.model.event.JobManagerEvent;
import io.netflix.titus.api.jobmanager.service.common.action.ActionKind;
import io.netflix.titus.api.jobmanager.service.common.action.JobChange;
import io.netflix.titus.api.jobmanager.service.common.action.TitusChangeAction;
import io.netflix.titus.common.framework.reconciler.ModelUpdateAction;
import io.netflix.titus.common.util.tuple.Pair;
import rx.Observable;

/**
 * A collection of {@link TitusChangeAction}s, and methods for testing.
 */
public final class SampleTitusChangeActions {


    public static TitusChangeAction successfulJob() {
        return new SuccessfulChangeAction(ActionKind.Job, JobManagerEvent.Trigger.API, "jobId");
    }

    public static TitusChangeAction failingJob(int failureCount) {
        return new FailingChangeAction(ActionKind.Job, JobManagerEvent.Trigger.API, "jobId", failureCount);
    }

    private static class SuccessfulChangeAction extends TitusChangeAction {

        private SuccessfulChangeAction(ActionKind actionKind, JobManagerEvent.Trigger trigger, String id) {
            super(new JobChange(actionKind, trigger, id, "Simulated successful action"));
        }

        @Override
        public Observable<Pair<JobChange, List<ModelUpdateAction>>> apply() {
            return Observable.just(Pair.of(getChange(), Collections.emptyList()));
        }
    }

    private static class FailingChangeAction extends TitusChangeAction {

        private final AtomicInteger failureCounter;

        protected FailingChangeAction(ActionKind actionKind, JobManagerEvent.Trigger trigger, String id, int failureCount) {
            super(new JobChange(actionKind, trigger, id, "Simulated initial failure repeated " + failureCount + " times"));
            this.failureCounter = new AtomicInteger(failureCount);
        }

        @Override
        public Observable<Pair<JobChange, List<ModelUpdateAction>>> apply() {
            if (failureCounter.decrementAndGet() >= 0) {
                return Observable.error(new RuntimeException("Simulated failure; remaining failures=" + failureCounter.get()));
            }
            return Observable.just(Pair.of(getChange(), Collections.emptyList()));
        }
    }
}