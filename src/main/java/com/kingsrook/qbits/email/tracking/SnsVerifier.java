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


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.kingsrook.qbits.email.transport.EmailHttp;
import org.json.JSONObject;


/*******************************************************************************
 ** SNS verification with HTTPS AWS-only certificate retrieval and memory caching.
 *******************************************************************************/
public final class SnsVerifier
{
   private final EmailHttp http;
   private final Map<String, X509Certificate> certificates = new ConcurrentHashMap<>();

   /*******************************************************************************
    ** Default bounded HTTPS transport without redirects.
    *******************************************************************************/
   public SnsVerifier()
   {
      this(EmailHttp::sendJdk);
   }

   /*******************************************************************************
    ** Inject HTTP for deterministic certificate and confirmation tests.
    *******************************************************************************/
   public SnsVerifier(EmailHttp http)
   {
      this.http = http;
   }

   /*******************************************************************************
    ** Strict URL boundary applied before every network operation.
    *******************************************************************************/
   public static boolean allowedUrl(String value, boolean certificate)
   {
      try
      {
         URI uri = URI.create(value);
         return ("https".equals(uri.getScheme()) && uri.getHost() != null
            && uri.getHost().matches("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$")
            && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null
            && (!certificate || (uri.getPath().endsWith(".pem") && uri.getQuery() == null)));
      }
      catch(RuntimeException e)
      {
         return (false);
      }
   }

   /*******************************************************************************
    ** AWS's fixed order includes the final newline and optional Subject only.
    *******************************************************************************/
   public static String canonical(JSONObject payload)
   {
      String type = payload.getString("Type");
      List<String> fields;
      if("Notification".equals(type))
      {
         fields = payload.has("Subject") ? List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type")
            : List.of("Message", "MessageId", "Timestamp", "TopicArn", "Type");
      }
      else if("SubscriptionConfirmation".equals(type) || "UnsubscribeConfirmation".equals(type))
      {
         fields = List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
      }
      else
      {
         throw (new IllegalArgumentException("Unsupported SNS envelope"));
      }
      StringBuilder value = new StringBuilder();
      for(String field : fields)
      {
         value.append(field).append('\n').append(payload.getString(field)).append('\n');
      }
      return (value.toString());
   }

   /*******************************************************************************
    ** Authenticate the topic and signature; no confirmation before success.
    *******************************************************************************/
   public boolean verify(JSONObject payload, String topic, Instant now) throws IOException, InterruptedException
   {
      try
      {
         String url = payload.getString("SigningCertURL");
         if(topic == null || topic.isBlank() || !topic.equals(payload.getString("TopicArn")) || !allowedUrl(url, true))
         {
            return (false);
         }
         String algorithm = switch(payload.getString("SignatureVersion"))
         {
            case "1" -> "SHA1withRSA";
            case "2" -> "SHA256withRSA";
            default -> null;
         };
         if(algorithm == null)
         {
            return (false);
         }
         String canonical = canonical(payload);
         X509Certificate certificate = certificates.get(url);
         if(certificate == null || certificate.getNotAfter().toInstant().isBefore(now))
         {
            byte[] pem = get(url).getBytes(StandardCharsets.UTF_8);
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem));
            certificate.checkValidity(Date.from(now));
            if(certificates.size() >= 64)
            {
               certificates.clear();
            }
            certificates.put(url, certificate);
         }
         certificate.checkValidity(Date.from(now));
         Signature verifier = Signature.getInstance(algorithm);
         verifier.initVerify(certificate.getPublicKey());
         verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
         return (verifier.verify(Base64.getDecoder().decode(payload.getString("Signature"))));
      }
      catch(IOException | InterruptedException e)
      {
         throw (e);
      }
      catch(Exception e)
      {
         return (false);
      }
   }

   /*******************************************************************************
    ** Caller invokes this only after verifying the signed confirmation envelope.
    *******************************************************************************/
   public void confirm(JSONObject payload) throws IOException, InterruptedException
   {
      String url = payload.getString("SubscribeURL");
      if(!allowedUrl(url, false))
      {
         throw (new IllegalArgumentException("Invalid SNS confirmation URL"));
      }
      get(url);
   }

   /*******************************************************************************
    ** Fixed request timeout, no redirects, and no provider response diagnostics.
    *******************************************************************************/
   private String get(String url) throws IOException, InterruptedException
   {
      var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build());
      if(response.statusCode() != 200 || response.body() == null || response.body().length() > 65536)
      {
         throw (new IOException("SNS verification service unavailable"));
      }
      return (response.body());
   }
}
