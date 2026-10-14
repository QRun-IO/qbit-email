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


import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** STAND-IN(core=#1142, removeBy=RC.7). Core classification wins when available;
 ** overrides then schema declarations provide the RC.5 compatibility contract.
 *******************************************************************************/
public final class EmailClassifications
{
   /*******************************************************************************
    ** Ordered data sensitivity.
    *******************************************************************************/
   public enum Level
   {
      PUBLIC, CONFIDENTIAL, PII, PHI
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailClassifications()
   {
   }

   /*******************************************************************************
    ** Resolve a field without a compile-time dependency on unreleased core.
    *******************************************************************************/
   public static Level ofField(QTableMetaData table, String field, String declared, EmailQBitConfig config)
   {
      if(table != null && field != null)
      {
         try
         {
            Class<?> core = Class.forName("com.kingsrook.qqq.backend.core.actions.ai.AiDataClassifications");
            Object value = core.getMethod("ofField", QTableMetaData.class, String.class).invoke(null, table, field);
            if(value instanceof Enum<?> level)
            {
               return (Level.valueOf(level.name()));
            }
         }
         catch(ClassNotFoundException e)
         {
            // Expected on RC.5; use the explicit stand-in configuration.
         }
         catch(ReflectiveOperationException | IllegalArgumentException e)
         {
            throw (new IllegalArgumentException("Field classification is unavailable"));
         }
         Level override = config.getClassificationOverrides().get(table.getName() + "." + field);
         if(override != null)
         {
            return (override);
         }
      }
      return (declared == null ? Level.PUBLIC : Level.valueOf(declared));
   }

   /*******************************************************************************
    ** PII has the same email approval requirement as PHI.
    *******************************************************************************/
   public static boolean sensitive(Level level)
   {
      return (level == Level.PII || level == Level.PHI);
   }
}
