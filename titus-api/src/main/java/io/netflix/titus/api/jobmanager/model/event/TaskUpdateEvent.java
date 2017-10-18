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

package io.netflix.titus.api.jobmanager.model.event;

import java.util.Optional;

import io.netflix.titus.api.jobmanager.model.job.Task;
import io.netflix.titus.api.jobmanager.service.common.action.TitusModelUpdateAction;
import io.netflix.titus.common.framework.reconciler.ModelUpdateAction;

/**
 */
public class TaskUpdateEvent extends TaskEvent {
    private final Optional<Task> task;
    private final Optional<Task> previousTaskVersion;
    private final ModelUpdateAction.Model model;

    public TaskUpdateEvent(EventType eventType,
                           TitusModelUpdateAction updateAction,
                           Optional<Task> task,
                           Optional<Task> previousTaskVersion,
                           Optional<Throwable> error) {
        super(eventType, updateAction, error);
        this.task = task;
        this.model = updateAction.getModel();
        this.previousTaskVersion = previousTaskVersion;
    }

    public Optional<Task> getTask() {
        return task;
    }

    public Optional<Task> getPreviousTaskVersion() {
        return previousTaskVersion;
    }

    public ModelUpdateAction.Model getModel() {
        return model;
    }

    public String toLogString() {
        return String.format("%-12s %s %-30s %-10s %-29s %-19s %-20s %s",
                "target=Task,",
                "id=" + getId() + ',',
                "eventType=" + getEventType() + ',',
                "status=" + getError().map(e -> "ERROR").orElse("OK") + ',',
                "action=" + getActionName() + ',',
                "trigger=" + getTrigger() + ',',
                "model=" + getModel() + ',',
                "summary=" + getError().map(e -> "ERROR: " + getSummary() + '(' + e.getMessage() + ')').orElse(getSummary())

        );
    }
}