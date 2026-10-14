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


import java.util.HashMap;
import java.util.Map;


/*******************************************************************************
 ** Per-dispatcher registry; no configuration or credential caches.
 *******************************************************************************/
public class EmailTransportRegistry
{
   private final Map<String, EmailTransport> transports = new HashMap<>();

   /*******************************************************************************
    ** Install the outbound transports.
    *******************************************************************************/
   public EmailTransportRegistry()
   {
      transports.put("SMTP", new SmtpEmailTransport());
      transports.put("GMAIL_SMTP", new SmtpEmailTransport());
      transports.put("SES", new SesEmailTransport());
      transports.put("POSTMARK", new PostmarkEmailTransport());
      transports.put("SENDGRID", new SendGridEmailTransport());
      transports.put("MAILGUN", new MailgunEmailTransport());
   }

   /*******************************************************************************
    ** Host or test transport override.
    *******************************************************************************/
   public EmailTransportRegistry withTransport(String kind, EmailTransport transport)
   {
      transports.put(kind, transport);
      return (this);
   }

   /*******************************************************************************
    ** Resolve a kind, explicitly refusing future transports.
    *******************************************************************************/
   public EmailTransport get(String kind) throws EmailTransportException
   {
      EmailTransport transport = transports.get(kind);
      if(transport == null)
      {
         throw (new EmailTransportException("GMAIL_API".equals(kind)
            ? "Gmail API sending is specified but not built; use GMAIL_SMTP" : "Unknown email provider kind", true));
      }
      return (transport);
   }
}
