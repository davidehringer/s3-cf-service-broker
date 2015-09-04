/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cloudfoundry.community.servicebroker.s3.service;

import org.cloudfoundry.community.servicebroker.s3.exception.UnsupportedPlanException;
import org.cloudfoundry.community.servicebroker.s3.plan.Plan;
import org.cloudfoundry.community.servicebroker.s3.plan.basic.BasicPlan;
import org.cloudfoundry.community.servicebroker.s3.plan.shared.SharedPlan;
import org.cloudfoundry.community.servicebroker.s3.plan.singlebucket.SingleBucketPlan;

public class S3ServiceInstanceBase {
    protected BasicPlan basicPlan;
    protected SharedPlan sharedPlan;
    protected SingleBucketPlan singleBucketPlan;
    protected Plan plan;

    protected Plan getPlan(String planId) throws UnsupportedPlanException {
        if (planId.equals(BasicPlan.planId)) {
            return basicPlan;
        } else if (planId.equals(SharedPlan.planId)) {
            return sharedPlan;
        } else if (planId.equals(SingleBucketPlan.planId)) {
            return singleBucketPlan;
        } else throw new UnsupportedPlanException(String.format("Unsupported plan: '%s'", planId));
    }
}
