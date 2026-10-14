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


import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Provider row and resolver. Resolved secrets are never retained.
 *******************************************************************************/
public final class EmailProviderSettings
{
   private final QRecord row;
   private final EmailQBitConfig config;

   /*******************************************************************************
    ** Settings for one attempt, without resolving secrets.
    *******************************************************************************/
   public EmailProviderSettings(QRecord row, EmailQBitConfig config)
   {
      this.row = row;
      this.config = config;
   }

   /*******************************************************************************
    ** Nonsecret provider setting.
    *******************************************************************************/
   public String value(String name)
   {
      return (row.getValueString(name));
   }

   /*******************************************************************************
    ** SMTP port, including the optional Gmail preset.
    *******************************************************************************/
   public int port()
   {
      return ("GMAIL_SMTP".equals(value("kind")) ? 587 : row.getValueInteger("port"));
   }

   /*******************************************************************************
    ** SMTP host.
    *******************************************************************************/
   public String host()
   {
      return ("GMAIL_SMTP".equals(value("kind")) ? "smtp.gmail.com" : value("host"));
   }

   /*******************************************************************************
    ** SMTP security.
    *******************************************************************************/
   public String security()
   {
      return ("GMAIL_SMTP".equals(value("kind")) ? "STARTTLS" : value("security"));
   }

   /*******************************************************************************
    ** Shared validation and send-time guard, without resolving credentials or DNS.
    *******************************************************************************/
   public boolean allowsSmtpAuth()
   {
      boolean credentials = value("usernameEnvName") != null || value("secretEnvName") != null;
      String security = value("security") == null ? security() : value("security");
      return (!credentials || "STARTTLS".equals(security) || "TLS".equals(security)
         || config.getAllowPlaintextSmtpAuth() || "localhost".equalsIgnoreCase(host())
         || "127.0.0.1".equals(host()) || "::1".equals(host()));
   }

   /*******************************************************************************
    ** A domain is a single DNS name, never a URL or a path component escape.
    *******************************************************************************/
   public static boolean validSendingDomain(String domain)
   {
      return (domain != null && domain.length() <= 253
         && domain.matches("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"));
   }

   /*******************************************************************************
    ** Tracking is opt-in; null database values mean disabled.
    *******************************************************************************/
   boolean enabled(String field)
   {
      return (Boolean.TRUE.equals(row.getValueBoolean(field)));
   }

   /*******************************************************************************
    ** HTTP providers must never send a missing token as the literal string null.
    *******************************************************************************/
   String requiredSecret() throws EmailTransportException
   {
      String secret = resolve("secretEnvName");
      if(secret == null)
      {
         throw (new EmailTransportException("Provider needs a secret environment name", false).withProviderLevel(true));
      }
      return (secret);
   }

   /*******************************************************************************
    ** Resolve an explicitly named secret immediately before sending.
    *******************************************************************************/
   String resolve(String field) throws EmailTransportException
   {
      String name = value(field);
      if(name == null)
      {
         return (null);
      }
      if(!EmailQBitConfig.ENV_NAME.matcher(name).matches() || !config.effectiveAllowedEnvNames(value("kind")).contains(name))
      {
         throw (new EmailTransportException("Credential environment name is not allowed", false).withProviderLevel(true));
      }
      try
      {
         String value = config.getSecretResolver().apply(name);
         if(value != null && !value.isBlank())
         {
            return (value);
         }
      }
      catch(RuntimeException e)
      {
         // Resolver exception text may contain a secret.
      }
      throw (new EmailTransportException("Credential environment variable is not set", false).withProviderLevel(true));
   }
}
