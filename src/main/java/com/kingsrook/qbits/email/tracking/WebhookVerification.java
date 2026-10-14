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


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Small shared verification primitives. Errors never retain request data.
 *******************************************************************************/
public final class WebhookVerification
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private WebhookVerification()
   {
   }

   /*******************************************************************************
    ** Resolve only an allowlisted webhook secret; null means unavailable.
    *******************************************************************************/
   public static String secret(QRecord provider)
   {
      EmailQBitConfig config = EmailRuntime.config();
      String name = provider.getValueString("webhookSecretEnvName");
      try
      {
         if(name != null && EmailQBitConfig.ENV_NAME.matcher(name).matches() && config.effectiveAllowedEnvNames(provider.getValueString("kind")).contains(name))
         {
            String value = config.getSecretResolver().apply(name);
            return (value == null || value.isBlank() ? null : value);
         }
      }
      catch(RuntimeException e)
      {
         return (null);
      }
      return (null);
   }

   /*******************************************************************************
    ** Compare bytes without data-dependent early exits.
    *******************************************************************************/
   public static boolean equal(String expected, String actual)
   {
      return (expected != null && actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)));
   }

   /*******************************************************************************
    ** Reject timestamps outside five minutes, including future timestamps.
    *******************************************************************************/
   public static boolean fresh(String timestamp, Instant now)
   {
      try
      {
         Instant time = Instant.ofEpochSecond(Long.parseLong(timestamp));
         return (!time.isBefore(now.minusSeconds(300)) && !time.isAfter(now.plusSeconds(300)));
      }
      catch(RuntimeException e)
      {
         return (false);
      }
   }

   /*******************************************************************************
    ** Stable payload and event fingerprints, never payload diagnostics.
    *******************************************************************************/
   public static String hash(byte[] bytes)
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
      catch(Exception e)
      {
         throw (new IllegalStateException("SHA-256 unavailable"));
      }
   }
}
