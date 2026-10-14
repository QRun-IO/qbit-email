/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.email.tracking;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.NOW;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.SECRET;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.TOPIC;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.certificateTime;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.resource;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.response;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.signMailgun;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.signSns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Crypto boundaries use genuine signatures and a public test-only RSA fixture.
 *******************************************************************************/
class WebhookVerifiersTest
{
   /*******************************************************************************
    ** Postmark's fixed user and constant-time credential verification.
    *******************************************************************************/
   @Test
   void postmarkValidTamperedMissing()
   {
      var verifier = new PostmarkVerifier();
      String auth = "Basic " + Base64.getEncoder().encodeToString(("postmark:" + SECRET).getBytes(StandardCharsets.UTF_8));
      assertThat(verifier.verify(auth, SECRET)).isTrue();
      assertThat(verifier.verify(auth, "wrong")).isFalse();
      assertThat(verifier.verify(auth, null)).isFalse();
      assertThat(verifier.verify(auth, " ")).isFalse();
      assertThat(verifier.verify(null, SECRET)).isFalse();
      assertThat(verifier.verify("Bearer abc", SECRET)).isFalse();
      assertThat(verifier.verify("Basic ?", SECRET)).isFalse();
      assertThat(verifier.verify("Basic " + Base64.getEncoder().encodeToString(("other:" + SECRET).getBytes(StandardCharsets.UTF_8)), SECRET)).isFalse();
   }

   /*******************************************************************************
    ** Genuine ECDSA validates unchanged bytes, timestamp freshness and DER key.
    *******************************************************************************/
   @Test
   void sendgridValidTamperedStaleMissing() throws Exception
   {
      var keys = KeyPairGenerator.getInstance("EC").generateKeyPair();
      String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
      byte[] body = "[{\"event\":\"delivered\"}]".getBytes(StandardCharsets.UTF_8);
      String timestamp = Long.toString(NOW.getEpochSecond());
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(keys.getPrivate());
      signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
      signer.update(body);
      String signed = Base64.getEncoder().encodeToString(signer.sign());
      var verifier = new SendGridVerifier();
      assertThat(verifier.verify(body, signed, timestamp, publicKey, NOW)).isTrue();
      assertThat(verifier.verify("tampered".getBytes(StandardCharsets.UTF_8), signed, timestamp, publicKey, NOW)).isFalse();
      assertThat(verifier.verify(body, signed, timestamp, publicKey, NOW.plusSeconds(301))).isFalse();
      assertThat(verifier.verify(body, signed, timestamp, publicKey, NOW.minusSeconds(301))).isFalse();
      assertThat(verifier.verify(body, signed, "9223372036854775807", publicKey, NOW)).isFalse();
      assertThat(verifier.verify(body, signed, timestamp, null, NOW)).isFalse();
      assertThat(verifier.verify(body, null, timestamp, publicKey, NOW)).isFalse();
      assertThat(verifier.verify(body, "?", timestamp, publicKey, NOW)).isFalse();
      assertThat(verifier.verify(body, signed, timestamp, "?", NOW)).isFalse();
   }

   /*******************************************************************************
    ** Mailgun validates the signed nonce, not its unsigned event body.
    *******************************************************************************/
   @Test
   void mailgunValidTamperedStaleMissing() throws Exception
   {
      var verifier = new MailgunVerifier();
      JSONObject data = signMailgun(new JSONObject(), "test-nonce", NOW);
      assertThat(verifier.verify(data, SECRET, NOW)).isTrue();
      assertThat(verifier.verify(data, SECRET, NOW.plusSeconds(301))).isFalse();
      assertThat(verifier.verify(data, SECRET, NOW.minusSeconds(301))).isFalse();
      assertThat(verifier.verify(data, null, NOW)).isFalse();
      assertThat(verifier.verify(data, "wrong", NOW)).isFalse();
      data.getJSONObject("signature").put("token", "changed");
      assertThat(verifier.verify(data, SECRET, NOW)).isFalse();
      assertThat(verifier.verify(new JSONObject(), SECRET, NOW)).isFalse();
      data.getJSONObject("signature").put("token", "");
      assertThat(verifier.verify(data, SECRET, NOW)).isFalse();
   }

   /*******************************************************************************
    ** Both SNS algorithms, optional Subject and certificate caching.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"1", "2"})
   void snsSignatureAndCache(String version) throws Exception
   {
      String pem = resource("/sns-test/test-only-certificate.pem");
      AtomicInteger requests = new AtomicInteger();
      var verifier = new SnsVerifier(request ->
      {
         requests.incrementAndGet();
         return (response(request, 200, pem));
      });
      JSONObject data = signSns(new JSONObject(resource("/webhooks/ses-bounce.json")).put("SignatureVersion", version));
      Instant now = certificateTime();
      assertThat(verifier.verify(data, TOPIC, now)).isTrue();
      assertThat(verifier.verify(signSns(data.put("Subject", "Test subject")), TOPIC, now)).isTrue();
      assertThat(requests.get()).isEqualTo(1);
      assertThat(verifier.verify(data, TOPIC + "other", now)).isFalse();
      assertThat(verifier.verify(data, null, now)).isFalse();
      data.put("Message", "tampered");
      assertThat(verifier.verify(data, TOPIC, now)).isFalse();
      data.put("SignatureVersion", "3");
      assertThat(verifier.verify(data, TOPIC, now)).isFalse();
   }

   /*******************************************************************************
    ** Notifications outside the one hour past / five minute future window are refused
    ** before any certificate fetch.
    *******************************************************************************/
   @Test
   void snsTimestampWindow() throws Exception
   {
      AtomicInteger requests = new AtomicInteger();
      String pem = resource("/sns-test/test-only-certificate.pem");
      var verifier = new SnsVerifier(request ->
      {
         requests.incrementAndGet();
         return (response(request, 200, pem));
      });
      Instant now = certificateTime();
      JSONObject base = new JSONObject(resource("/webhooks/ses-bounce.json"));
      assertThat(verifier.verify(signSns(new JSONObject(base.toString()), now.minusSeconds(3601)), TOPIC, now)).isFalse();
      assertThat(verifier.verify(signSns(new JSONObject(base.toString()), now.plusSeconds(301)), TOPIC, now)).isFalse();
      assertThat(requests.get()).isZero();
      assertThat(verifier.verify(signSns(new JSONObject(base.toString()), now.minusSeconds(3599)), TOPIC, now)).isTrue();
      assertThat(verifier.verify(signSns(new JSONObject(base.toString()), now.plusSeconds(299)), TOPIC, now)).isTrue();
      assertThat(verifier.verify(signSns(new JSONObject(base.toString())).put("Timestamp", "yesterday"), TOPIC, now)).isFalse();
   }

   /*******************************************************************************
    ** A failed certificate fetch is remembered per URL for five minutes.
    *******************************************************************************/
   @Test
   void snsCachesCertificateFetchFailures() throws Exception
   {
      AtomicInteger requests = new AtomicInteger();
      var verifier = new SnsVerifier(request ->
      {
         requests.incrementAndGet();
         return (response(request, 503, "down"));
      });
      Instant now = certificateTime();
      JSONObject data = signSns(new JSONObject(resource("/webhooks/ses-bounce.json")));
      assertThatThrownBy(() -> verifier.verify(data, TOPIC, now)).isInstanceOf(java.io.IOException.class);
      assertThatThrownBy(() -> verifier.verify(data, TOPIC, now.plusSeconds(299))).isInstanceOf(java.io.IOException.class);
      assertThat(requests.get()).isEqualTo(1);
      assertThatThrownBy(() -> verifier.verify(signSns(new JSONObject(data.toString()), now.plusSeconds(300)), TOPIC, now.plusSeconds(300))).isInstanceOf(java.io.IOException.class);
      assertThat(requests.get()).isEqualTo(2);
   }

   /*******************************************************************************
    ** Strict AWS URL host, scheme, path and authority before network access.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"http://sns.us-east-1.amazonaws.com/cert.pem", "https://evil.example/cert.pem", "https://sns.us-east-1.amazonaws.com/cert.txt",
      "https://sns.us-east-1.amazonaws.com.evil.example/cert.pem", "https://user@sns.us-east-1.amazonaws.com/cert.pem", "https://sns.us-east-1.amazonaws.com:8443/cert.pem",
      "https://sns.us-east-1.amazonaws.com/cert.pem?x=1", "not a URI"})
   void snsRejectsCertificateUrls(String url) throws Exception
   {
      var verifier = new SnsVerifier(request ->
      {
         throw (new AssertionError("Must not fetch untrusted URL"));
      });
      JSONObject data = new JSONObject(resource("/webhooks/ses-bounce.json")).put("SigningCertURL", url);
      assertThat(verifier.verify(data, TOPIC, certificateTime())).isFalse();
   }

   /*******************************************************************************
    ** Confirmations use AWS field order and only verified AWS URLs are fetched.
    *******************************************************************************/
   @Test
   void snsConfirmationsAndFailures() throws Exception
   {
      String pem = resource("/sns-test/test-only-certificate.pem");
      AtomicInteger confirms = new AtomicInteger();
      var verifier = new SnsVerifier(request ->
      {
         if(request.uri().getPath().endsWith(".pem"))
         {
            return (response(request, 200, pem));
         }
         confirms.incrementAndGet();
         return (response(request, 200, "confirmed"));
      });
      for(String type : List.of("SubscriptionConfirmation", "UnsubscribeConfirmation"))
      {
         JSONObject data = signSns(new JSONObject(resource("/webhooks/ses-bounce.json")).put("Type", type)
            .put("SubscribeURL", "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=test")
            .put("Token", "test-token"));
         assertThat(verifier.verify(data, TOPIC, certificateTime())).isTrue();
         verifier.confirm(data);
         assertThatThrownBy(() -> verifier.confirm(data.put("SubscribeURL", "https://evil.example/"))).isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(confirms.get()).isEqualTo(2);
      assertThat(SnsVerifier.allowedUrl("https://sns.cn-north-1.amazonaws.com.cn/test.pem", true)).isTrue();
      JSONObject data = signSns(new JSONObject(resource("/webhooks/ses-bounce.json")));
      assertThat(new SnsVerifier(request -> response(request, 200, "bad cert")).verify(data, TOPIC, certificateTime())).isFalse();
      assertThatThrownBy(() -> new SnsVerifier(request -> response(request, 503, "error")).verify(data, TOPIC, certificateTime())).isInstanceOf(java.io.IOException.class);
      assertThat(verifier.verify(data, TOPIC, Instant.parse("2000-01-01T00:00:00Z"))).isFalse();
      data.put("Type", "Unsupported");
      assertThat(verifier.verify(data, TOPIC, certificateTime())).isFalse();
   }
}
