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

package com.kingsrook.qbits.email.delivery;


import java.util.List;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qqq.backend.core.alerts.AlertSeverity;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.QAlertTypeMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;


/*******************************************************************************
 ** Optional alert sink integration with content-free signals.
 *******************************************************************************/
public final class EmailAlerts
{
   public static final String UNHEALTHY = "email.provider.unhealthy";
   public static final String DEAD_LETTER = "email.message.dead-letter";

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailAlerts()
   {
   }

   /*******************************************************************************
    ** Declare alert types using RC.5 dotted-name syntax.
    *******************************************************************************/
   public static List<QAlertTypeMetaData> types()
   {
      return (List.of(new QAlertTypeMetaData().withName(UNHEALTHY).withLabel("Email provider unhealthy").withDefaultSeverity(AlertSeverity.ERROR)
            .withSubjectTableName(EmailTables.PROVIDER).withVisibilityPermission("email.admin").withSourceName("email"),
         new QAlertTypeMetaData().withName(DEAD_LETTER).withLabel("Email dead letters").withDefaultSeverity(AlertSeverity.WARNING)
            .withSubjectTableName(EmailTables.MESSAGE).withVisibilityPermission("email.view").withSourceName("email")));
   }

   /*******************************************************************************
    ** Raise without addresses, provider diagnostics or content.
    *******************************************************************************/
   public static void raise(String type, Integer providerId)
   {
      String key = "provider:" + providerId + (DEAD_LETTER.equals(type) ? ":" + EmailRuntime.clock().instant().getEpochSecond() / 3600 : "");
      QAlerts.raise(new AlertSignal(type).withDedupKey(key).withTitle(UNHEALTHY.equals(type) ? "Email provider unhealthy" : "Email message dead-lettered")
         .withSummary("Review the Email tables for status and retry actions."));
   }

   /*******************************************************************************
    ** Resolve on recovery; core guards a missing or failing sink.
    *******************************************************************************/
   public static void recovered(Integer providerId)
   {
      QAlerts.resolve(UNHEALTHY, "provider:" + providerId, "An email attempt succeeded");
   }
}
