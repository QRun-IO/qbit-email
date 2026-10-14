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
 ** Built-ins are created on first use, transactionally and without JVM state.
 *******************************************************************************/
public final class EmailBuiltIns
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailBuiltIns()
   {
   }

   /*******************************************************************************
    ** Seed the secure-link pattern. The unique template name arbitrates replicas.
    *******************************************************************************/
   public static void ensure() throws QException
   {
      if(!SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", "email_secure_message")).isEmpty())
      {
         return;
      }
      try(QBackendTransaction tx = QBackendTransaction.openFor(new InsertInput(EmailTables.TEMPLATE)))
      {
         QRecord template = SystemWrites.insert(EmailTables.TEMPLATE, List.of(new QRecord().withValue("name", "email_secure_message")
            .withValue("label", "Secure message").withValue("kind", "MARKDOWN").withValue("category", "SECURITY").withValue("phiApproved", false)), tx).getRecords().getFirst();
         if(template.getValue("id") == null || template.getErrors() != null && !template.getErrors().isEmpty())
         {
            tx.rollback();
            return;
         }
         QRecord version = SystemWrites.insert(EmailTables.VERSION, List.of(new QRecord().withValue("templateId", template.getValue("id"))
            .withValue("versionNumber", 1).withValue("status", "ACTIVE").withValue("locale", "en").withValue("subject", "New secure message")
            .withValue("bodySource", "You have a new secure message from {{brand.label}}. Sign in to read it.\n\n[Read secure message]({{link}}){.button}")
            .withValue("variablesSchema", "[{\"name\":\"link\",\"type\":\"URL\",\"required\":true}]")
            .withValue("sampleVariables", "{\"link\":\"https://example.invalid/sign-in\"}")), tx).getRecords().getFirst();
         if(version.getValue("id") == null || version.getErrors() != null && !version.getErrors().isEmpty())
         {
            throw (new QException("Could not install secure-message template"));
         }
         EmailStore.update(EmailTables.TEMPLATE, new QRecord().withValue("id", template.getValue("id")).withValue("activeVersionId", version.getValue("id")), tx);
         tx.commit();
      }
   }
}
