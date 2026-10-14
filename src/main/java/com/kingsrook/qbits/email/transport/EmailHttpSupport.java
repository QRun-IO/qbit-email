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

package com.kingsrook.qbits.email.transport;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;


/*******************************************************************************
 ** Shared serialization, timeouts and sanitized HTTP failures.
 *******************************************************************************/
final class EmailHttpSupport
{
   static final ObjectMapper JSON = new ObjectMapper();



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailHttpSupport()
   {
   }



   /*******************************************************************************
    ** Use the shared envelope validation even for JSON APIs.
    *******************************************************************************/
   static void validate(EmailEnvelope envelope) throws EmailTransportException
   {
      try
      {
         EmailMime.build(Session.getInstance(new Properties()), envelope);
      }
      catch(MessagingException | RuntimeException e)
      {
         throw (new EmailTransportException("Invalid email message", true));
      }
   }



   /*******************************************************************************
    ** Preserve application headers and the stable correlation identifier.
    *******************************************************************************/
   static Map<String, String> headers(EmailEnvelope envelope)
   {
      Map<String, String> headers = new LinkedHashMap<>();
      envelope.getHeaders().forEach((name, value) ->
      {
         if(!name.equalsIgnoreCase("Bcc") && !name.equalsIgnoreCase("Auto-Submitted") && !name.equalsIgnoreCase("X-QQQ-Email-Id") && !name.equalsIgnoreCase("Message-ID"))
         {
            headers.put(name, value);
         }
      });
      headers.put("Auto-Submitted", "auto-generated");
      headers.put("X-QQQ-Email-Id", envelope.getMessageId());
      if(envelope.getRfcMessageId() != null)
      {
         headers.put("Message-ID", envelope.getRfcMessageId());
      }
      return (headers);
   }



   /*******************************************************************************
    ** Serialize only in memory using Jackson already supplied by core.
    *******************************************************************************/
   static byte[] json(Object value) throws EmailTransportException
   {
      try
      {
         return (JSON.writeValueAsBytes(value));
      }
      catch(IOException | RuntimeException e)
      {
         throw (new EmailTransportException("Invalid email message", true));
      }
   }



   /*******************************************************************************
    ** Resolve auth before calling; never attach raw exceptions to a safe failure.
    *******************************************************************************/
   static HttpResponse<String> post(EmailHttp http, String url, String contentType, String authHeader, String auth, byte[] body) throws EmailTransportException
   {
      try
      {
         HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", contentType).header("Accept", "application/json").header(authHeader, auth)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
         return (http.send(request));
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
         throw (new EmailTransportException("Email HTTP request interrupted", false));
      }
      catch(IOException e)
      {
         throw (new EmailTransportException("Email HTTP temporarily unavailable", false));
      }
      catch(RuntimeException e)
      {
         throw (new EmailTransportException("Email HTTP configuration invalid", false).withProviderLevel(true));
      }
   }



   /*******************************************************************************
    ** Unparseable replies must not expose bodies or cause permanent message loss.
    *******************************************************************************/
   static JsonNode responseJson(HttpResponse<String> response) throws EmailTransportException
   {
      try
      {
         JsonNode json = JSON.readTree(response.body());
         if(json != null && json.isObject())
         {
            return (json);
         }
      }
      catch(IOException | RuntimeException e)
      {
         // Bodies and parser diagnostics may contain recipient addresses.
      }
      throw (new EmailTransportException("Email provider returned an invalid response", false));
   }



   /*******************************************************************************
    ** Parse either Retry-After syntax; the dispatcher applies the one-hour cap.
    *******************************************************************************/
   static Duration retryAfter(HttpResponse<String> response)
   {
      String value = response.headers().firstValue("Retry-After").orElse("").trim();
      try
      {
         long seconds = Long.parseLong(value);
         return (seconds < 0 ? null : Duration.ofSeconds(seconds));
      }
      catch(RuntimeException e)
      {
         try
         {
            Duration delay = Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return (delay.isNegative() ? Duration.ZERO : delay);
         }
         catch(RuntimeException ignored)
         {
            return (null);
         }
      }
   }



   /*******************************************************************************
    ** Only numeric status/code and fixed text escape the transport.
    *******************************************************************************/
   static EmailTransportException failure(HttpResponse<String> response, String provider, Integer code, boolean permanent, boolean providerLevel)
   {
      return (new EmailTransportException(provider + " request failed (HTTP " + response.statusCode()
         + (code == null ? "" : ", code " + code) + ")", permanent).withProviderLevel(providerLevel).withRetryAfter(retryAfter(response)));
   }



   /*******************************************************************************
    ** Common HTTP precedence: throttling and server errors always remain retryable.
    *******************************************************************************/
   static void checkStatus(HttpResponse<String> response, String provider, int success) throws EmailTransportException
   {
      int status = response.statusCode();
      if(status != success)
      {
         boolean transientFailure = status == 408 || status == 429 || status >= 500;
         boolean providerLevel = status == 401 || status == 403 || (status >= 300 && status < 400);
         throw (failure(response, provider, null, !transientFailure && !providerLevel && status >= 400, providerLevel));
      }
   }



   /*******************************************************************************
    ** A success must contain a usable id; never mistake a malformed body for success.
    *******************************************************************************/
   static EmailTransportResult result(String id) throws EmailTransportException
   {
      if(id == null || id.isBlank() || id.length() > 500 || id.contains("\r") || id.contains("\n"))
      {
         throw (new EmailTransportException("Email provider returned an invalid message id", false));
      }
      return (new EmailTransportResult(id));
   }
}

