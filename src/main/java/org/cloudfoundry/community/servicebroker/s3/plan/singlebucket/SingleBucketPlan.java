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
package org.cloudfoundry.community.servicebroker.s3.plan.singlebucket;

import com.amazonaws.services.identitymanagement.model.AccessKey;
import com.amazonaws.services.identitymanagement.model.User;
import com.amazonaws.services.s3.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.community.servicebroker.model.ServiceDefinition;
import org.cloudfoundry.community.servicebroker.model.ServiceInstance;
import org.cloudfoundry.community.servicebroker.model.ServiceInstanceBinding;
import org.cloudfoundry.community.servicebroker.s3.config.BrokerConfiguration;
import org.cloudfoundry.community.servicebroker.s3.config.s3.S3ServiceInstanceConfigObject;
import org.cloudfoundry.community.servicebroker.s3.plan.Plan;
import org.cloudfoundry.community.servicebroker.s3.service.S3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class SingleBucketPlan implements Plan {
    private static final Logger logger = LoggerFactory.getLogger(SingleBucketPlan.class);
    public static final String planId = "s3-singlebucket-plan";
    public static final String CONFIG_DIR = "config";
    public static final String ENCRYPTION_ALGORITHM = "DESede";
    public static final String ENCRYPTION_KEY_ID = "generated";

    private final BrokerConfiguration brokerConfiguration;
    private final SingleBucketPlanIam iam;
    private final S3 s3;

    @Autowired
    public SingleBucketPlan(SingleBucketPlanIam iam, S3 s3, BrokerConfiguration brokerConfiguration) {
        this.iam = iam;
        this.s3 = s3;
        this.brokerConfiguration = brokerConfiguration;
    }

    public static org.cloudfoundry.community.servicebroker.model.Plan getPlan() {
        return new org.cloudfoundry.community.servicebroker.model.Plan(planId, "singlebucket", "An S3 plan providing a single bucket (separate IAM users) with unlimited storage.",
                getPlanMetadata());
    }

    private static Map<String, Object> getPlanMetadata() {
        Map<String, Object> planMetadata = new HashMap<String, Object>();
        planMetadata.put("bullets", getPlanBullets());
        return planMetadata;
    }

    private static List<String> getPlanBullets() {
        return Arrays.asList("Shared S3 bucket (separate IAM users)", "Unlimited storage", "Unlimited number of objects");
    }

    private static String getInstanceConfigPath(String instanceId) {
        return String.format("%s/%s", CONFIG_DIR, instanceId);
    }

    private void putObjectToJSONOnS3(String path, Object object) {
        try {
            String jsonString = new ObjectMapper().writeValueAsString(object);
            InputStream inputstream = new ByteArrayInputStream(jsonString.getBytes(StandardCharsets.UTF_8));
            s3.putObject(new PutObjectRequest(brokerConfiguration.getSharedBucket(), path, inputstream, new ObjectMetadata()));
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert JSON to inputstream.", e);
        }
    }

    private void createUserBucketPolicy(String userName, String key_suffix) {
        String policyName = "CFSingleBucketIamPolicy";
        if (!iam.doesUserPolicyExist(userName, policyName)) {
            iam.applyUserPolicy(userName, policyName, iam.getPolicyDocument(brokerConfiguration.getSharedBucket(), key_suffix));
        }
    }

    private void deleteUserBucketPolicy(String userName) {
        String policyName = "CFSingleBucketIamPolicy";
        if (iam.doesUserPolicyExist(userName, policyName)) {
            iam.deleteUserPolicy(userName, policyName);
        }
    }

    private void ensureSharedBucket() {
        if (!s3.doesBucketExist(brokerConfiguration.getSharedBucket())) {
            s3.createBucket(brokerConfiguration.getSharedBucket());
        }
    }

    @Override
    public ServiceInstance createServiceInstance(ServiceDefinition service, String serviceInstanceId, String planId,
                                                 String organizationGuid, String spaceGuid) {
        ensureSharedBucket();

        S3ServiceInstanceEncryptionKey encryptionKey = new S3ServiceInstanceEncryptionKey(ENCRYPTION_KEY_ID, ENCRYPTION_ALGORITHM);
        encryptionKey.generateSecretKey();
        S3ServiceInstanceConfigObject configObject = new S3ServiceInstanceConfigObject(organizationGuid, spaceGuid, Arrays.asList(encryptionKey));
        logger.info("Creating Service Instance Config Object in: s3://{}/{}", brokerConfiguration.getSharedBucket(), getInstanceConfigPath(serviceInstanceId));
        putObjectToJSONOnS3(getInstanceConfigPath(serviceInstanceId), configObject);

        return new ServiceInstance(serviceInstanceId, service.getId(), planId, organizationGuid, spaceGuid, null);
    }

    @Override
    public ServiceInstance deleteServiceInstance(String id, String serviceId, String planId) {
        String instanceConfigPath = getInstanceConfigPath(id);
        s3.deleteObject(new DeleteObjectRequest(brokerConfiguration.getSharedBucket(), instanceConfigPath));
        return new ServiceInstance(id, serviceId, planId, null, null, null);
    }

    @Override
    public ServiceInstanceBinding createServiceInstanceBinding(String bindingId, ServiceInstance serviceInstance,
                                                               String serviceId, String planId, String appGuid) {
        User user = iam.createUserForBinding(bindingId);
        AccessKey accessKey = iam.createAccessKey(user);

        String key_suffix = String.format("%s%s", "_", serviceInstance.getId());
        createUserBucketPolicy(user.getUserName(), key_suffix);

        Map<String, Object> credentials = new HashMap<String, Object>();
        credentials.put("bucket", brokerConfiguration.getSharedBucket());
        credentials.put("username", user.getUserName());
        credentials.put("access_key_id", accessKey.getAccessKeyId());
        credentials.put("secret_access_key", accessKey.getSecretAccessKey());
        credentials.put("key_suffix", key_suffix);

        S3ServiceInstanceConfigObject configObject = getObjectFromJSONOnS3(brokerConfiguration.getSharedBucket(),
                getInstanceConfigPath(serviceInstance.getId()), S3ServiceInstanceConfigObject.class);
        credentials.put("encryption_keys", configObject.getEncryptionKeys());

        return new ServiceInstanceBinding(bindingId, serviceInstance.getId(), credentials, null, appGuid);
    }

    @Override
    public ServiceInstanceBinding deleteServiceInstanceBinding(String bindingId, ServiceInstance serviceInstance,
                                                               String serviceId, String planId) {

        deleteUserBucketPolicy(iam.getUserNameForBinding(bindingId));
        iam.deleteUserAccessKeysForBinding(bindingId);
        iam.deleteUserForBinding(bindingId);
        return new ServiceInstanceBinding(bindingId, serviceInstance.getId(), null, null, null);
    }

    // TODO needs to be implemented
    @Override
    public List<ServiceInstance> getAllServiceInstances() {
        return Collections.emptyList();
    }

    @Override
    public ServiceInstance getServiceInstance(String id) {
        String instanceConfigPath = getInstanceConfigPath(id);
        S3Object s3Object = s3.getObject(new GetObjectRequest(brokerConfiguration.getSharedBucket(), instanceConfigPath));
        if (s3Object != null) {
            return new ServiceInstance(id, null, planId, null, null, null);
        } else {
            return null;
        }
    }
}