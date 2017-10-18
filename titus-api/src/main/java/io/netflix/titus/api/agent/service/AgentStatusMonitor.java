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

package io.netflix.titus.api.agent.service;

import java.util.Optional;

import io.netflix.titus.api.agent.model.monitor.AgentStatus;
import rx.Observable;

/**
 * {@link AgentStatusMonitor} provides information about perceived agent health status. This information
 * may come from many different sources.
 */
public interface AgentStatusMonitor {

    /**
     * Return current health status for an agent with the given id.
     *
     * @return {@link Optional#empty()} if status is not known
     * @throws AgentManagementException {@link AgentManagementException.ErrorCode#AgentNotFound} if the agent instance is not found
     */
    default Optional<AgentStatus> getCurrent(String agentInstanceId) {
        return Optional.empty();
    }

    /**
     * Observable that emits {@link AgentStatus}es. If {@link AgentStatus.AgentStatusCode#Healthy} is emitted, the agent
     * is perceived as healthy, and good to use. If {@link AgentStatus.AgentStatusCode#Unhealthy} is emitted, the agent
     * should not be used for the amount of time indicated by the {@link AgentStatus#getDisableTime()} value. If
     * 'healthy' status is received within disable period, it invalidates it. If disable period expires, the agent is
     * assumed to be good again, even if no {@link AgentStatus.AgentStatusCode#Healthy} was received.
     */
    Observable<AgentStatus> monitor();
}