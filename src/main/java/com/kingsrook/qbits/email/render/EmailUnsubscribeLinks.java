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
import java.util.Map;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.template.VariableSchema;


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
    ** Bind the message, recipient-envelope hash and category without exposing PII.
    ** No key means no link/header; marketing must refuse rather than omit opt-out.
    *******************************************************************************/
   public static String create(String messageId, String recipients, String category)
   {
      if(!"NOTIFICATION".equals(category) && !"MARKETING".equals(category))
      {
         return (null);
      }
      try
      {
         String key = EmailRuntime.config().getSecretResolver().apply("QQQ_EMAIL_UNSUBSCRIBE_KEY");
         String base = EmailRuntime.config().getPublicBaseUrl();
         if(key == null || key.isBlank() || base == null || base.isBlank())
         {
            if("MARKETING".equals(category))
            {
               throw (new IllegalArgumentException());
            }
            return (null);
         }
         VariableSchema.url(base);
         String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(recipients.getBytes(StandardCharsets.UTF_8)));
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
    ** Verify the existing envelope-bound format before any lookup or side effect.
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
