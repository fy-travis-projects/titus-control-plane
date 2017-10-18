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

package io.netflix.titus.master.service.management.internal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.netflix.spectator.api.Registry;
import io.netflix.titus.api.model.ApplicationSLA;
import io.netflix.titus.api.model.Tier;
import io.netflix.titus.common.util.guice.ProxyType;
import io.netflix.titus.common.util.guice.annotation.Activator;
import io.netflix.titus.common.util.guice.annotation.ProxyConfiguration;
import io.netflix.titus.common.util.rx.ComputationTaskInvoker;
import io.netflix.titus.common.util.tuple.Pair;
import io.netflix.titus.master.service.management.CapacityAllocationService;
import io.netflix.titus.master.service.management.CapacityGuaranteeStrategy;
import io.netflix.titus.master.service.management.CapacityGuaranteeStrategy.CapacityAllocations;
import io.netflix.titus.master.service.management.CapacityGuaranteeStrategy.CapacityRequirements;
import io.netflix.titus.master.service.management.CapacityGuaranteeStrategy.InstanceTypeLimit;
import io.netflix.titus.master.service.management.CapacityManagementConfiguration;
import io.netflix.titus.master.service.management.CapacityMonitoringService;
import io.netflix.titus.master.store.ApplicationSlaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.schedulers.Schedulers;

import static io.netflix.titus.common.util.CollectionsExt.zipToMap;

@Singleton
@ProxyConfiguration(types = ProxyType.ActiveGuard)
public class DefaultCapacityMonitoringService implements CapacityMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCapacityMonitoringService.class);

    // An upper bound on the update execution time.
    /* Visible for testing */ static final long UPDATE_TIMEOUT_MS = 30 * 1000;

    /**
     * Schedule periodically capacity dimensioning updates, to override any changes made by external actors.
     * (for example, a user doing manual override).
     */
    /* Visible for testing */ static final long PERIODIC_UPDATE_INTERVAL_MS = 5 * 60 * 1000;

    private final CapacityAllocationService capacityAllocationService;
    private final CapacityManagementConfiguration configuration;
    private final CapacityGuaranteeStrategy strategy;

    private final ApplicationSlaStore storage;

    private final Scheduler scheduler;
    private final ComputationTaskInvoker<Void> invoker;
    private final DefaultCapacityMonitoringServiceMetrics metrics;

    private Subscription periodicUpdateSubscription;

    public DefaultCapacityMonitoringService(CapacityAllocationService capacityAllocationService,
                                            CapacityManagementConfiguration configuration,
                                            CapacityGuaranteeStrategy strategy,
                                            ApplicationSlaStore storage,
                                            Registry registry,
                                            Scheduler scheduler) {
        this.capacityAllocationService = capacityAllocationService;
        this.configuration = configuration;
        this.strategy = strategy;
        this.storage = storage;
        this.invoker = new ComputationTaskInvoker<>(Observable.create(subscriber -> updateAction(subscriber)), scheduler);
        this.scheduler = scheduler;
        this.metrics = new DefaultCapacityMonitoringServiceMetrics(registry);
    }

    @Inject
    public DefaultCapacityMonitoringService(CapacityAllocationService capacityAllocationService,
                                            CapacityManagementConfiguration configuration,
                                            CapacityGuaranteeStrategy strategy,
                                            ApplicationSlaStore storage,
                                            Registry registry) {
        this(capacityAllocationService, configuration, strategy, storage, registry, Schedulers.computation());
    }

    @PreDestroy
    public void shutdown() {
        if (periodicUpdateSubscription != null) {
            periodicUpdateSubscription.unsubscribe();
        }
    }

    @Activator
    public Observable<Void> enterActiveMode() {
        logger.info("Entering active mode");
        schedulePeriodicUpdate();
        return Observable.empty();
    }

    @Override
    public Observable<Void> refresh() {
        return invoker.recompute();
    }

    private void schedulePeriodicUpdate() {
        this.periodicUpdateSubscription = Observable.interval(0, PERIODIC_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS, scheduler)
                .subscribe(tick ->
                        invoker.recompute()
                                .subscribe(
                                        none -> {
                                        },
                                        e -> logger.error("Scheduled capacity guarantees refresh failed: {}", e.getMessage()),
                                        () -> logger.debug("Scheduled capacity guarantees refresh finished")
                                )
                );
    }

    private void updateAction(Subscriber<? super Void> result) {
        try {
            long startTime = scheduler.now();

            List<String> instanceTypes = ConfigUtil.getAllInstanceTypes(configuration);
            List<String> scalableInstanceTypes = ConfigUtil.getTierInstanceTypes(Tier.Critical, configuration);

            Observable<Map<String, InstanceTypeLimit>> instanceTypeLimits = capacityAllocationService.limits(instanceTypes)
                    .map(maxLimits -> buildInstanceTypeLimits(instanceTypes, maxLimits))
                    .map(limits -> zipToMap(instanceTypes, limits));

            // Compute capacity allocations for all tiers, and for those tiers that are scaled (now only Critical).
            // We need full capacity allocation for alerting.
            Observable<Pair<CapacityAllocations, CapacityAllocations>> allocationsObservable = Observable.zip(
                    storage.findAll().toList(),
                    instanceTypeLimits,
                    (allSLAs, limits) -> new Pair<>(
                            recompute(allSLAs, limits),
                            recompute(scalableSLAs(allSLAs), scalableInstanceTypeLimits(limits, scalableInstanceTypes))
                    )
            );

            Observable<Void> updateStatus = allocationsObservable.flatMap(allocationPair -> {
                CapacityAllocations allTiersAllocation = allocationPair.getLeft();
                CapacityAllocations scaledAllocations = allocationPair.getRight();

                metrics.recordResourceShortage(allTiersAllocation);

                List<Observable<Void>> updates = new ArrayList<>();
                scaledAllocations.getInstanceTypes().forEach(instanceType -> {
                            int minSize = scaledAllocations.getExpectedMinSize(instanceType);
                            updates.add(capacityAllocationService.allocate(instanceType, minSize));
                        }
                );
                return Observable.merge(updates);
            });

            updateStatus
                    .timeout(UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS, scheduler)
                    .subscribe(
                            never -> {
                            },
                            e -> {
                                metrics.getUpdateExecutionMetrics().failure(e, startTime);
                                logger.error("Capacity update failure", e);
                                result.onError(e);
                            },
                            () -> {
                                metrics.getUpdateExecutionMetrics().success(startTime);
                                logger.debug("Capacity guarantee changes applied");
                                result.onCompleted();
                            }
                    );
        } catch (Throwable e) {
            result.onError(e);
            throw e;
        }
    }

    private Map<String, InstanceTypeLimit> scalableInstanceTypeLimits(Map<String, InstanceTypeLimit> limits,
                                                                      List<String> scalableInstanceTypes) {
        HashMap<String, InstanceTypeLimit> result = new HashMap<>(limits);
        result.keySet().retainAll(scalableInstanceTypes);
        return result;
    }

    private List<ApplicationSLA> scalableSLAs(List<ApplicationSLA> allSLAs) {
        return allSLAs.stream().filter(sla -> sla.getTier() == Tier.Critical).collect(Collectors.toList());
    }

    private List<InstanceTypeLimit> buildInstanceTypeLimits(List<String> instanceTypes, List<Integer> maxLimits) {
        List<InstanceTypeLimit> limits = new ArrayList<>(instanceTypes.size());
        for (int i = 0; i < instanceTypes.size(); i++) {
            int minSize = ConfigUtil.getInstanceTypeMinSize(configuration, instanceTypes.get(i));
            limits.add(new InstanceTypeLimit(minSize, maxLimits.get(i)));
        }
        return limits;
    }

    private CapacityAllocations recompute(List<ApplicationSLA> allSLAs, Map<String, InstanceTypeLimit> limits) {
        EnumMap<Tier, List<ApplicationSLA>> tiers = new EnumMap<>(Tier.class);
        allSLAs.forEach(sla -> {
                    List<ApplicationSLA> tierSLAs = tiers.get(sla.getTier());
                    if (tierSLAs == null) {
                        tiers.put(sla.getTier(), tierSLAs = new ArrayList<>());
                    }
                    tierSLAs.add(sla);
                }
        );
        CapacityAllocations allocations = strategy.compute(new CapacityRequirements(tiers, limits));
        logger.debug("Recomputed resource dimensions: {}", allocations);

        return allocations;
    }
}