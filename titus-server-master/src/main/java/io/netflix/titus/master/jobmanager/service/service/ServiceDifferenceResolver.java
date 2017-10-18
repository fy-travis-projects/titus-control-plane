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

package io.netflix.titus.master.jobmanager.service.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.netflix.titus.api.jobmanager.model.event.JobManagerEvent.Trigger;
import io.netflix.titus.api.jobmanager.model.job.Job;
import io.netflix.titus.api.jobmanager.model.job.JobState;
import io.netflix.titus.api.jobmanager.model.job.ServiceJobTask;
import io.netflix.titus.api.jobmanager.model.job.TaskState;
import io.netflix.titus.api.jobmanager.model.job.TaskStatus;
import io.netflix.titus.api.jobmanager.model.job.ext.ServiceJobExt;
import io.netflix.titus.api.jobmanager.service.common.action.TitusChangeAction;
import io.netflix.titus.api.jobmanager.store.JobStore;
import io.netflix.titus.common.framework.reconciler.ChangeAction;
import io.netflix.titus.common.framework.reconciler.EntityHolder;
import io.netflix.titus.common.framework.reconciler.ReconciliationEngine;
import io.netflix.titus.common.util.limiter.ImmutableLimiters;
import io.netflix.titus.common.util.retry.Retryers;
import io.netflix.titus.common.util.time.Clock;
import io.netflix.titus.common.util.time.Clocks;
import io.netflix.titus.master.VirtualMachineMasterService;
import io.netflix.titus.master.jobmanager.service.JobManagerConfiguration;
import io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils;
import io.netflix.titus.master.jobmanager.service.common.action.job.WriteJobAction;
import io.netflix.titus.master.jobmanager.service.common.action.task.InitiateTaskKillAction;
import io.netflix.titus.master.jobmanager.service.common.action.task.StartNewTaskAction;
import io.netflix.titus.master.jobmanager.service.common.action.task.WriteTaskAction;
import io.netflix.titus.master.jobmanager.service.common.interceptor.RateLimiterInterceptor;
import io.netflix.titus.master.jobmanager.service.common.interceptor.RetryActionInterceptor;
import io.netflix.titus.master.jobmanager.service.service.action.RemoveServiceTaskAction;
import io.netflix.titus.master.scheduler.SchedulingService;
import io.netflix.titus.master.service.management.ApplicationSlaManagementService;
import rx.schedulers.Schedulers;

import static io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils.areEquivalent;
import static io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils.findTaskStateTimeouts;
import static io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils.hasJobState;
import static io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils.hasReachedRetryLimit;
import static io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils.isTerminating;
import static io.netflix.titus.master.jobmanager.service.common.DifferenceResolverUtils.removeCompletedJob;
import static io.netflix.titus.master.jobmanager.service.service.action.CreateOrReplaceServiceTaskAction.createOrReplaceTaskAction;


@Singleton
public class ServiceDifferenceResolver implements ReconciliationEngine.DifferenceResolver {

    private static final long NEW_TASK_BUCKET = 10;
    private static final long NEW_TASK_REFILL_INTERVAL_MS = 100;

    private final JobManagerConfiguration configuration;
    private final ApplicationSlaManagementService capacityGroupService;
    private final SchedulingService schedulingService;
    private final VirtualMachineMasterService vmService;
    private final JobStore titusStore;

    private final RetryActionInterceptor storeWriteRetryInterceptor;
    private final RateLimiterInterceptor newTaskRateLimiterInterceptor;

    private final Clock clock = Clocks.system();

    @Inject
    public ServiceDifferenceResolver(
            JobManagerConfiguration configuration,
            ApplicationSlaManagementService capacityGroupService,
            SchedulingService schedulingService,
            VirtualMachineMasterService vmService,
            JobStore titusStore) {
        this.configuration = configuration;
        this.capacityGroupService = capacityGroupService;
        this.schedulingService = schedulingService;
        this.vmService = vmService;
        this.titusStore = titusStore;

        this.storeWriteRetryInterceptor = new RetryActionInterceptor(
                "storeWrite",
                Retryers.exponentialBackoff(5000, 5000, TimeUnit.MILLISECONDS),
                Schedulers.computation()
        );

        this.newTaskRateLimiterInterceptor = new RateLimiterInterceptor(
                "newTask",
                ImmutableLimiters.tokenBucket(
                        NEW_TASK_BUCKET,
                        ImmutableLimiters.refillAtFixedInterval(1, NEW_TASK_REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                )
        );
    }

    @Override
    public List<ChangeAction> apply(EntityHolder referenceModel, EntityHolder runningModel, EntityHolder storeModel) {
        List<ChangeAction> actions = new ArrayList<>();
        ServiceJobView refJobView = new ServiceJobView(referenceModel);
        actions.addAll(applyRuntime(refJobView, runningModel, storeModel));
        actions.addAll(applyStore(refJobView, storeModel));

        if (actions.isEmpty()) {
            actions.addAll(removeCompletedJob(referenceModel, storeModel, titusStore));
        }

        return actions;
    }

    private List<ChangeAction> applyRuntime(ServiceJobView refJobView, EntityHolder runningModel, EntityHolder storeModel) {
        List<ChangeAction> actions = new ArrayList<>();
        EntityHolder referenceModel = refJobView.getJobHolder();
        ServiceJobView runningJobView = new ServiceJobView(runningModel);

        if (hasJobState(referenceModel, JobState.KillInitiated)) {
            return InitiateTaskKillAction.applyKillInitiated(runningJobView, vmService);
        } else if (hasJobState(referenceModel, JobState.Finished)) {
            return Collections.emptyList();
        }

        actions.addAll(findJobSizeInconsistencies(refJobView, storeModel));
        actions.addAll(findMissingRunningTasks(refJobView, runningJobView));
        actions.addAll(findTaskStateTimeouts(runningJobView, configuration, clock, vmService));

        return actions;
    }

    /**
     * Check that the reference job has the required number of tasks.
     */
    private List<ChangeAction> findJobSizeInconsistencies(ServiceJobView refJobView, EntityHolder storeModel) {
        boolean canUpdateStore = storeWriteRetryInterceptor.executionLimits(storeModel);
        List<ServiceJobTask> tasks = refJobView.getTasks();
        int missing = refJobView.getRequiredSize() - tasks.size();
        if (canUpdateStore && missing > 0) {
            List<ChangeAction> missingTasks = new ArrayList<>();
            for (int i = 0; i < missing; i++) {
                missingTasks.add(createNewTaskAction(refJobView, Optional.empty()));
            }
            return missingTasks;
        } else if (missing < 0) {
            // Too many tasks (job was scaled down)
            int finishedCount = (int) tasks.stream().filter(DifferenceResolverUtils::isTerminating).count();
            int toRemoveCount = -missing - finishedCount;
            if (toRemoveCount > 0) {
                List<ChangeAction> toRemove = new ArrayList<>();
                for (int i = tasks.size() - 1; toRemoveCount > 0 && i >= 0; i--) {
                    ServiceJobTask next = tasks.get(i);
                    if (!isTerminating(next)) {
                        toRemove.add(new InitiateTaskKillAction(Trigger.Reconciler, next, false, vmService, TaskStatus.REASON_SCALED_DOWN, "Terminating excessive service job task"));
                        toRemoveCount--;
                    }
                }
                return toRemove;
            }
        }
        return Collections.emptyList();
    }

    private TitusChangeAction createNewTaskAction(ServiceJobView refJobView, Optional<ServiceJobTask> previousTask) {
        return newTaskRateLimiterInterceptor.apply(
                storeWriteRetryInterceptor.apply(
                        createOrReplaceTaskAction(titusStore, refJobView.getJob(), refJobView.getTasks(), previousTask)
                )
        );
    }

    /**
     * Check that for each reference job task, there is a corresponding running task.
     */
    private List<ChangeAction> findMissingRunningTasks(ServiceJobView refJobView, ServiceJobView runningJobView) {
        List<ChangeAction> missingTasks = new ArrayList<>();
        long allowedToRun = newTaskRateLimiterInterceptor.executionLimits(runningJobView.getJobHolder());
        List<ServiceJobTask> tasks = refJobView.getTasks();
        for (int i = 0; i < tasks.size() && allowedToRun > 0; i++) {
            ServiceJobTask refTask = tasks.get(i);
            ServiceJobTask runningTask = runningJobView.getTaskById(refTask.getId());
            if (runningTask == null) {
                missingTasks.add(new StartNewTaskAction(capacityGroupService, schedulingService, runningJobView.getJob(), refTask));
                allowedToRun--;
            }
        }
        return missingTasks;
    }

    private List<ChangeAction> applyStore(ServiceJobView refJobView, EntityHolder storeJob) {
        if (!storeWriteRetryInterceptor.executionLimits(storeJob)) {
            return Collections.emptyList();
        }

        List<ChangeAction> actions = new ArrayList<>();

        EntityHolder refJobHolder = refJobView.getJobHolder();
        Job<ServiceJobExt> refJob = refJobHolder.getEntity();

        if (!refJobHolder.getEntity().equals(storeJob.getEntity())) {
            actions.add(storeWriteRetryInterceptor.apply(new WriteJobAction(titusStore, refJobHolder.getEntity())));
        }
        boolean isJobTerminating = refJob.getStatus().getState() == JobState.KillInitiated;
        for (EntityHolder referenceTask : refJobHolder.getChildren()) {
            Optional<EntityHolder> storeHolder = storeJob.findById(referenceTask.getId());
            boolean refAndStoreInSync = storeHolder.isPresent() && areEquivalent(storeHolder.get(), referenceTask);
            if (refAndStoreInSync) {
                ServiceJobTask task = storeHolder.get().getEntity();
                if (task.getStatus().getState() == TaskState.Finished) {
                    if (isJobTerminating || task.getStatus().getReasonCode().equals(TaskStatus.REASON_SCALED_DOWN)) {
                        actions.add(new RemoveServiceTaskAction(task));
                    } else if (!hasReachedRetryLimit(refJob, task)) {
                        actions.add(createNewTaskAction(refJobView, Optional.of(task)));
                    }
                }
            } else {
                actions.add(storeWriteRetryInterceptor.apply(new WriteTaskAction(titusStore, schedulingService, capacityGroupService, refJob, referenceTask.getEntity())));
            }
        }
        return actions;
    }

    private static class ServiceJobView extends DifferenceResolverUtils.JobView<ServiceJobExt, ServiceJobTask> {
        ServiceJobView(EntityHolder jobHolder) {
            super(jobHolder);
        }
    }
}