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

package com.kingsrook.qbits.email.template;


import java.util.List;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Entry point for other qbits to ship their templates. A template is created
 ** once; after that this class never touches it, so people's edits are safe.
 *******************************************************************************/
public final class EmailTemplates
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailTemplates()
   {
   }

   /*******************************************************************************
    ** Create the template and an ACTIVE version 1 when no template of that name
    ** exists. The definition is validated like Activate Version (syntax, schema, a
    ** render with the samples, MJML compile); an invalid one is refused with
    ** plain reasons and nothing is written. Returns true only when this call
    ** created the template; the unique name arbitrates concurrent callers.
    *******************************************************************************/
   public static boolean ensure(EmailTemplateDefinition definition) throws QException
   {
      List<String> reasons = definition.problems();
      if(!reasons.isEmpty())
      {
         throw (new IllegalArgumentException("Template definition refused: " + String.join("; ", reasons)));
      }
      return (SystemWrites.call(() -> create(definition)));
   }

   /*******************************************************************************
    ** The checked, transactional creation, run as the qbit.
    *******************************************************************************/
   private static boolean create(EmailTemplateDefinition definition) throws QException
   {
      if(exists(definition.getName()))
      {
         return (false);
      }
      QRecord template = new QRecord().withValue("name", definition.getName()).withValue("label", definition.getLabel())
         .withValue("category", definition.getCategory().name()).withValue("kind", definition.getKind()).withValue("phiApproved", false)
         .withValue("ownerQbit", definition.getOwnerQbit()).withValue("editPermissionName", definition.getEditPermissionName());
      QRecord version = new QRecord().withValue("versionNumber", 1).withValue("status", "ACTIVE").withValue("locale", "en")
         .withValue("subject", definition.getSubject()).withValue("bodySource", definition.getBody()).withValue("textSource", definition.getTextBody())
         .withValue("variablesSchema", definition.getVariablesSchema()).withValue("sampleVariables", definition.getSampleVariables());
      try
      {
         EmailVersions.validate(template, version);
      }
      catch(IllegalArgumentException e)
      {
         throw (new IllegalArgumentException("Template definition refused: " + e.getMessage()));
      }
      try(QBackendTransaction tx = QBackendTransaction.openFor(new InsertInput(EmailTables.TEMPLATE)))
      {
         QRecord stored = SystemWrites.insert(EmailTables.TEMPLATE, List.of(template), tx).getRecords().getFirst();
         if(stored.getValue("id") == null || stored.getErrors() != null && !stored.getErrors().isEmpty())
         {
            tx.rollback();
            if(exists(definition.getName()))
            {
               return (false);
            }
            throw (new QException("Could not create template " + definition.getName()));
         }
         version.withValue("templateId", stored.getValue("id"));
         QRecord storedVersion = SystemWrites.insert(EmailTables.VERSION, List.of(version), tx).getRecords().getFirst();
         if(storedVersion.getValue("id") == null || storedVersion.getErrors() != null && !storedVersion.getErrors().isEmpty())
         {
            throw (new QException("Could not create template version for " + definition.getName()));
         }
         EmailStore.update(EmailTables.TEMPLATE, new QRecord().withValue("id", stored.getValue("id")).withValue("activeVersionId", storedVersion.getValue("id")), tx);
         tx.commit();
         return (true);
      }
   }

   /*******************************************************************************
    ** Whether a template of that name is already present.
    *******************************************************************************/
   private static boolean exists(String name) throws QException
   {
      return (!SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", name)).isEmpty());
   }
}
