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

package com.kingsrook.qbits.email.render;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.template.VariableSchema;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** Queue-time link signing and verification from DESIGN 8.2.
 *******************************************************************************/
public final class EmailUnsubscribeLinks
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailUnsubscribeLinks()
   {
   }

   /*******************************************************************************
    ** Bind the message, single mailbox hash and category without exposing PII.
    ** No key means no link/header; marketing must refuse rather than omit opt-out.
    *******************************************************************************/
   public static String create(String messageId, String recipient, String category)
   {
      if(!"NOTIFICATION".equals(category) && !"MARKETING".equals(category))
      {
         return (null);
      }
      String key;
      try
      {
         key = EmailRuntime.config().getSecretResolver().apply("QQQ_EMAIL_UNSUBSCRIBE_KEY");
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Opt-out email needs publicBaseUrl and QQQ_EMAIL_UNSUBSCRIBE_KEY"));
      }
      String base = EmailRuntime.config().getPublicBaseUrl();
      if(key == null || key.isBlank() || base == null || base.isBlank())
      {
         if("MARKETING".equals(category))
         {
            throw (new IllegalArgumentException("Opt-out email needs publicBaseUrl and QQQ_EMAIL_UNSUBSCRIBE_KEY"));
         }
         return (null);
      }
      String address = recipient(recipient, null, null);
      try
      {
         VariableSchema.url(base);
         String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));
         String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(VariableSchema.json(Map.of("messageId", messageId, "addressHash", hash, "category", category)).getBytes(StandardCharsets.UTF_8));
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
         String signed = "v1." + payload;
         String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
         return (base.replaceAll("/+$", "") + EmailRuntime.config().getWebhookBasePath() + "/unsubscribe/" + signed + "." + signature);
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Opt-out email needs publicBaseUrl and QQQ_EMAIL_UNSUBSCRIBE_KEY"));
      }
   }

   /*******************************************************************************
    ** Exactly one To mailbox, canonicalized for signing and category suppression.
    *******************************************************************************/
   public static String recipient(String to, String cc, String bcc)
   {
      try
      {
         InternetAddress[] addresses = InternetAddress.parse(to == null ? "" : to, true);
         if(addresses.length == 1 && !addresses[0].isGroup() && addresses[0].getAddress().contains("@")
            && (cc == null || cc.isBlank()) && (bcc == null || bcc.isBlank()))
         {
            addresses[0].validate();
            return (addresses[0].getAddress().toLowerCase(Locale.ROOT));
         }
      }
      catch(jakarta.mail.internet.AddressException e)
      {
         // Malformed stored envelopes cannot authorize an opt-out.
      }
      throw (new IllegalArgumentException("Notification and marketing email with an unsubscribe link goes to one recipient per message; send one message per recipient."));
   }

   /*******************************************************************************
    ** Verify the signed format before any lookup or side effect.
    ** Invalid and non-opt-out categories are indistinguishable to callers.
    *******************************************************************************/
   public static org.json.JSONObject verify(String token)
   {
      try
      {
         if(token == null || token.length() > 4096)
         {
            return (null);
         }
         String[] parts = token.split("\\.", -1);
         String key = EmailRuntime.config().getSecretResolver().apply("QQQ_EMAIL_UNSUBSCRIBE_KEY");
         if(parts.length != 3 || !"v1".equals(parts[0]) || key == null || key.isBlank())
         {
            return (null);
         }
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
         if(!MessageDigest.isEqual(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)), Base64.getUrlDecoder().decode(parts[2])))
         {
            return (null);
         }
         var payload = new org.json.JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
         String category = payload.getString("category");
         return (("MARKETING".equals(category) || "NOTIFICATION".equals(category)) ? payload : null);
      }
      catch(Exception e)
      {
         return (null);
      }
   }
}
