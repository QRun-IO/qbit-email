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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONObject;

/*******************************************************************************
 ** Synthetic provider and crypto fixtures, always non-production.
 *******************************************************************************/
public final class TrackingTestSupport
{
   public static final Instant NOW = Instant.parse("2026-10-13T12:05:00Z");
   public static final String SECRET = "test-only-webhook-secret";
   public static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:email-events";

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private TrackingTestSupport()
   {
   }

   /*******************************************************************************
    ** Load a synthetic JSON or test-only PEM fixture from the classpath.
    *******************************************************************************/
   public static String resource(String name) throws Exception
   {
      try(var stream = TrackingTestSupport.class.getResourceAsStream(name))
      {
         return (new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
   }

   /*******************************************************************************
    ** Configure a fully valid provider row without resolving its sending key.
    *******************************************************************************/
   public static QRecord provider(String kind) throws Exception
   {
      return (H2Support.insert(EmailTables.PROVIDER, new QRecord().withValue("name", kind.toLowerCase(java.util.Locale.ROOT))
         .withValue("kind", kind).withValue("enabled", true).withValue("isDefault", H2Support.all(EmailTables.PROVIDER).isEmpty())
         .withValue("fromAddress", "sender@example.com").withValue("sendingDomain", "example.com").withValue("region", "SES".equals(kind) ? "us-east-1" : "US")
         .withValue("secretEnvName", switch(kind)
         {
            case "POSTMARK" -> "POSTMARK_SERVER_TOKEN";
            case "SENDGRID" -> "SENDGRID_API_KEY";
            case "MAILGUN" -> "MAILGUN_API_KEY";
            default -> null;
         })
         .withValue("webhookSecretEnvName", switch(kind)
         {
            case "POSTMARK" -> "POSTMARK_WEBHOOK_PASSWORD";
            case "SENDGRID" -> "SENDGRID_WEBHOOK_PUBLIC_KEY";
            case "MAILGUN" -> "MAILGUN_WEBHOOK_SIGNING_KEY";
            default -> null;
         })
         .withValue("snsTopicArn", TOPIC)));
   }

   /*******************************************************************************
    ** Persist an accepted outbox row through the same authorized scope as delivery.
    *******************************************************************************/
   public static QRecord message(QRecord provider) throws Exception
   {
      return (SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("providerId", provider.getValue("id"))
         .withValue("providerMessageId", "provider-message-1").withValue("status", "SENT").withValue("category", "MARKETING")
         .withValue("toAddresses", "recipient@example.com").withValue("sentDate", NOW.minusSeconds(600))));
   }

   /*******************************************************************************
    ** Test certificate controls the verification date independent of execution day.
    *******************************************************************************/
   public static Instant certificateTime() throws Exception
   {
      try(var stream = TrackingTestSupport.class.getResourceAsStream("/sns-test/test-only-certificate.pem"))
      {
         var cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
         return (cert.getNotBefore().toInstant().plusSeconds(60));
      }
   }

   /*******************************************************************************
    ** Sign independently using AWS's required order, not the implementation helper.
    *******************************************************************************/
   public static JSONObject signSns(JSONObject payload) throws Exception
   {
      return (signSns(payload, certificateTime()));
   }

   /*******************************************************************************
    ** Sign with an explicit envelope Timestamp (the verifier enforces freshness).
    *******************************************************************************/
   public static JSONObject signSns(JSONObject payload, Instant timestamp) throws Exception
   {
      payload.put("Timestamp", timestamp.toString());
      String pem = resource("/sns-test/test-only-private-key.pem").replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      List<String> fields = "Notification".equals(payload.getString("Type")) ? List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type")
         : List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
      StringBuilder canonical = new StringBuilder();
      for(String field : fields)
      {
         if(payload.has(field))
         {
            canonical.append(field).append('\n').append(payload.getString(field)).append('\n');
         }
      }
      Signature signer = Signature.getInstance("1".equals(payload.getString("SignatureVersion")) ? "SHA1withRSA" : "SHA256withRSA");
      signer.initSign(key);
      signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return (payload.put("Signature", Base64.getEncoder().encodeToString(signer.sign())));
   }

   /*******************************************************************************
    ** Sign Mailgun's timestamp and nonce with a clearly test-only HMAC key.
    *******************************************************************************/
   public static JSONObject signMailgun(JSONObject body, String nonce, Instant now) throws Exception
   {
      String timestamp = Long.toString(now.getEpochSecond());
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      body.put("signature", new JSONObject().put("timestamp", timestamp).put("token", nonce)
         .put("signature", java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + nonce).getBytes(StandardCharsets.UTF_8)))));
      return (body);
   }

   /*******************************************************************************
    ** Memory-only HTTP response for the existing EmailHttp seam.
    *******************************************************************************/
   public static HttpResponse<String> response(HttpRequest request, int status, String body)
   {
      return (new HttpResponse<>()
      {
         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public int statusCode()
         {
            return (status);
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public HttpRequest request()
         {
            return (request);
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public Optional<HttpResponse<String>> previousResponse()
         {
            return (Optional.empty());
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public HttpHeaders headers()
         {
            return (HttpHeaders.of(Map.of(), (a, b) -> true));
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public String body()
         {
            return (body);
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public Optional<SSLSession> sslSession()
         {
            return (Optional.empty());
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public URI uri()
         {
            return (request.uri());
         }

         /*******************************************************************************
          ** Test adapter implementation.
          *******************************************************************************/
         @Override
         public java.net.http.HttpClient.Version version()
         {
            return (java.net.http.HttpClient.Version.HTTP_1_1);
         }

      });
   }
}
