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

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.google.common.collect.Lists;
import org.cloudfoundry.community.servicebroker.model.CreateServiceInstanceRequest;
import org.cloudfoundry.community.servicebroker.model.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author David Ehringer
 */
@Component
public class S3 {

    private static final Logger logger = LoggerFactory.getLogger(S3.class);

    private final AmazonS3 s3;
    private final String bucketNamePrefix;
    private final String region;

    @Autowired
    public S3(AmazonS3 s3, @Value("${BUCKET_NAME_PREFIX:cloud-foundry-}") String bucketNamePrefix, @Value("${AWS_REGION:US}") String region) {
        this.s3 = s3;
        this.bucketNamePrefix = bucketNamePrefix;
        this.region = region;
    }

    public Bucket createBucketForInstance(String instanceId, String serviceDefinitionId, String planId,
            String organizationGuid, String spaceGuid) {
        String bucketName = getBucketNameForInstance(instanceId);
        logger.info("Creating bucket '{}' for serviceInstanceId '{}'", bucketName, instanceId);
        Bucket bucket = s3.createBucket(bucketName, Region.fromValue(region));

        // TODO allow for additional, custom tagging options
        BucketTaggingConfiguration bucketTaggingConfiguration = new BucketTaggingConfiguration();
        TagSet tagSet = new TagSet();
        tagSet.setTag("serviceInstanceId", instanceId);
        tagSet.setTag("serviceDefinitionId", serviceDefinitionId);
        tagSet.setTag("planId", planId);
        tagSet.setTag("organizationGuid", organizationGuid);
        tagSet.setTag("spaceGuid", spaceGuid);
        bucketTaggingConfiguration.withTagSets(tagSet);
        s3.setBucketTaggingConfiguration(bucket.getName(), bucketTaggingConfiguration);

        return bucket;
    }

    public void deleteBucket(String id) {
        String bucketName = getBucketNameForInstance(id);
        logger.info("Deleting bucket '{}' for serviceInstanceId '{}'", bucketName, id);
        s3.deleteBucket(bucketName);
    }

    /**
     * Deletes all objects and all object versions in the bucket.
     * 
     * @param id
     */
    public void emptyBucket(String id) {
        String bucketName = getBucketNameForInstance(id);
        deleteAllObjects(bucketName);
        deleteAllVersions(bucketName);
    }

    private void deleteAllObjects(String bucketName) {
        logger.info("Deleting all objects from bucket '{}'", bucketName);
        ObjectListing objectList = s3.listObjects(bucketName);
        delete(objectList);
        while (objectList.isTruncated()) {
            objectList = s3.listNextBatchOfObjects(objectList);
            delete(objectList);
        }
    }

    private void delete(ObjectListing objectList) {
        for (S3ObjectSummary objectSummary : objectList.getObjectSummaries()) {
            s3.deleteObject(objectSummary.getBucketName(), objectSummary.getKey());
        }
    }

    private void deleteAllVersions(String bucketName) {
        logger.info("Deleting all object versions from bucket '{}'", bucketName);
        VersionListing versionListing = s3.listVersions(bucketName, null);
        delete(versionListing);
        while (versionListing.isTruncated()) {
            versionListing = s3.listNextBatchOfVersions(versionListing);
            delete(versionListing);
        }
    }

    private void delete(VersionListing versionListing) {
        for (S3VersionSummary versionSummary : versionListing.getVersionSummaries()) {
            s3.deleteVersion(versionSummary.getBucketName(), versionSummary.getKey(), versionSummary.getVersionId());
        }
    }

    public String getBucketNameForInstance(String instanceId) {
        return bucketNamePrefix + instanceId;
    }

    public ServiceInstance findServiceInstance(String instanceId) {
        String bucketName = getBucketNameForInstance(instanceId);
        if (s3.doesBucketExist(bucketName)) {
            BucketTaggingConfiguration taggingConfiguration = s3.getBucketTaggingConfiguration(bucketName);
            return createServiceInstance(taggingConfiguration);
        }
        return null;
    }

    public List<ServiceInstance> getAllServiceInstances() {
        List<ServiceInstance> serviceInstances = Lists.newArrayList();
        for (Bucket bucket : s3.listBuckets()) {
            BucketTaggingConfiguration taggingConfiguration = s3.getBucketTaggingConfiguration(bucket.getName());
            ServiceInstance serviceInstance = createServiceInstance(taggingConfiguration);
            serviceInstances.add(serviceInstance);
        }
        return serviceInstances;
    }

    private ServiceInstance createServiceInstance(BucketTaggingConfiguration taggingConfiguration) {
        // While the Java API has multiple TagSets, it would appear from
        // http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUTtagging.html
        // that only one TagSet is supported.
        TagSet tagSet = taggingConfiguration.getTagSet();
        String serviceInstanceId = tagSet.getTag("serviceInstanceId");
        if (serviceInstanceId == null) {
            // could occur if someone used this broker AWS ID to a bucket
            // outside of the broker process
            return null;
        }
        String serviceDefinitionId = tagSet.getTag("serviceDefinitionId");
        String planId = tagSet.getTag("planId");
        String organizationGuid = tagSet.getTag("organizationGuid");
        String spaceGuid = tagSet.getTag("spaceGuid");
        CreateServiceInstanceRequest wrapper = new CreateServiceInstanceRequest(serviceDefinitionId, planId, organizationGuid, spaceGuid).withServiceInstanceId(serviceInstanceId);
        return new ServiceInstance(wrapper);
    }

    public boolean doesBucketExist(String bucket) {
        return s3.doesBucketExist(bucket);
    }

    public Bucket createBucket(String bucket) {
        logger.info("Creating shared bucket '{}' in region '{}'", bucket, region);
        return s3.createBucket(bucket, region);
    }

    /**
     * Caution: S3Object opens a connection for each object.
     * These connections are not liberated even if the object is garbage collected,
     * so it is needed to execute either use Try-with-resources (preferred)
     * or manually call object.close() in order to liberate the connection to the pool.
     * Failing to do so will result in AmazonClientException: ConnectionPoolTimeoutException.
     *
     * @param getObjectRequest
     * @return Returns the requested {@link S3Object}.
     */
    public S3Object getObject(GetObjectRequest getObjectRequest) {
        return s3.getObject(getObjectRequest);
    }

    public PutObjectResult putObject(PutObjectRequest putObjectRequest) {
        return s3.putObject(putObjectRequest);
    }

    public void deleteObject(DeleteObjectRequest deleteObjectRequest) {
        s3.deleteObject(deleteObjectRequest);
    }
}
