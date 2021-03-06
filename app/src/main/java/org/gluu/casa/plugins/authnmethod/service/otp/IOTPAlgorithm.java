/*
 * casa is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2018, Gluu
 */
package org.gluu.casa.plugins.authnmethod.service.otp;

/**
 * @author jgomer
 */
public interface IOTPAlgorithm {

    byte[] generateSecretKey();

    String generateSecretKeyUri(byte[] secretKey, String displayName);

    String getExternalUid(byte[] secretKey, String code);

}
