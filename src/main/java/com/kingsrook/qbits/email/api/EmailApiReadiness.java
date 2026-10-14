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

package com.kingsrook.qbits.email.api;


import java.util.EnumSet;
import java.util.List;
import java.util.Set;


/*******************************************************************************
 ** Configuration preflight, not a delivery guarantee. Reasons never contain secret values.
 *******************************************************************************/
public record EmailApiReadiness(boolean ready, List<String> reasons, Set<EmailApiReadinessCheck> failedChecks)
{
   /*******************************************************************************
    ** Copy configuration reasons.
    *******************************************************************************/
   public EmailApiReadiness
   {
      reasons = List.copyOf(reasons);
      failedChecks = failedChecks == null || failedChecks.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(failedChecks));
   }



   /*******************************************************************************
    ** Whether a specific prerequisite failed.
    *******************************************************************************/
   public boolean failed(EmailApiReadinessCheck check)
   {
      return (failedChecks.contains(check));
   }
}
