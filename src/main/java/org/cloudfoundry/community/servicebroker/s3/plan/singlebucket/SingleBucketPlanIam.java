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

import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.model.User;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.cloudfoundry.community.servicebroker.s3.policy.BucketGroupPolicy;
import org.cloudfoundry.community.servicebroker.s3.service.Iam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;

@Component
public class SingleBucketPlanIam extends Iam {
    private static final Logger logger = LoggerFactory.getLogger(SingleBucketPlanIam.class);

    @Autowired
    public SingleBucketPlanIam(AmazonIdentityManagement iam, BucketGroupPolicy bucketGroupPolicy,
                               @Value("${GROUP_PATH:/cloud-foundry/s3/}") String groupPath,
                               @Value("${GROUP_NAME_PREFIX:cloud-foundry-s3-}") String groupNamePrefix,
                               @Value("${POLICY_NAME_PREFIX:cloud-foundry-s3-}") String policyNamePrefix,
                               @Value("${USER_PATH:/cloud-foundry/s3/}") String userPath,
                               @Value("${USER_NAME_PREFIX:cloud-foundry-s3-}") String userNamePrefix) {
        super(iam, bucketGroupPolicy, groupPath, groupNamePrefix, policyNamePrefix, userPath, userNamePrefix);
    }

    public String getPolicyDocument(String bucket, String key_suffix) {
        try {
            URL url = new ClassPathResource("single-bucket-user-iam-policy.json").getURL();
            String policyDocument = Resources.toString(url, Charsets.UTF_8);
            return policyDocument.replace("${bucketName}", bucket).replace("${suffix}", key_suffix);
        } catch (IOException e) {
            logger.error("Policy file not found", e);
        }
        return null;
    }

    public User createUserForBinding(String bindingId) {
        String userName = getUserNameForBinding(bindingId);
        logger.info("Creating user '{}' for service binding '{}'", userName, bindingId);
        return createUser(userName);
    }

    public String getUserNameForBinding(String bindingId) {
        return getUserNamePrefix() + bindingId;
    }

    public void deleteUserForBinding(String bindingId) {
        String userName = getUserNameForBinding(bindingId);
        logger.info("Deleting user '{}' from service binding '{}'", userName, bindingId);
        deleteUser(userName);
    }

    public void deleteUserAccessKeysForBinding(String bindingId) {
        String userName = getUserNameForBinding(bindingId);
        deleteUserAccessKeys(userName);
    }
}
