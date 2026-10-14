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


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** Mailgun MIME upload, entirely in memory, with explicit blind destinations.
 *******************************************************************************/
public class MailgunEmailTransport implements EmailTransport
{
   private final EmailHttp http;



   /*******************************************************************************
    ** Fixed regional production endpoints and JDK HTTP implementation.
    *******************************************************************************/
   public MailgunEmailTransport()
   {
      this(EmailHttp::sendJdk);
   }



   /*******************************************************************************
    ** Package-visible request/response test seam.
    *******************************************************************************/
   MailgunEmailTransport(EmailHttp http)
   {
      this.http = http;
   }



   /*******************************************************************************
    ** Envelope destinations include Bcc; serialized MIME never includes its header.
    *******************************************************************************/
   @Override
   public EmailTransportResult send(EmailEnvelope envelope, EmailProviderSettings settings) throws EmailTransportException
   {
      String domain = settings.value("sendingDomain");
      String region = settings.value("region");
      if(!EmailProviderSettings.validSendingDomain(domain) || (!"US".equals(region) && !"EU".equals(region)))
      {
         throw (new EmailTransportException("Mailgun needs a sending domain and US or EU region", false).withProviderLevel(true));
      }
      String boundary = "qqq-email-" + UUID.randomUUID();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      try
      {
         List<String> destinations = new ArrayList<>();
         for(List<String> role : List.of(envelope.getTo(), envelope.getCc(), envelope.getBcc()))
         {
            for(String value : role)
            {
               for(InternetAddress address : InternetAddress.parse(value, true))
               {
                  address.validate();
                  destinations.add(address.getAddress());
               }
            }
         }
         field(body, boundary, "to", String.join(",", destinations));
         field(body, boundary, "o:tracking-opens", settings.enabled("trackOpens") ? "yes" : "no");
         field(body, boundary, "o:tracking-clicks", settings.enabled("trackClicks") ? "yes" : "no");
         field(body, boundary, "v:qqq_email_id", envelope.getMessageId());
         body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"message\"; filename=\"message.eml\"\r\n"
            + "Content-Type: message/rfc822\r\n\r\n").getBytes(StandardCharsets.UTF_8));
         EmailMime.build(Session.getInstance(new Properties()), envelope).writeTo(body, new String[] {"Bcc"});
         body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      }
      catch(MessagingException | IOException | RuntimeException e)
      {
         throw (new EmailTransportException("Invalid email message", true));
      }
      String auth = Base64.getEncoder().encodeToString(("api:" + settings.requiredSecret()).getBytes(StandardCharsets.UTF_8));
      String host = "EU".equals(region) ? "https://api.eu.mailgun.net" : "https://api.mailgun.net";
      HttpResponse<String> response = EmailHttpSupport.post(http, host + "/v3/" + domain + "/messages.mime",
         "multipart/form-data; boundary=" + boundary, "Authorization", "Basic " + auth, body.toByteArray());
      EmailHttpSupport.checkStatus(response, "Mailgun", 200);
      String id = EmailHttpSupport.responseJson(response).path("id").textValue();
      if(id != null && id.startsWith("<") && id.endsWith(">"))
      {
         id = id.substring(1, id.length() - 1);
      }
      return (EmailHttpSupport.result(id));
   }



   /*******************************************************************************
    ** Encode fixed-name UTF-8 multipart fields without touching the filesystem.
    *******************************************************************************/
   private static void field(ByteArrayOutputStream body, String boundary, String name, String value)
   {
      body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
         + value + "\r\n").getBytes(StandardCharsets.UTF_8));
   }
}

