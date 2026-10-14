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

package com.kingsrook.qbits.email.outbox;


import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueEnum;


/*******************************************************************************
 ** Purpose of a message; transactional and security messages cannot be opted out.
 *******************************************************************************/
public enum EmailCategory implements PossibleValueEnum<String>
{
   TRANSACTIONAL,
   NOTIFICATION,
   MARKETING,
   SECURITY;

   /*******************************************************************************
    ** Stable database value.
    *******************************************************************************/
   @Override
   public String getPossibleValueId()
   {
      return (name());
   }

   /*******************************************************************************
    ** Display label.
    *******************************************************************************/
   @Override
   public String getPossibleValueLabel()
   {
      return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT));
   }
}
