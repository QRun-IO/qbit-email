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


import java.util.Date;
import java.util.List;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;


/*******************************************************************************
 ** Shared UTF-8 multipart MIME construction for SMTP and SES.
 *******************************************************************************/
final class EmailMime
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailMime()
   {
   }

   /*******************************************************************************
    ** Construct both alternative parts and stable application headers.
    *******************************************************************************/
   static MimeMessage build(Session session, EmailEnvelope envelope) throws MessagingException
   {
      MimeMessage message = new MimeMessage(session);
      message.setFrom(new InternetAddress(envelope.getFrom(), true));
      recipients(message, Message.RecipientType.TO, envelope.getTo());
      recipients(message, Message.RecipientType.CC, envelope.getCc());
      recipients(message, Message.RecipientType.BCC, envelope.getBcc());
      if(envelope.getReplyTo() != null)
      {
         message.setReplyTo(InternetAddress.parse(envelope.getReplyTo(), true));
      }
      message.setSubject(envelope.getSubject(), "UTF-8");
      message.setSentDate(new Date());
      MimeMultipart alternative = new MimeMultipart("alternative");
      MimeBodyPart text = new MimeBodyPart();
      text.setText(envelope.getTextBody() == null ? "" : envelope.getTextBody(), "UTF-8");
      alternative.addBodyPart(text);
      MimeBodyPart html = new MimeBodyPart();
      html.setContent(envelope.getHtmlBody() == null ? "" : envelope.getHtmlBody(), "text/html; charset=UTF-8");
      alternative.addBodyPart(html);
      message.setContent(alternative);
      for(var header : envelope.getHeaders().entrySet())
      {
         if(!header.getKey().matches("[A-Za-z0-9-]+") || header.getValue().contains("\r") || header.getValue().contains("\n"))
         {
            throw (new MessagingException("Invalid envelope header"));
         }
         message.setHeader(header.getKey(), header.getValue());
      }
      message.setHeader("Auto-Submitted", "auto-generated");
      message.setHeader("X-QQQ-Email-Id", envelope.getMessageId());
      message.saveChanges();
      return (message);
   }

   /*******************************************************************************
    ** Add one recipient role.
    *******************************************************************************/
   private static void recipients(MimeMessage message, Message.RecipientType type, List<String> addresses) throws MessagingException
   {
      if(!addresses.isEmpty())
      {
         message.setRecipients(type, InternetAddress.parse(String.join(",", addresses), true));
      }
   }
}
