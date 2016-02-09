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
package org.cloudfoundry.community.servicebroker.s3.sharedplan;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.identitymanagement.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.*;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.response.ValidatableResponse;
import org.apache.http.HttpStatus;
import org.cloudfoundry.community.servicebroker.ServiceBrokerV2IntegrationTestBase;
import org.cloudfoundry.community.servicebroker.s3.config.Application;
import org.junit.*;
import org.junit.runners.MethodSorters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.SpringApplicationConfiguration;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import static com.jayway.restassured.RestAssured.given;
import static junit.framework.Assert.assertTrue;

@SpringApplicationConfiguration(classes = Application.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SharedPlanIntegrationTests extends ServiceBrokerV2IntegrationTestBase {

    private static AmazonS3 s3;
    private static AmazonIdentityManagementClient iam;

    @Value("${AWS_SHARED_BUCKET:}")
    private String awsSharedBucketFromEnv;
    private static String awsSharedBucket;

    @Value("${AWS_SHARED_USER_NAME:cloud-foundry-s3-shared}")
    private String awsSharedUserNameFromEnv;
    private static String awsSharedUserName;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        s3 = new AmazonS3Client();
        iam = new AmazonIdentityManagementClient();
        planId = "s3-shared-plan";
        awsSharedBucket = awsSharedBucketFromEnv;
        awsSharedUserName = awsSharedUserNameFromEnv;
    }

    private boolean doesUserExist(String userName) {
        for (User u: iam.listUsers().getUsers()) {
            if (u.getUserName().equals(userName)) {
                return true;
            }
        }

        return false;
    }

    private void testBucketOperations(String accessKey, String secretKey, String bucketName, String keySuffix) throws IOException {
        AmazonS3Client instanceS3 = new AmazonS3Client(new BasicAWSCredentials(accessKey, secretKey));
        assertTrue(instanceS3.doesBucketExist(bucketName));
        String objectName = "testObject" + keySuffix;
        String objectContent = "Hello World!";

        //set object content
        ByteArrayInputStream data = new ByteArrayInputStream(objectContent.getBytes());
        PutObjectRequest putRequest = new PutObjectRequest(bucketName, objectName, data, new ObjectMetadata());
        instanceS3.putObject(putRequest);

        //get object content
        GetObjectRequest getRequest = new GetObjectRequest(bucketName, objectName);
        BufferedReader reader = new BufferedReader(new InputStreamReader(instanceS3.getObject(getRequest).getObjectContent()));
        assertTrue(reader.readLine().equals(objectContent));

        //deletion is forbidden by IAM policy
    }

    @Override
    public void case2_provisionInstanceSucceedsWithCredentials() throws Exception {
        super.case2_provisionInstanceSucceedsWithCredentials();
        assertTrue(s3.doesBucketExist(awsSharedBucket));
        assertTrue(doesUserExist(awsSharedUserName));
    }

    @Override
    public void case3_createBindingSucceedsWithCredentials() throws Exception {
        String createBindingPath = String.format(createOrRemoveBindingBasePath, instanceId, serviceId);
        String request_body = "{\n" +
                "  \"plan_id\":      \"" + planId + "\",\n" +
                "  \"service_id\":   \"" + serviceId + "\",\n" +
                "  \"app_guid\":     \"" + appGuid + "\"\n" +
                "}";


        ValidatableResponse response = given().auth().basic(username, password).header(apiVersionHeader).request().contentType(ContentType.JSON).body(request_body).when().put(createBindingPath).then().statusCode(HttpStatus.SC_CREATED);
        String accessKey = response.extract().path("credentials.access_key_id");
        String secretKey = response.extract().path("credentials.secret_access_key");
        String sharedBucket = response.extract().path("credentials.bucket");
        String keySuffix = response.extract().path("credentials.key_suffix");

        //wait for AWS to do its user creation magic
        Thread.sleep(1000 * 30);
        testBucketOperations(accessKey, secretKey, sharedBucket, keySuffix);
    }

    @AfterClass
    public static void wipeCredentials() {
        ListAccessKeysRequest accessKeysRequest = new ListAccessKeysRequest();
        accessKeysRequest.setUserName(awsSharedUserName);
        ListAccessKeysResult accessKeysResult = iam.listAccessKeys(accessKeysRequest);
        for (AccessKeyMetadata keyMeta : accessKeysResult.getAccessKeyMetadata()) {
            DeleteAccessKeyRequest request = new DeleteAccessKeyRequest(awsSharedUserName, keyMeta.getAccessKeyId());
            iam.deleteAccessKey(request);
        }
        iam.deleteUserPolicy(new DeleteUserPolicyRequest(awsSharedUserName, "CFSharedBucketIamPolicy"));
        iam.deleteUser(new DeleteUserRequest(awsSharedUserName));
    }

    @AfterClass
    public static void wipeBucket() throws Exception {
        ObjectListing objects = s3.listObjects(awsSharedBucket);
        for (S3ObjectSummary object: objects.getObjectSummaries()) {
            s3.deleteObject(awsSharedBucket, object.getKey());
        }
        s3.deleteBucket(awsSharedBucket);

        //cooldown to ensure that the next doesBucketExists call returns the correct result
        Thread.sleep(1000 * 30);
    }
}
