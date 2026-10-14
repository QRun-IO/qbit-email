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
import java.util.Base64;


/*******************************************************************************
 ** Postmark authenticates with fixed username postmark and a resolved password.
 *******************************************************************************/
public final class PostmarkVerifier
{
   /*******************************************************************************
    ** Verify HTTP Basic credentials without logging or returning either value.
    *******************************************************************************/
   public boolean verify(String authorization, String secret)
   {
      if(secret == null || secret.isBlank() || authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6))
      {
         return (false);
      }
      try
      {
         String credentials = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
         return (WebhookVerification.equal("postmark:" + secret, credentials));
      }
      catch(IllegalArgumentException e)
      {
         return (false);
      }
   }
}
