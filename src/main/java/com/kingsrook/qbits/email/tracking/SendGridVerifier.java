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
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;


/*******************************************************************************
 ** Verify SendGrid's timestamp and raw body with its DER encoded EC public key.
 *******************************************************************************/
public final class SendGridVerifier
{
   /*******************************************************************************
    ** The timestamp bytes precede the unchanged request body.
    *******************************************************************************/
   public boolean verify(byte[] body, String signature, String timestamp, String secret, Instant now)
   {
      if(secret == null || secret.isBlank() || signature == null || !WebhookVerification.fresh(timestamp, now))
      {
         return (false);
      }
      try
      {
         var key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(secret)));
         Signature verifier = Signature.getInstance("SHA256withECDSA");
         verifier.initVerify(key);
         verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
         verifier.update(body);
         return (verifier.verify(Base64.getDecoder().decode(signature)));
      }
      catch(Exception e)
      {
         return (false);
      }
   }
}
