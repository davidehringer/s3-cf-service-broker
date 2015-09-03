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
package org.cloudfoundry.community.servicebroker.s3.plan.shared;

import com.amazonaws.services.identitymanagement.model.AccessKey;
import com.amazonaws.services.identitymanagement.model.User;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.community.servicebroker.model.ServiceDefinition;
import org.cloudfoundry.community.servicebroker.model.ServiceInstance;
import org.cloudfoundry.community.servicebroker.model.ServiceInstanceBinding;
import org.cloudfoundry.community.servicebroker.s3.config.BrokerConfiguration;
import org.cloudfoundry.community.servicebroker.s3.config.s3.SharedCredentials;
import org.cloudfoundry.community.servicebroker.s3.config.s3.S3ServiceInstanceConfigObject;
import org.cloudfoundry.community.servicebroker.s3.config.s3.S3ServiceInstanceEncryptionKey;
import org.cloudfoundry.community.servicebroker.s3.plan.Plan;
import org.cloudfoundry.community.servicebroker.s3.service.S3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class SharedPlan implements Plan {
    private static final Logger logger = LoggerFactory.getLogger(SharedPlan.class);
    private static final String planId = "s3-shared-plan";
    public static final String CONFIG_DIR = "config";
    public static final String CREDENTIALS_FILENAME = "shared_credentials";
    public static final String ENCRYPTION_ALGORITHM = "DESede";
    public static final String ENCRYPTION_KEY_ID = "generated";

    private final BrokerConfiguration brokerConfiguration;
    private final SharedPlanIam iam;
    private final S3 s3;

    @Autowired
    public SharedPlan(SharedPlanIam iam, S3 s3, BrokerConfiguration brokerConfiguration) {
        this.iam = iam;
        this.s3 = s3;
        this.brokerConfiguration = brokerConfiguration;
    }

    public static org.cloudfoundry.community.servicebroker.model.Plan getPlan() {
        return new org.cloudfoundry.community.servicebroker.model.Plan(planId, "shared", "An S3 plan providing a shared bucket with unlimited storage.",
                getPlanMetadata());
    }

    private static Map<String, Object> getPlanMetadata() {
        Map<String, Object> planMetadata = new HashMap<String, Object>();
        planMetadata.put("bullets", getPlanBullets());
        return planMetadata;
    }

    private static List<String> getPlanBullets() {
        return Arrays.asList("Shared S3 bucket", "Unlimited storage", "Unlimited number of objects");
    }

    private static String getInstanceConfigPath(String instanceId) {
        return String.format("%s/%s", CONFIG_DIR, instanceId);
    }

    private S3Object getObjectFromS3(String bucket, String path) {
        try {
            return s3.getObject(new GetObjectRequest(bucket, path));
        } catch (AmazonS3Exception e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                logger.error("The S3 object '{}/{}' doesn't exist.", bucket, path);
            } else {
                logger.error("Undefined error on getJSONFromS3Object()", e);
            }
        }
        return null;
    }

    private static <T> T readObjectFromS3Object(S3Object s3object, Class<T> returnObject) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(new InputStreamReader(s3object.getObjectContent()), returnObject);
        } catch (IOException e) {
            logger.error("Could not read JSON from S3 bucket.", e);
        }
        return null;
    }

    private <T> T getObjectFromJSONOnS3(String bucket, String path, Class<T> returnObject) {
        S3Object s3object = getObjectFromS3(bucket, path);
        if(s3object != null) {
            return readObjectFromS3Object(s3object, returnObject);
        }
        return null;
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

    private SharedCredentials persistSharedUser(User sharedUser) {
        String sharedCredentialsPath = String.format("%s/%s", CONFIG_DIR, CREDENTIALS_FILENAME);
        logger.info("Attempting to persist shared user to: s3://" + brokerConfiguration.getSharedBucket() + "/" + sharedCredentialsPath);
        SharedCredentials credentialsObject = getObjectFromJSONOnS3(brokerConfiguration.getSharedBucket(), sharedCredentialsPath, SharedCredentials.class);
        if (credentialsObject == null) {
            AccessKey accessKey = iam.createAccessKey(sharedUser);
            credentialsObject = new SharedCredentials(accessKey.getAccessKeyId(), accessKey.getSecretAccessKey());
            putObjectToJSONOnS3(sharedCredentialsPath, credentialsObject);
        }
        return credentialsObject;
    }

    private void ensureSharedUserPolicy(String sharedUser) {
        String policyName = "CFSharedBucketIamPolicy";
        if (!iam.doesUserPolicyExist(sharedUser, policyName)) {
            iam.applyUserPolicy(sharedUser, policyName, iam.getPolicyDocument(brokerConfiguration.getSharedBucket()));
        }
    }

    private void ensureSharedBucket() {
        if (!s3.doesBucketExist(brokerConfiguration.getSharedBucket())) {
            s3.createBucket(brokerConfiguration.getSharedBucket());
        }
    }

    private SharedCredentials getSharedCredentialsFromBucket() {
        String sharedCredentialsPath = String.format("%s/%s", CONFIG_DIR, CREDENTIALS_FILENAME);
        AmazonS3 s3client = brokerConfiguration.amazonS3();
        S3Object sharedCredentials = s3client.getObject(new GetObjectRequest(brokerConfiguration.getSharedBucket(), sharedCredentialsPath));
        if (sharedCredentials != null) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                return mapper.readValue(new InputStreamReader(sharedCredentials.getObjectContent()), SharedCredentials.class);
            } catch (IOException e) {
                logger.error("Could not read shared credentials from S3 bucket.", e);
            }
        }
        return null;
    }

    public String getSharedAccessKey() {
        SharedCredentials credentialsFromBucket = getSharedCredentialsFromBucket();
        if (credentialsFromBucket != null) {
            return credentialsFromBucket.getAccessKey();
        }
        return null;
    }

    public String getSharedSecretKey() {
        SharedCredentials credentialsFromBucket = getSharedCredentialsFromBucket();
        if (credentialsFromBucket != null) {
            return credentialsFromBucket.getSecretKey();
        }
        return null;
    }

    @Override
    public ServiceInstance createServiceInstance(ServiceDefinition service, String serviceInstanceId, String planId,
                                                 String organizationGuid, String spaceGuid) {
        ensureSharedBucket();
        User sharedUser = iam.ensureSharedUser();
        persistSharedUser(sharedUser);
        ensureSharedUserPolicy(sharedUser.getUserName());

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

        Map<String, Object> credentials = new HashMap<String, Object>();
        credentials.put("bucket", brokerConfiguration.getSharedBucket());
        credentials.put("username", serviceId);
        credentials.put("access_key_id", getSharedAccessKey());
        credentials.put("secret_access_key", getSharedSecretKey());
        credentials.put("key_suffix", String.format("%s%s", "_", serviceInstance.getId()));

        S3ServiceInstanceConfigObject configObject = getObjectFromJSONOnS3(brokerConfiguration.getSharedBucket(),
                getInstanceConfigPath(serviceInstance.getId()), S3ServiceInstanceConfigObject.class);
        credentials.put("encryption_keys", configObject.getEncryptionKeys());

        return new ServiceInstanceBinding(bindingId, serviceInstance.getId(), credentials, null, appGuid);
    }

    @Override
    public ServiceInstanceBinding deleteServiceInstanceBinding(String bindingId, ServiceInstance serviceInstance,
                                                               String serviceId, String planId) {
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
