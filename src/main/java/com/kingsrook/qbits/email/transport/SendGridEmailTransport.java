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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** SendGrid v3 mail send with explicit per-message tracking settings.
 *******************************************************************************/
public class SendGridEmailTransport implements EmailTransport
{
   private static final Set<String> RESERVED_HEADERS = Set.of("x-sg-id", "x-sg-eid", "received", "dkim-signature",
      "content-type", "content-transfer-encoding", "to", "from", "subject", "reply-to", "cc", "bcc");
   private final EmailHttp http;



   /*******************************************************************************
    ** Fixed production endpoint and JDK HTTP implementation.
    *******************************************************************************/
   public SendGridEmailTransport()
   {
      this(EmailHttp::sendJdk);
   }



   /*******************************************************************************
    ** Package-visible request/response test seam.
    *******************************************************************************/
   SendGridEmailTransport(EmailHttp http)
   {
      this.http = http;
   }



   /*******************************************************************************
    ** Build one personalization, keeping blind recipients out of custom headers.
    *******************************************************************************/
   @Override
   public EmailTransportResult send(EmailEnvelope envelope, EmailProviderSettings settings) throws EmailTransportException
   {
      EmailHttpSupport.validate(envelope);
      Map<String, Object> body = new LinkedHashMap<>();
      Map<String, Object> personalization = new LinkedHashMap<>();
      personalization.put("to", addresses(envelope.getTo()));
      if(!envelope.getCc().isEmpty())
      {
         personalization.put("cc", addresses(envelope.getCc()));
      }
      if(!envelope.getBcc().isEmpty())
      {
         personalization.put("bcc", addresses(envelope.getBcc()));
      }
      body.put("personalizations", List.of(personalization));
      body.put("from", addresses(List.of(envelope.getFrom())).getFirst());
      if(envelope.getReplyTo() != null)
      {
         body.put("reply_to", addresses(List.of(envelope.getReplyTo())).getFirst());
      }
      body.put("subject", envelope.getSubject());
      List<Map<String, String>> content = new ArrayList<>();
      if(envelope.getTextBody() != null && !envelope.getTextBody().isEmpty())
      {
         content.add(Map.of("type", "text/plain", "value", envelope.getTextBody()));
      }
      if(envelope.getHtmlBody() != null && !envelope.getHtmlBody().isEmpty())
      {
         content.add(Map.of("type", "text/html", "value", envelope.getHtmlBody()));
      }
      body.put("content", content);
      Map<String, String> headers = EmailHttpSupport.headers(envelope);
      headers.keySet().removeIf(name -> RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
      body.put("headers", headers);
      body.put("custom_args", Map.of("qqq_email_id", envelope.getMessageId()));
      body.put("tracking_settings", Map.of("open_tracking", Map.of("enable", settings.enabled("trackOpens")),
         "click_tracking", Map.of("enable", settings.enabled("trackClicks"), "enable_text", settings.enabled("trackClicks"))));
      HttpResponse<String> response = EmailHttpSupport.post(http, "https://api.sendgrid.com/v3/mail/send", "application/json",
         "Authorization", "Bearer " + settings.requiredSecret(), EmailHttpSupport.json(body));
      EmailHttpSupport.checkStatus(response, "SendGrid", 202);
      return (EmailHttpSupport.result(response.headers().firstValue("X-Message-Id").orElse(null)));
   }



   /*******************************************************************************
    ** Convert mailboxes without losing their decoded display names.
    *******************************************************************************/
   private static List<Map<String, String>> addresses(List<String> values) throws EmailTransportException
   {
      List<Map<String, String>> result = new ArrayList<>();
      try
      {
         for(String value : values)
         {
            for(InternetAddress address : InternetAddress.parse(value, true))
            {
               address.validate();
               Map<String, String> entry = new LinkedHashMap<>();
               entry.put("email", address.getAddress());
               if(address.getPersonal() != null)
               {
                  entry.put("name", address.getPersonal());
               }
               result.add(entry);
            }
         }
      }
      catch(AddressException e)
      {
         throw (new EmailTransportException("Invalid email message", true));
      }
      return (result);
   }
}

