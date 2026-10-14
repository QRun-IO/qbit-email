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
import java.util.Objects;
import java.util.Optional;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.render.EmailBrands;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;


/*******************************************************************************
 ** Lifecycle and template edit permissions apply equally to forms and APIs.
 *******************************************************************************/
public class EmailTemplateCustomizers implements TableCustomizerInterface
{
   /*******************************************************************************
    ** Merge sparse updates and protect stored lifecycle state, not caller claims.
    *******************************************************************************/
   @Override
   public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> rows, boolean preview, Optional<List<QRecord>> oldRecords) throws QException
   {
      if(SystemWrites.active())
      {
         return (rows);
      }
      for(QRecord row : rows)
      {
         QRecord old = oldRecords.orElse(List.of()).stream().filter(v -> Objects.equals(v.getValue("id"), row.getValue("id"))).findFirst().orElse(new QRecord());
         QRecord merged = new QRecord();
         merged.getValues().putAll(old.getValues());
         merged.getValues().putAll(row.getValues());
         try
         {
            String table = input instanceof com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput insert ? insert.getTableName()
               : ((com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput) input).getTableName();
            if(EmailTables.BRAND.equals(table))
            {
               EmailBrands.validate(merged);
            }
            else if(EmailTables.TEMPLATE.equals(table))
            {
               EmailVersions.requireEdit(old.getValue("id") == null ? merged : old, false);
               protectedFields(row, old, List.of("phiApproved", "approvedVersionId", "phiApprovedBy", "phiApprovedDate", "activeVersionId"));
               if(Boolean.TRUE.equals(old.getValueBoolean("phiApproved")))
               {
                  protectedFields(row, old, List.of("kind", "category", "brandId", "name"));
               }
            }
            else
            {
               QRecord parent = EmailStore.get(EmailTables.TEMPLATE, merged.getValueInteger("templateId"), null);
               if(parent == null)
               {
                  throw (new IllegalArgumentException("Template is required"));
               }
               EmailVersions.requireEdit(parent, false);
               if(old.getValue("id") != null)
               {
                  protectedFields(row, old, List.of("templateId", "versionNumber", "status", "compiledHtml", "activatedDate"));
                  immutable(old, parent);
               }
               else
               {
                  if(merged.getValueString("status") != null && !"DRAFT".equals(merged.getValueString("status")))
                  {
                     throw (new IllegalArgumentException("New versions must be DRAFT"));
                  }
                  row.setValue("status", "DRAFT");
                  row.setValue("compiledHtml", null);
               }
               new VariableSchema(merged.getValueString("variablesSchema"));
            }
         }
         catch(IllegalArgumentException e)
         {
            row.addError(new BadInputStatusMessage(e.getMessage()));
         }
      }
      return (rows);
   }

   /*******************************************************************************
    ** Lifecycle-owned fields cannot be changed through ordinary CRUD.
    *******************************************************************************/
   private static void protectedFields(QRecord row, QRecord old, List<String> fields)
   {
      for(String field : fields)
      {
         Object value = row.getValue(field);
         if(row.getValues().containsKey(field) && !Objects.equals(value, old.getValue(field)) && !(old.getValue("id") == null && Boolean.FALSE.equals(value)))
         {
            throw (new IllegalArgumentException("Use the version actions to change " + field));
         }
      }
   }

   /*******************************************************************************
    ** Approving a draft freezes its content, preventing approval substitution.
    *******************************************************************************/
   private static void immutable(QRecord version, QRecord template)
   {
      if(!"DRAFT".equals(version.getValueString("status")) || Objects.equals(version.getValue("id"), template.getValue("approvedVersionId")))
      {
         throw (new IllegalArgumentException("ACTIVE, RETIRED and PHI-approved versions cannot be edited; use New Version"));
      }
   }

   /*******************************************************************************
    ** Preserve active/history rows and the same edit grants on deletion.
    *******************************************************************************/
   @Override
   public List<QRecord> preDelete(DeleteInput input, List<QRecord> rows, boolean preview) throws QException
   {
      if(SystemWrites.active())
      {
         return (rows);
      }
      for(QRecord row : rows)
      {
         try
         {
            if(EmailTables.VERSION.equals(input.getTableName()))
            {
               QRecord parent = EmailStore.get(EmailTables.TEMPLATE, row.getValueInteger("templateId"), input.getTransaction());
               EmailVersions.requireEdit(parent, false);
               immutable(row, parent);
            }
            else if(EmailTables.TEMPLATE.equals(input.getTableName()))
            {
               EmailVersions.requireEdit(row, false);
               if(!SystemWrites.query(EmailTables.VERSION, EmailStore.equal("templateId", row.getValueInteger("id")), input.getTransaction()).isEmpty())
               {
                  throw (new IllegalArgumentException("Templates with versions cannot be deleted"));
               }
            }
         }
         catch(IllegalArgumentException e)
         {
            row.addError(new BadInputStatusMessage(e.getMessage()));
         }
      }
      return (rows);
   }
}
