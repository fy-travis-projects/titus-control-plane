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

package io.netflix.titus.master.agent.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import io.netflix.titus.api.agent.model.AgentInstance;
import io.netflix.titus.api.agent.model.AgentInstanceGroup;
import io.netflix.titus.api.agent.model.AutoScaleRule;
import io.netflix.titus.api.agent.model.InstanceGroupLifecycleState;
import io.netflix.titus.api.agent.model.InstanceGroupLifecycleStatus;
import io.netflix.titus.api.agent.model.InstanceOverrideState;
import io.netflix.titus.api.agent.model.InstanceOverrideStatus;
import io.netflix.titus.api.agent.model.event.AgentEvent;
import io.netflix.titus.api.agent.model.event.AgentInstanceGroupRemovedEvent;
import io.netflix.titus.api.agent.model.event.AgentInstanceGroupUpdateEvent;
import io.netflix.titus.api.agent.model.event.AgentInstanceRemovedEvent;
import io.netflix.titus.api.agent.model.event.AgentInstanceUpdateEvent;
import io.netflix.titus.api.connector.cloud.InstanceCloudConnector;
import io.netflix.titus.api.model.Tier;
import io.netflix.titus.common.data.generator.DataGenerator;
import io.netflix.titus.common.util.tuple.Either;
import io.netflix.titus.common.util.tuple.Pair;
import io.netflix.titus.master.agent.service.vm.AgentCache;
import io.netflix.titus.master.agent.service.vm.CacheUpdateEvent;
import io.netflix.titus.master.agent.service.vm.CacheUpdateType;
import io.netflix.titus.testkit.rx.ExtTestSubscriber;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import rx.Completable;
import rx.Observable;
import rx.subjects.PublishSubject;

import static io.netflix.titus.testkit.model.agent.AgentGenerator.agentInstances;
import static io.netflix.titus.testkit.model.agent.AgentGenerator.agentServerGroups;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DefaultAgentManagementServiceTest {

    private final InstanceCloudConnector connector = mock(InstanceCloudConnector.class);

    private final AgentCache agentCache = mock(AgentCache.class);

    private final DefaultAgentManagementService service = new DefaultAgentManagementService(connector, agentCache);

    private final PublishSubject<CacheUpdateEvent> agentCacheEventSubject = PublishSubject.create();
    private final ExtTestSubscriber<AgentEvent> eventSubscriber = new ExtTestSubscriber<>();

    private DataGenerator<AgentInstance> serverGen0;
    private DataGenerator<AgentInstance> serverGen1;

    private List<AgentInstanceGroup> serverGroups;
    private final List<AgentInstance> serverSet0 = new ArrayList<>();
    private final List<AgentInstance> serverSet1 = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        this.serverGroups = agentServerGroups(Tier.Flex, 5).toList(2);
        this.serverGen0 = agentInstances(serverGroups.get(0)).apply(serverSet0::add, 5);
        this.serverGen1 = agentInstances(serverGroups.get(1)).apply(serverSet1::add, 5);

        when(agentCache.getInstanceGroups()).thenReturn(serverGroups);
        when(agentCache.getInstanceGroup(serverGroups.get(0).getId())).thenReturn(serverGroups.get(0));
        when(agentCache.getInstanceGroup(serverGroups.get(1).getId())).thenReturn(serverGroups.get(1));
        when(agentCache.getAgentInstances(serverGroups.get(0).getId())).thenReturn(new HashSet<>(serverSet0));
        when(agentCache.getAgentInstances(serverGroups.get(1).getId())).thenReturn(new HashSet<>(serverSet1));
        when(agentCache.events()).thenReturn(agentCacheEventSubject);

        when(connector.updateCapacity(any(), any(), any())).thenReturn(Completable.complete());

        service.events(false).subscribe(eventSubscriber);
    }

    @Test
    public void testFindAgentInstances() throws Exception {
        List<AgentInstanceGroup> serverGroups = service.getInstanceGroups();
        assertThat(serverGroups).hasSize(2);

        String serverGroupId0 = serverGroups.get(0).getId();

        List<Pair<AgentInstanceGroup, List<AgentInstance>>> result = service.findAgentInstances(pair -> pair.getLeft().getId().equals(serverGroupId0));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLeft().getId()).isEqualTo(serverGroups.get(0).getId());
    }

    @Test
    public void testUpdateTier() throws Exception {
        ExtTestSubscriber<Object> testSubscriber = new ExtTestSubscriber<>();
        service.updateInstanceGroupTier(serverGroups.get(0).getId(), Tier.Critical).toObservable().subscribe(testSubscriber);

        ArgumentCaptor<AgentInstanceGroup> captor = ArgumentCaptor.forClass(AgentInstanceGroup.class);
        verify(agentCache, times(1)).updateInstanceGroupStore(captor.capture());
        assertThat(captor.getValue().getTier()).isEqualTo(Tier.Critical);
    }

    @Test
    public void testUpdateAutoScalingRule() throws Exception {
        AutoScaleRule updatedAutoScaleRule = serverGroups.get(0).getAutoScaleRule().toBuilder().withMax(1000).build();

        ExtTestSubscriber<Object> testSubscriber = new ExtTestSubscriber<>();
        service.updateAutoScalingRule(serverGroups.get(0).getId(), updatedAutoScaleRule).toObservable().subscribe(testSubscriber);

        ArgumentCaptor<AgentInstanceGroup> captor = ArgumentCaptor.forClass(AgentInstanceGroup.class);
        verify(agentCache, times(1)).updateInstanceGroupStore(captor.capture());
        assertThat(captor.getValue().getAutoScaleRule()).isEqualTo(updatedAutoScaleRule);
    }

    @Test
    public void testUpdateLifecycle() throws Exception {
        InstanceGroupLifecycleStatus updatedInstanceGroupLifecycleStatus = InstanceGroupLifecycleStatus.newBuilder().withState(InstanceGroupLifecycleState.Removable).build();

        ExtTestSubscriber<Object> testSubscriber = new ExtTestSubscriber<>();
        service.updateInstanceGroupLifecycle(serverGroups.get(0).getId(), updatedInstanceGroupLifecycleStatus).toObservable().subscribe(testSubscriber);

        ArgumentCaptor<AgentInstanceGroup> captor = ArgumentCaptor.forClass(AgentInstanceGroup.class);
        verify(agentCache, times(1)).updateInstanceGroupStore(captor.capture());
        assertThat(captor.getValue().getLifecycleStatus()).isEqualTo(updatedInstanceGroupLifecycleStatus);
    }

    @Test
    public void testUpdateCapacity() throws Exception {
        ExtTestSubscriber<Object> testSubscriber = new ExtTestSubscriber<>();
        service.updateCapacity(serverGroups.get(0).getId(), Optional.of(100), Optional.of(1000)).toObservable().subscribe(testSubscriber);

        verify(connector, times(1)).updateCapacity(serverGroups.get(0).getId(), Optional.of(100), Optional.of(1000));

        ArgumentCaptor<AgentInstanceGroup> captor = ArgumentCaptor.forClass(AgentInstanceGroup.class);
        verify(agentCache, times(1)).updateInstanceGroupStoreAndSyncCloud(captor.capture());
        assertThat(captor.getValue().getMin()).isEqualTo(100);
        assertThat(captor.getValue().getDesired()).isEqualTo(1000);
    }

    @Test
    public void testUpdateOverride() throws Exception {
        String agentId = serverSet0.get(0).getId();
        when(agentCache.getAgentInstance(agentId)).thenReturn(serverSet0.get(0));

        InstanceOverrideStatus instanceOverrideStatus = InstanceOverrideStatus.newBuilder().withState(InstanceOverrideState.Quarantined).build();

        ExtTestSubscriber<Object> testSubscriber = new ExtTestSubscriber<>();
        service.updateInstanceOverride(agentId, instanceOverrideStatus).toObservable().subscribe(testSubscriber);

        ArgumentCaptor<AgentInstance> captor = ArgumentCaptor.forClass(AgentInstance.class);
        verify(agentCache, times(1)).updateAgentInstanceStore(captor.capture());
        assertThat(captor.getValue().getOverrideStatus()).isEqualTo(instanceOverrideStatus);
    }

    @Test
    public void testTerminateAgentsFromOneServerGroup() throws Exception {
        String agentId1 = serverSet0.get(0).getId();
        String agentId2 = serverSet0.get(1).getId();
        List<String> agentIds = asList(agentId1, agentId2);

        when(agentCache.getAgentInstance(agentId1)).thenReturn(serverSet0.get(0));
        when(agentCache.getAgentInstance(agentId2)).thenReturn(serverSet0.get(1));
        when(connector.terminateInstances(serverGroups.get(0).getId(), agentIds, false)).thenReturn(
                Observable.just(asList(Either.ofValue(true), Either.ofValue(true)))
        );
        when(agentCache.removeInstances(any(), any())).thenReturn(Completable.complete());

        ExtTestSubscriber<List<Either<Boolean, Throwable>>> testSubscriber = new ExtTestSubscriber<>();
        service.terminateAgents(serverGroups.get(0).getId(), agentIds, false).subscribe(testSubscriber);

        List<Either<Boolean, Throwable>> result = testSubscriber.takeNext();
        assertThat(result).hasSize(2);
        verify(agentCache, times(1)).removeInstances(any(), any());
    }

    @Test
    public void testTerminateAgentsFromDifferentServerGroups() throws Exception {
        String agentId1 = serverSet0.get(0).getId();
        String agentId2 = serverSet1.get(0).getId();
        List<String> agentIds = asList(agentId1, agentId2);

        when(agentCache.getAgentInstance(agentId1)).thenReturn(serverSet0.get(0));
        when(agentCache.getAgentInstance(agentId2)).thenReturn(serverSet1.get(0));

        ExtTestSubscriber<List<Either<Boolean, Throwable>>> testSubscriber = new ExtTestSubscriber<>();
        service.terminateAgents(serverGroups.get(0).getId(), agentIds, false).subscribe(testSubscriber);

        assertThat(testSubscriber.isError()).isTrue();
    }

    @Test
    public void testEventOnServerGroupUpdate() throws Exception {
        serverGroups.set(0, serverGroups.get(0).toBuilder().withMax(1000).build());
        agentCacheEventSubject.onNext(new CacheUpdateEvent(CacheUpdateType.ServerGroup, serverGroups.get(0).getId()));
        AgentEvent event = eventSubscriber.takeNext();
        assertThat(event).isInstanceOf(AgentInstanceGroupUpdateEvent.class);
    }

    @Test
    public void testEventOnServerGroupRemoved() throws Exception {
        String id = serverGroups.get(0).getId();
        serverGroups.remove(0);

        agentCacheEventSubject.onNext(new CacheUpdateEvent(CacheUpdateType.ServerGroup, id));
        AgentEvent event = eventSubscriber.takeNext();
        assertThat(event).isInstanceOf(AgentInstanceGroupRemovedEvent.class);
    }

    @Test
    public void testEventOnServerUpdate() throws Exception {
        serverSet0.set(0, serverSet0.get(0).toBuilder().withHostname("changed").build());
        when(agentCache.getAgentInstances(serverGroups.get(0).getId())).thenReturn(new HashSet<>(serverSet0));

        agentCacheEventSubject.onNext(new CacheUpdateEvent(CacheUpdateType.Server, serverSet0.get(0).getId()));
        AgentEvent event = eventSubscriber.takeNext();
        assertThat(event).isInstanceOf(AgentInstanceUpdateEvent.class);
    }

    @Test
    public void testEventOnServerRemovedAndGroupRefresh() throws Exception {
        serverSet0.remove(0);
        when(agentCache.getAgentInstances(serverGroups.get(0).getId())).thenReturn(new HashSet<>(serverSet0));

        agentCacheEventSubject.onNext(new CacheUpdateEvent(CacheUpdateType.ServerGroup, serverGroups.get(0).getId()));
        AgentEvent event = eventSubscriber.takeNext();
        assertThat(event).isInstanceOf(AgentInstanceRemovedEvent.class);
    }

    @Test
    public void testEventOnServerRemoved() throws Exception {
        String id = serverSet0.get(0).getId();
        serverSet0.remove(0);
        when(agentCache.getAgentInstances(serverGroups.get(0).getId())).thenReturn(new HashSet<>(serverSet0));

        agentCacheEventSubject.onNext(new CacheUpdateEvent(CacheUpdateType.Server, id));
        AgentEvent event = eventSubscriber.takeNext();
        assertThat(event).isInstanceOf(AgentInstanceRemovedEvent.class);
    }
}