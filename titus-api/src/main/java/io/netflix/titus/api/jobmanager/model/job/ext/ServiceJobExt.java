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

package io.netflix.titus.api.jobmanager.model.job.ext;

import javax.validation.Valid;

import io.netflix.titus.api.jobmanager.model.job.Capacity;
import io.netflix.titus.api.jobmanager.model.job.JobDescriptor;
import io.netflix.titus.api.jobmanager.model.job.retry.RetryPolicy;
import io.netflix.titus.common.model.sanitizer.FieldInvariant;
import io.netflix.titus.common.model.sanitizer.NeverNull;

/**
 */
@NeverNull
public class ServiceJobExt implements JobDescriptor.JobDescriptorExt {

    @Valid
    @FieldInvariant(value = "value.getMax() <= @constraints.getMaxServiceJobSize()", message = "Service job too big #{value.getMax()} > #{@constraints.getMaxServiceJobSize()}")
    private final Capacity capacity;

    private final boolean enabled;

    @Valid
    private final RetryPolicy retryPolicy;

    public ServiceJobExt(Capacity capacity,
                         boolean enabled,
                         RetryPolicy retryPolicy) {
        this.capacity = capacity;
        this.enabled = enabled;
        this.retryPolicy = retryPolicy;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ServiceJobExt that = (ServiceJobExt) o;

        if (enabled != that.enabled) {
            return false;
        }
        if (capacity != null ? !capacity.equals(that.capacity) : that.capacity != null) {
            return false;
        }

        return retryPolicy != null ? retryPolicy.equals(that.retryPolicy) : that.retryPolicy == null;
    }

    @Override
    public int hashCode() {
        int result = capacity != null ? capacity.hashCode() : 0;
        result = 31 * result + (enabled ? 1 : 0);
        result = 31 * result + (retryPolicy != null ? retryPolicy.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ServiceJobExt{" +
                "capacity=" + capacity +
                ", enabled=" + enabled +
                ", retryPolicy=" + retryPolicy +
                '}';
    }

    public Builder toBuilder() {
        return newBuilder(this);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static Builder newBuilder(ServiceJobExt serviceJobExt) {
        return new Builder()
                .withCapacity(serviceJobExt.getCapacity())
                .withEnabled(serviceJobExt.isEnabled())
                .withRetryPolicy(serviceJobExt.getRetryPolicy());
    }

    public static final class Builder {
        private Capacity capacity;
        private boolean enabled;
        private RetryPolicy retryPolicy;

        private Builder() {
        }

        public Builder withCapacity(Capacity capacity) {
            this.capacity = capacity;
            return this;
        }

        public Builder withEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder withRetryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder but() {
            return newBuilder().withCapacity(capacity).withEnabled(enabled).withRetryPolicy(retryPolicy);
        }

        public ServiceJobExt build() {
            ServiceJobExt serviceJobExt = new ServiceJobExt(capacity, enabled, retryPolicy);
            return serviceJobExt;
        }
    }
}