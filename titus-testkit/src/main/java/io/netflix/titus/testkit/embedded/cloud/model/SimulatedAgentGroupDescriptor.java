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

package io.netflix.titus.testkit.embedded.cloud.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SimulatedAgentGroupDescriptor {

    private final String name;
    private final String instanceType;
    private final int min;
    private final int desired;
    private final int max;

    @JsonCreator
    public SimulatedAgentGroupDescriptor(@JsonProperty("name") String name,
                                         @JsonProperty("instanceType") String instanceType,
                                         @JsonProperty("min") int min,
                                         @JsonProperty("desired") int desired,
                                         @JsonProperty("max") int max) {
        this.name = name;
        this.instanceType = instanceType;
        this.min = min;
        this.desired = desired;
        this.max = max;
    }

    public String getName() {
        return name;
    }

    public String getInstanceType() {
        return instanceType;
    }

    public int getMin() {
        return min;
    }

    public int getDesired() {
        return desired;
    }

    public int getMax() {
        return max;
    }
}