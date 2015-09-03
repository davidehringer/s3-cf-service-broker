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
package org.cloudfoundry.community.servicebroker.s3.config.s3;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;

public class S3ServiceInstanceEncryptionKey {
    private static final Logger logger = LoggerFactory.getLogger(S3ServiceInstanceEncryptionKey.class);

    private String keyID;
    private String algorithm;
    private String key;

    public S3ServiceInstanceEncryptionKey() {
    }

    public S3ServiceInstanceEncryptionKey(String keyID, String algorithm) {
        this.keyID = keyID;
        this.algorithm = algorithm;
    }

    public S3ServiceInstanceEncryptionKey(String keyID, String algorithm, String key) {
        this.keyID = keyID;
        this.algorithm = algorithm;
        this.key = key;
    }

    public void generateSecretKey() {
        try {
            SecretKey secretKey = KeyGenerator.getInstance(getAlgorithm()).generateKey();
            setKey(Base64.encodeBase64String(secretKey.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            logger.error("The provided encryption algorithm '{}' is not supported.", getAlgorithm(), e);
        }
    }

    public String getKeyID() {
        return keyID;
    }

    public void setKeyID(String keyID) {
        this.keyID = keyID;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
