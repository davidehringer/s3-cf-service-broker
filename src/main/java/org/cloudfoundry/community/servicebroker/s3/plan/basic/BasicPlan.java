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
package org.cloudfoundry.community.servicebroker.s3.plan.basic;

import com.amazonaws.services.identitymanagement.model.AccessKey;
import com.amazonaws.services.identitymanagement.model.User;
import com.amazonaws.services.s3.model.Bucket;
import org.cloudfoundry.community.servicebroker.exception.ServiceBrokerException;
import org.cloudfoundry.community.servicebroker.model.*;
import org.cloudfoundry.community.servicebroker.s3.plan.Plan;
import org.cloudfoundry.community.servicebroker.s3.service.S3;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BasicPlan implements Plan {
    public static final String planId = "s3-basic-plan";

    private final BasicPlanIam iam;
    private final S3 s3;

    @Autowired
    public BasicPlan(BasicPlanIam iam, S3 s3) {
        this.iam = iam;
        this.s3 = s3;
    }

    public static org.cloudfoundry.community.servicebroker.model.Plan getPlan() {
        return new org.cloudfoundry.community.servicebroker.model.Plan(planId, "basic", "An S3 plan providing a single bucket with unlimited storage.",
                getPlanMetadata());
    }

    private static Map<String, Object> getPlanMetadata() {
        Map<String, Object> planMetadata = new HashMap<String, Object>();
        planMetadata.put("bullets", getPlanBullets());
        return planMetadata;
    }

    private static List<String> getPlanBullets() {
        return Arrays.asList("Single S3 bucket", "Unlimited storage", "Unlimited number of objects");
    }

    @Override
    public ServiceInstance createServiceInstance(CreateServiceInstanceRequest createServiceInstanceRequest) {
        String serviceInstanceId = createServiceInstanceRequest.getServiceInstanceId();
        String serviceDefinitionId = createServiceInstanceRequest.getServiceDefinitionId();
        String organizationGuid = createServiceInstanceRequest.getOrganizationGuid();
        String spaceGuid = createServiceInstanceRequest.getSpaceGuid();
        Bucket bucket = s3.createBucketForInstance(serviceInstanceId, serviceDefinitionId, planId, organizationGuid, spaceGuid);
        iam.createGroupForInstance(serviceInstanceId, bucket.getName());
        iam.applyGroupPolicyForInstance(serviceInstanceId, bucket.getName());
        return new ServiceInstance(createServiceInstanceRequest);
    }

    @Override
    public ServiceInstance deleteServiceInstance(DeleteServiceInstanceRequest deleteServiceInstanceRequest) {
        String serviceInstanceId = deleteServiceInstanceRequest.getServiceInstanceId();
        ServiceInstance instance = s3.findServiceInstance(serviceInstanceId);
        // TODO we need to make these deletes idempotent so we can handle retries on error
        iam.deleteGroupPolicyForInstance(serviceInstanceId);
        iam.deleteGroupForInstance(serviceInstanceId);
        s3.emptyBucket(serviceInstanceId);
        s3.deleteBucket(serviceInstanceId);
        return instance;
    }

    @Override
    public ServiceInstanceBinding createServiceInstanceBinding(CreateServiceInstanceBindingRequest createServiceInstanceBindingRequest) {
        String bindingId = createServiceInstanceBindingRequest.getBindingId();
        String serviceInstanceId = createServiceInstanceBindingRequest.getServiceInstanceId();
        String appGuid = createServiceInstanceBindingRequest.getAppGuid();
        User user = iam.createUserForBinding(bindingId);
        AccessKey accessKey = iam.createAccessKey(user);
        // TODO create password and add to credentials
        iam.addUserToGroup(user, iam.getGroupNameForInstance(serviceInstanceId));

        Map<String, Object> credentials = new HashMap<String, Object>();
        credentials.put("bucket", s3.getBucketNameForInstance(serviceInstanceId));
        credentials.put("username", user.getUserName());
        credentials.put("access_key_id", accessKey.getAccessKeyId());
        credentials.put("secret_access_key", accessKey.getSecretAccessKey());
        return new ServiceInstanceBinding(bindingId, serviceInstanceId, credentials, null, appGuid);
    }

    @Override
    public ServiceInstanceBinding deleteServiceInstanceBinding(DeleteServiceInstanceBindingRequest deleteServiceInstanceBindingRequest)
            throws ServiceBrokerException {
        String bindingId = deleteServiceInstanceBindingRequest.getBindingId();
        String serviceInstanceId = deleteServiceInstanceBindingRequest.getInstance().getServiceInstanceId();
        // TODO make operations idempotent so we can handle retries on error
        iam.removeUserFromGroupForInstance(bindingId, serviceInstanceId);
        iam.deleteUserAccessKeysForBinding(bindingId);
        iam.deleteUserForBinding(bindingId);
        return new ServiceInstanceBinding(bindingId, serviceInstanceId, null, null, null);
    }

    @Override
    public ServiceInstance getServiceInstance(String id) {
        return s3.findServiceInstance(id);
    }
}
