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

package com.kingsrook.qbits.email;


import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;


/*******************************************************************************
 ** Runtime lookups for the qbit.
 **
 ** <p>The config is read from the current QInstance on every call (never held
 ** in a static), so reconfiguring, or two instances in one JVM, never see
 ** stale settings. The only JVM-wide state is the runner identity
 ** and the clock, which tests replace.</p>
 *******************************************************************************/
public final class EmailRuntime
{

   private static final String NODE_ID = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);

   private static volatile Clock                         clock    = Clock.systemUTC();



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailRuntime()
   {
   }



   /*******************************************************************************
    ** The config in the current instance, or null when the qbit is not installed.
    *******************************************************************************/
   public static EmailQBitConfig config()
   {
      return (config(QContext.getQInstance()));
   }



   /*******************************************************************************
    ** The config in an instance, or null when the qbit is not installed.
    *******************************************************************************/
   public static EmailQBitConfig config(QInstance qInstance)
   {
      if(qInstance == null || qInstance.getQBits() == null)
      {
         return (null);
      }
      for(QBitMetaData qBit : qInstance.getQBits().values())
      {
         if(EmailQBitProducer.ARTIFACT_ID.equals(qBit.getArtifactId()) && qBit.getConfig() instanceof EmailQBitConfig config)
         {
            return (config);
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** True when the qbit is installed in the current instance.
    *******************************************************************************/
   public static boolean isInstalled()
   {
      return (config() != null);
   }



   /*******************************************************************************
    ** The clock (tests replace it).
    *******************************************************************************/
   public static Clock clock()
   {
      return (clock);
   }



   /*******************************************************************************
    ** Replace the clock (tests).
    *******************************************************************************/
   public static void setClock(Clock newClock)
   {
      clock = newClock == null ? Clock.systemUTC() : newClock;
   }



   /*******************************************************************************
    ** Runner identity stored on claimed attempts.
    *******************************************************************************/
   public static String nodeId()
   {
      return (NODE_ID);
   }
}
