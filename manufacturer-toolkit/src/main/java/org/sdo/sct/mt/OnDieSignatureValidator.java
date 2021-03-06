// Copyright 2020 Intel Corporation
// SPDX-License-Identifier: Apache 2.0

package org.sdo.sct.mt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CRL;
import java.security.cert.CRLException;
import java.security.cert.CertPath;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


@Service
public class OnDieSignatureValidator {

  private OnDieCache onDieCache;
  private boolean checkRevocations = true;

  private static final int taskInfoLength = 36;  // length of the taskinfo part of OnDie signature
  private static final int rLength = 48;  // length of the r field part of OnDie signature
  private static final int sLength = 48;  // length of the s field part of OnDie signature

  /**
   * Constructor.
   */
  @Autowired
  public OnDieSignatureValidator(
      OnDieCache onDieCache,
      @Value("${sdo.ondiecache.revocations:true}") final boolean checkRevocations) {
    this.onDieCache = onDieCache;
    this.checkRevocations = checkRevocations;
  }

  /**
   * Performs a validation of the given signature with the public key
   * extracted from the given cert chain.
   *
   * @param certChain certChain
   * @param signedData signedData
   * @param signature signature
   * @return boolean indicating if signature is valid.
   * @throws CertificateException when error.
   */
  public boolean validate(CertPath certChain,
                          byte[] signedData,
                          byte[] signature) throws CertificateException {

    List<Certificate> certificateList = (List<Certificate>) certChain.getCertificates();

    // Check revocations first.
    if (checkRevocations && !checkRevocations(certificateList)) {
      return false;
    }

    try {
      // check minimum length (taskinfo + R + S)
      if (signature.length < (taskInfoLength + rLength + sLength)) {
        return false;
      }
      byte[] taskInfo = Arrays.copyOfRange(signature, 0, taskInfoLength);

      // adjust the signed data
      // data-to-verify format is: [ task-info | nonce (optional) | data ]
      // First 36 bytes of signature is the taskinfo. This value must be prepended
      // to the signed data
      ByteArrayOutputStream adjSignedData = new ByteArrayOutputStream();
      adjSignedData.write(Arrays.copyOfRange(signature, 0, taskInfo.length));
      adjSignedData.write(signedData);

      byte[] adjSignature = convertSignature(signature, taskInfo);
      PublicKey publicKey = certificateList.get(0).getPublicKey();

      Signature sig = Signature.getInstance("SHA384withECDSA");
      sig.initVerify(publicKey);
      sig.update(adjSignedData.toByteArray());
      return sig.verify(adjSignature);
    } catch (Exception ex) {
      return false;
    }
  }

  private static byte[] convertSignature(byte[] signature, byte[] taskInfo)
      throws IllegalArgumentException, IOException {
    if (taskInfo.length != 36) {
      throw new IllegalArgumentException("taskinfo length is incorrect: " + taskInfo.length);
    }

    // Format for signature should be as follows:
    // 0x30 b1 0x02 b2 (vr) 0x02 b3 (vs)
    // The b1 = length of remaining bytes,
    // b2 = length of R value (vr), b3 = length of S value (vs)
    byte[] rvalue = Arrays.copyOfRange(signature, taskInfo.length, taskInfo.length + rLength);
    byte[] svalue = Arrays.copyOfRange(signature,
      taskInfo.length + rLength,
      taskInfo.length + rLength + sLength);

    boolean appendZeroToR = false;
    boolean appendZeroToS = false;
    if ((rvalue[0] & 0x80) != 0) {
      appendZeroToR = true;
    }
    if ((svalue[0] & 0x80) != 0) {
      appendZeroToS = true;
    }

    ByteArrayOutputStream adjSignature = new ByteArrayOutputStream();
    adjSignature.write(0x30);
    // total length of remaining bytes
    adjSignature.write(4
        + (appendZeroToR ? rLength + 1 : rLength)
        + (appendZeroToS ? sLength + 1 : sLength));
    adjSignature.write(0x02);
    // R value
    if (appendZeroToR) {
      adjSignature.write(rLength + 1);
      adjSignature.write(0x00);
      adjSignature.write(rvalue);
    } else {
      adjSignature.write(rLength);
      adjSignature.write(rvalue);
    }
    adjSignature.write(0x02);
    // S value
    if (appendZeroToS) {
      adjSignature.write(sLength + 1);
      adjSignature.write(0x00);
      adjSignature.write(svalue);
    } else {
      adjSignature.write(sLength);
      adjSignature.write(svalue);
    }
    return adjSignature.toByteArray();
  }

  private boolean checkRevocations(List<Certificate> certificateList) {
    // Check revocations first.
    try {
      CertificateFactory certificateFactory = CertificateFactory.getInstance("X509");
      for (Certificate cert: certificateList) {
        X509Certificate x509cert = (X509Certificate) cert;
        X509CertificateHolder certHolder = new X509CertificateHolder(x509cert.getEncoded());
        CRLDistPoint cdp = CRLDistPoint.fromExtensions(certHolder.getExtensions());
        if (cdp != null) {
          DistributionPoint[] distPoints = cdp.getDistributionPoints();
          for (DistributionPoint dp : distPoints) {
            GeneralName[] generalNames =
              GeneralNames.getInstance(dp.getDistributionPoint().getName()).getNames();
            for (GeneralName generalName : generalNames) {
              byte[] crlBytes = onDieCache.getCertOrCrl(generalName.toString());
              if (crlBytes == null) {
                LoggerFactory.getLogger(getClass()).error(
                    "CRL ({}) not found in cache for cert: {}",
                    generalName.getName().toString(),
                    x509cert.getIssuerX500Principal().getName());
                return false;
              } else {
                CRL crl = certificateFactory.generateCRL(new ByteArrayInputStream(crlBytes));
                if (crl.isRevoked(cert)) {
                  return false;
                }
              }
            }
          }
        }
      }
    } catch (IOException | CertificateException | CRLException ex) {
      return false;
    }
    return true;
  }
}

