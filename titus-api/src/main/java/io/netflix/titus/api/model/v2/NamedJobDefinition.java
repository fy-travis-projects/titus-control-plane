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

package io.netflix.titus.api.model.v2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public class NamedJobDefinition {

    public enum CronPolicy {KEEP_EXISTING, KEEP_NEW}

    private final V2JobDefinition jobDefinition;
    private final JobOwner owner;

    @JsonCreator
    @JsonIgnoreProperties(ignoreUnknown = true)
    public NamedJobDefinition(@JsonProperty("jobDefinition") V2JobDefinition jobDefinition,
                              @JsonProperty("owner") JobOwner owner) {
        this.jobDefinition = jobDefinition;
        this.owner = owner;
    }

    public V2JobDefinition getJobDefinition() {
        return jobDefinition;
    }

    public JobOwner getOwner() {
        return owner;
    }
}