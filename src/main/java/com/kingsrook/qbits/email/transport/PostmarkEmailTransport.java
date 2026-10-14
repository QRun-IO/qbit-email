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


import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;


/*******************************************************************************
 ** Postmark's single-message API with per-attempt server token resolution.
 *******************************************************************************/
public class PostmarkEmailTransport implements EmailTransport
{
   private final EmailHttp http;



   /*******************************************************************************
    ** Fixed production endpoint and JDK HTTP implementation.
    *******************************************************************************/
   public PostmarkEmailTransport()
   {
      this(EmailHttp::sendJdk);
   }



   /*******************************************************************************
    ** Package-visible request/response test seam.
    *******************************************************************************/
   PostmarkEmailTransport(EmailHttp http)
   {
      this.http = http;
   }



   /*******************************************************************************
    ** Send a rendered message; provider response diagnostics never escape.
    *******************************************************************************/
   @Override
   public EmailTransportResult send(EmailEnvelope envelope, EmailProviderSettings settings) throws EmailTransportException
   {
      EmailHttpSupport.validate(envelope);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("From", envelope.getFrom());
      body.put("To", String.join(",", envelope.getTo()));
      body.put("Cc", String.join(",", envelope.getCc()));
      body.put("Bcc", String.join(",", envelope.getBcc()));
      if(envelope.getReplyTo() != null)
      {
         body.put("ReplyTo", envelope.getReplyTo());
      }
      body.put("Subject", envelope.getSubject());
      if(envelope.getHtmlBody() != null)
      {
         body.put("HtmlBody", envelope.getHtmlBody());
      }
      if(envelope.getTextBody() != null)
      {
         body.put("TextBody", envelope.getTextBody());
      }
      body.put("Headers", EmailHttpSupport.headers(envelope).entrySet().stream()
         .map(h -> Map.of("Name", h.getKey(), "Value", h.getValue())).toList());
      String stream = settings.value("messageStream");
      body.put("MessageStream", stream == null || stream.isBlank() ? "outbound" : stream);
      body.put("TrackOpens", settings.enabled("trackOpens"));
      body.put("TrackLinks", settings.enabled("trackClicks") ? "HtmlAndText" : "None");
      HttpResponse<String> response = EmailHttpSupport.post(http, "https://api.postmarkapp.com/email", "application/json",
         "X-Postmark-Server-Token", settings.requiredSecret(), EmailHttpSupport.json(body));
      int status = response.statusCode();
      if(status == 408 || status == 429 || status >= 500 || status == 401 || status == 403 || (status >= 300 && status < 400))
      {
         EmailHttpSupport.checkStatus(response, "Postmark", 200);
      }
      JsonNode json;
      try
      {
         json = EmailHttpSupport.responseJson(response);
      }
      catch(EmailTransportException e)
      {
         EmailHttpSupport.checkStatus(response, "Postmark", 200);
         throw (e);
      }
      Integer code = json.path("ErrorCode").canConvertToInt() ? json.path("ErrorCode").intValue() : null;
      if(status == 200 && code == null)
      {
         throw (new EmailTransportException("Email provider returned an invalid response", false));
      }
      if(status != 200 || code == null || code != 0)
      {
         // Postmark ErrorCode (not HTTP status) | Classification
         // 10 bad token                        | provider-level
         // 300 invalid request, 406 inactive   | permanent message failure
         // 400/401 sender signature, 405 send disabled, 412 pending approval,
         // 413 account unapproved, 422 invalid server, 1235/1236 stream,
         // 1480 IP restriction                 | provider-level
         // HTTP 429 / 5xx                      | transient, takes precedence
         // Other request codes                | permanent (unknown 200 is transient)
         boolean providerLevel = code != null && Set.of(10, 400, 401, 405, 412, 413, 422, 1235, 1236, 1480).contains(code);
         throw (EmailHttpSupport.failure(response, "Postmark", code, !providerLevel && (status >= 400 || code != null && code != 0), providerLevel));
      }
      return (EmailHttpSupport.result(json.path("MessageID").textValue()));
   }
}

