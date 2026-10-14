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


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import org.json.JSONObject;


/*******************************************************************************
 ** Authenticate Mailgun; the durable inbox separately arbitrates nonce replay.
 *******************************************************************************/
public final class MailgunVerifier
{
   /*******************************************************************************
    ** Verify timestamp, token and hexadecimal HMAC without retaining the key.
    *******************************************************************************/
   public boolean verify(JSONObject payload, String secret, Instant now)
   {
      if(secret == null || secret.isBlank())
      {
         return (false);
      }
      try
      {
         JSONObject signed = payload.getJSONObject("signature");
         String timestamp = signed.get("timestamp").toString();
         String token = signed.getString("token");
         if(token.isBlank() || token.length() > 500 || !WebhookVerification.fresh(timestamp, now))
         {
            return (false);
         }
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
         String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + token).getBytes(StandardCharsets.UTF_8)));
         return (WebhookVerification.equal(expected, signed.getString("signature")));
      }
      catch(Exception e)
      {
         return (false);
      }
   }
}
