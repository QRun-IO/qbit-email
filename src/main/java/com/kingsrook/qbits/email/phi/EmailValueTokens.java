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

package com.kingsrook.qbits.email.phi;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** STAND-IN(core=#1161-impl, removeBy=RC.7). Mirrors ClassifiedValueTokens.
 ** Tokens are comparisons, never recoverable snapshots. No secret is retained.
 *******************************************************************************/
public final class EmailValueTokens
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailValueTokens()
   {
   }

   /*******************************************************************************
    ** Bind to table, field and record identity. TODO core: wipe-rule record
    ** fields will produce shred:v1; context values must never use shred:v1.
    *******************************************************************************/
   public static String forRecordField(QTableMetaData table, QFieldMetaData field, Serializable pk, Serializable value)
   {
      return (token(List.of(table.getName(), field.getName(), canonical(pk)), value));
   }

   /*******************************************************************************
    ** Non-record low-entropy inputs cannot yield dictionary-testable tokens.
    *******************************************************************************/
   public static String forContext(List<String> bindingParts, Serializable value)
   {
      if(value instanceof Boolean || canonical(value).codePoints().distinct().count() < 4)
      {
         return (changed());
      }
      return (token(bindingParts, value));
   }

   /*******************************************************************************
    ** Deliberately non-verifiable marker.
    *******************************************************************************/
   public static String changed()
   {
      return ("changed:v1");
   }

   /*******************************************************************************
    ** Verify only a genuine keyed token, with constant-time comparison.
    *******************************************************************************/
   public static boolean verifyRecordField(String token, QTableMetaData table, QFieldMetaData field, Serializable pk, Serializable value)
   {
      return (matches(token, forRecordField(table, field, pk, value)));
   }

   /*******************************************************************************
    ** Verify a context token, never changed or shred markers.
    *******************************************************************************/
   public static boolean verifyContext(String token, List<String> bindingParts, Serializable value)
   {
      return (matches(token, forContext(bindingParts, value)));
   }

   /*******************************************************************************
    ** Stable canonical representation shared with future core.
    *******************************************************************************/
   public static String canonical(Serializable value)
   {
      if(value instanceof BigDecimal decimal)
      {
         return (decimal.stripTrailingZeros().toPlainString());
      }
      if(value instanceof Instant instant)
      {
         return (instant.toString());
      }
      return (value == null ? "null" : value.toString());
   }

   /*******************************************************************************
    ** Construct the versioned HMAC without logging key or input.
    *******************************************************************************/
   private static String token(List<String> parts, Serializable value)
   {
      try
      {
         String secret = EmailRuntime.config().getSecretResolver().apply("QQQ_AUDIT_HMAC_KEY");
         if(secret == null || secret.isBlank())
         {
            return (changed());
         }
         byte[] key = Base64.getDecoder().decode(secret);
         String keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)).substring(0, 8);
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(key, "HmacSHA256"));
         List<String> binding = new ArrayList<>(parts);
         binding.add(canonical(value));
         return ("hmac:v1:" + keyId + ":" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(String.join("\u001f", binding).getBytes(StandardCharsets.UTF_8))));
      }
      catch(Exception e)
      {
         // An unavailable or malformed audit key must not reveal the value.
         return (changed());
      }
   }

   /*******************************************************************************
    ** Compare only HMAC tokens, never sentinel strings.
    *******************************************************************************/
   private static boolean matches(String supplied, String computed)
   {
      return (supplied != null && supplied.startsWith("hmac:v1:") && computed.startsWith("hmac:v1:")
         && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), computed.getBytes(StandardCharsets.UTF_8)));
   }
}
