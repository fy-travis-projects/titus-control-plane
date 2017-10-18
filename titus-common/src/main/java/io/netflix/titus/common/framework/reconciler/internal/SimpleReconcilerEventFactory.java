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

package io.netflix.titus.common.framework.reconciler.internal;

import java.util.Optional;

import io.netflix.titus.common.framework.reconciler.ChangeAction;
import io.netflix.titus.common.framework.reconciler.EntityHolder;
import io.netflix.titus.common.framework.reconciler.ModelUpdateAction;
import io.netflix.titus.common.framework.reconciler.ReconcilerEvent;

/**
 */
public class SimpleReconcilerEventFactory implements ReconcilerEvent.ReconcileEventFactory {
    @Override
    public ReconcilerEvent newChangeEvent(ReconcilerEvent.EventType eventType, ChangeAction changeAction, Optional<Throwable> error) {
        return new SimpleReconcilerEvent(eventType, changeAction.toString(), error);
    }

    @Override
    public ReconcilerEvent newModelUpdateEvent(ReconcilerEvent.EventType eventType,
                                               ModelUpdateAction modelUpdateAction,
                                               Optional<EntityHolder> changedEntityHolder,
                                               Optional<EntityHolder> previousEntityHolder,
                                               Optional<Throwable> error) {
        return new SimpleReconcilerEvent(eventType, modelUpdateAction == null ? "Init" : modelUpdateAction.toString(), error);
    }
}