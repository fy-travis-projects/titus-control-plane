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

package io.netflix.titus.master;

import java.util.List;

import com.netflix.fenzo.VirtualMachineLease;
import com.netflix.fenzo.functions.Action1;
import io.netflix.titus.api.store.v2.V2WorkerMetadata;
import org.apache.mesos.Protos;
import rx.Observable;
import rx.functions.Func0;

public interface VirtualMachineMasterService {

    void launchTasks(List<Protos.TaskInfo> requests, List<VirtualMachineLease> leases);

    void rejectLease(VirtualMachineLease lease);

    void setRunningWorkersGetter(Func0<List<V2WorkerMetadata>> runningWorkersGetter);

    void killTask(String taskId);

    void setVMLeaseHandler(Action1<List<? extends VirtualMachineLease>> leaseHandler);

    Observable<String> getLeaseRescindedObservable();

    Observable<Status> getTaskStatusObservable();
}