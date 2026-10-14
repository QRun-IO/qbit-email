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


import java.time.Duration;


/*******************************************************************************
 ** Sanitized transport failure. Never retain a provider exception or credential.
 *******************************************************************************/
public class EmailTransportException extends Exception
{
   private boolean permanent;
   private Duration retryAfter;

   /*******************************************************************************
    ** Create a safe failure with a permanent or transient classification.
    *******************************************************************************/
   public EmailTransportException(String message, boolean permanent)
   {
      super(message);
      this.permanent = permanent;
   }


   /*******************************************************************************
    ** Getter for permanent.
    *******************************************************************************/
   public boolean getPermanent()
   {
      return (permanent);
   }


   /*******************************************************************************
    ** Fluent setter for permanent.
    *******************************************************************************/
   public EmailTransportException withPermanent(boolean permanent)
   {
      this.permanent = permanent;
      return (this);
   }


   /*******************************************************************************
    ** Getter for retryAfter.
    *******************************************************************************/
   public Duration getRetryAfter()
   {
      return (retryAfter);
   }


   /*******************************************************************************
    ** Fluent setter for retryAfter.
    *******************************************************************************/
   public EmailTransportException withRetryAfter(Duration retryAfter)
   {
      this.retryAfter = retryAfter;
      return (this);
   }
}
