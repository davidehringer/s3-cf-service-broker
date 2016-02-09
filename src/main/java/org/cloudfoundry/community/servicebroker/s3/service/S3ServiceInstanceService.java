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

import org.cloudfoundry.community.servicebroker.exception.ServiceBrokerException;
import org.cloudfoundry.community.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.cloudfoundry.community.servicebroker.exception.ServiceInstanceExistsException;
import org.cloudfoundry.community.servicebroker.exception.ServiceInstanceUpdateNotSupportedException;
import org.cloudfoundry.community.servicebroker.model.CreateServiceInstanceRequest;
import org.cloudfoundry.community.servicebroker.model.DeleteServiceInstanceRequest;
import org.cloudfoundry.community.servicebroker.model.ServiceInstance;
import org.cloudfoundry.community.servicebroker.model.UpdateServiceInstanceRequest;
import org.cloudfoundry.community.servicebroker.s3.exception.UnsupportedPlanException;
import org.cloudfoundry.community.servicebroker.s3.plan.basic.BasicPlan;
import org.cloudfoundry.community.servicebroker.s3.plan.shared.SharedPlan;
import org.cloudfoundry.community.servicebroker.s3.plan.singlebucket.SingleBucketPlan;
import org.cloudfoundry.community.servicebroker.service.ServiceInstanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author David Ehringer
 */
@Service
public class S3ServiceInstanceService extends S3ServiceInstanceBase implements ServiceInstanceService {

    @Autowired
    public S3ServiceInstanceService(BasicPlan basicPlan, SharedPlan sharedPlan, SingleBucketPlan singleBucketPlan) {
        this.basicPlan = basicPlan;
        this.sharedPlan = sharedPlan;
        this.singleBucketPlan = singleBucketPlan;
    }

    @Override
    public ServiceInstance createServiceInstance(CreateServiceInstanceRequest createServiceInstanceRequest)
            throws ServiceInstanceExistsException, ServiceBrokerException {
        try {
            plan = getPlan(createServiceInstanceRequest.getPlanId());
        } catch (UnsupportedPlanException e) {
            throw new ServiceBrokerException(e.getMessage());
        }
        return plan.createServiceInstance(createServiceInstanceRequest);
    }

    @Override
    public ServiceInstance deleteServiceInstance(DeleteServiceInstanceRequest deleteServiceInstanceRequest)
            throws ServiceBrokerException {
        try {
            plan = getPlan(deleteServiceInstanceRequest.getPlanId());
        } catch (UnsupportedPlanException e) {
            throw new ServiceBrokerException(e.getMessage());
        }
        return plan.deleteServiceInstance(deleteServiceInstanceRequest);
    }

    @Override
    public ServiceInstance updateServiceInstance(UpdateServiceInstanceRequest updateServiceInstanceRequest)
            throws ServiceInstanceUpdateNotSupportedException, ServiceBrokerException, ServiceInstanceDoesNotExistException {
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public ServiceInstance getServiceInstance(String id) {
        ServiceInstance instance = null;
        instance = basicPlan.getServiceInstance(id);
        if (instance != null) {
            return instance;
        }
        instance = sharedPlan.getServiceInstance(id);
        if (instance != null) {
            return instance;
        }
        return singleBucketPlan.getServiceInstance(id);
    }
}
