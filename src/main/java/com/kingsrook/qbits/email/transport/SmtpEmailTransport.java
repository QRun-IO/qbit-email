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


import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;


/*******************************************************************************
 ** Authenticated or relay SMTP with bounded connect, read and write timeouts.
 *******************************************************************************/
public class SmtpEmailTransport implements EmailTransport
{
   /*******************************************************************************
    ** Submit one claimed message. Provider diagnostics are never retained.
    *******************************************************************************/
   @Override
   public EmailTransportResult send(EmailEnvelope envelope, EmailProviderSettings settings) throws EmailTransportException
   {
      Properties properties = new Properties();
      properties.setProperty("mail.smtp.connectiontimeout", "10000");
      properties.setProperty("mail.smtp.timeout", "15000");
      properties.setProperty("mail.smtp.writetimeout", "15000");
      properties.setProperty("mail.smtp.auth", String.valueOf(settings.value("usernameEnvName") != null));
      properties.setProperty("mail.smtp.starttls.enable", String.valueOf("STARTTLS".equals(settings.security())));
      properties.setProperty("mail.smtp.starttls.required", String.valueOf("STARTTLS".equals(settings.security())));
      properties.setProperty("mail.smtp.ssl.enable", String.valueOf("TLS".equals(settings.security())));
      properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
      Session session = Session.getInstance(properties);
      try(Transport transport = session.getTransport("smtp"))
      {
         MimeMessage message = EmailMime.build(session, envelope);
         String username = settings.resolve("usernameEnvName");
         String password = username == null ? null : settings.resolve("secretEnvName");
         transport.connect(settings.host(), settings.port(), username, password);
         transport.sendMessage(message, message.getAllRecipients());
         return (new EmailTransportResult(message.getMessageID()));
      }
      catch(MessagingException e)
      {
         boolean permanent = permanent(e);
         throw (new EmailTransportException(permanent ? "SMTP rejected the message" : "SMTP temporarily unavailable", permanent));
      }
   }

   /*******************************************************************************
    ** Inspect chained SMTP reply codes without exposing addresses or diagnostics.
    *******************************************************************************/
   static boolean permanent(Throwable error)
   {
      Set<Throwable> seen = new HashSet<>();
      while(error != null && seen.add(error))
      {
         if(error instanceof SMTPAddressFailedException address && address.getReturnCode() >= 500)
         {
            return (true);
         }
         if(error instanceof SMTPSendFailedException send && send.getReturnCode() >= 500)
         {
            return (true);
         }
         if(error instanceof AuthenticationFailedException)
         {
            // Authentication can also fail temporarily (for example SMTP 454).
            return (error.getMessage() == null || !error.getMessage().matches("(?s)^4[0-9]{2}[ -].*"));
         }
         error = error instanceof MessagingException messaging && messaging.getNextException() != null ? messaging.getNextException() : error.getCause();
      }
      return (false);
   }
}

