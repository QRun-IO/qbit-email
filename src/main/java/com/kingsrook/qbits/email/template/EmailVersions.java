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
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Atomic version lifecycle. Approval binds to exactly one immutable version.
 *******************************************************************************/
public final class EmailVersions
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailVersions()
   {
   }

   /*******************************************************************************
    ** Enforce both the qbit permission and the template's independent edit grant.
    *******************************************************************************/
   public static void requireEdit(QRecord template, boolean approval)
   {
      if(SystemWrites.active())
      {
         return;
      }
      if(!EmailPermissionChecker.has(approval ? EmailPermissions.ADMIN : EmailPermissions.MANAGE))
      {
         throw (new IllegalArgumentException(approval ? "PHI approval needs email.admin" : "Editing templates needs email.manage"));
      }
      String permission = template.getValueString("editPermissionName");
      if(permission != null && !permission.isBlank() && (QContext.getQSession() == null || !QContext.getQSession().hasPermission(permission)))
      {
         throw (new IllegalArgumentException("Editing this template needs " + permission));
      }
   }

   /*******************************************************************************
    ** Serialize lifecycle operations across replicas using the template row.
    *******************************************************************************/
   private static QRecord lock(Integer templateId, QBackendTransaction tx) throws QException
   {
      QRecord template = EmailStore.get(EmailTables.TEMPLATE, templateId, tx);
      if(template == null)
      {
         throw (new IllegalArgumentException("Template not found"));
      }
      requireEdit(template, false);
      EmailStore.update(EmailTables.TEMPLATE, new QRecord().withValue("id", templateId).withValue("versionLock", 0), tx);
      return (EmailStore.get(EmailTables.TEMPLATE, templateId, tx));
   }

   /*******************************************************************************
    ** Copy active (or latest) into a new draft, with a globally increasing number.
    *******************************************************************************/
   public static QRecord newVersion(Integer templateId) throws QException
   {
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.TEMPLATE)))
      {
         QRecord template = lock(templateId, tx);
         List<QRecord> versions = SystemWrites.query(EmailTables.VERSION, EmailStore.equal("templateId", templateId)
            .withOrderBy(new QFilterOrderBy("versionNumber", false)), tx);
         QRecord source = versions.stream().filter(v -> Objects.equals(template.getValue("activeVersionId"), v.getValue("id"))).findFirst()
            .orElse(versions.isEmpty() ? new QRecord().withValue("locale", "en").withValue("variablesSchema", "[]").withValue("sampleVariables", "{}") : versions.getFirst());
         QRecord draft = new QRecord();
         for(String field : List.of("locale", "subject", "preheader", "bodySource", "textSource", "variablesSchema", "sampleVariables"))
         {
            draft.setValue(field, source.getValue(field));
         }
         draft.withValue("templateId", templateId).withValue("versionNumber", versions.isEmpty() ? 1 : versions.getFirst().getValueInteger("versionNumber") + 1)
            .withValue("status", "DRAFT").withValue("createdBy", actor());
         QRecord result = SystemWrites.insert(EmailTables.VERSION, List.of(draft), tx).getRecords().getFirst();
         if(result.getValue("id") == null || result.getErrors() != null && !result.getErrors().isEmpty())
         {
            throw (new QException("Could not create template version"));
         }
         tx.commit();
         return (result);
      }
   }

   /*******************************************************************************
    ** Approval is granted to the selected draft or active version, never retired.
    *******************************************************************************/
   public static void approve(Integer versionId) throws QException
   {
      QRecord version = requiredVersion(versionId, null);
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.TEMPLATE)))
      {
         QRecord template = lock(version.getValueInteger("templateId"), tx);
         requireEdit(template, true);
         version = requiredVersion(versionId, tx);
         if("RETIRED".equals(version.getValueString("status")) || "MARKETING".equals(template.getValueString("category")))
         {
            throw (new IllegalArgumentException("Retired versions and MARKETING templates cannot be approved for PHI"));
         }
         EmailStore.update(EmailTables.TEMPLATE, new QRecord().withValue("id", template.getValue("id"))
            .withValue("phiApproved", true).withValue("approvedVersionId", versionId).withValue("phiApprovedBy", actor())
            .withValue("phiApprovedDate", EmailRuntime.clock().instant()), tx);
         tx.commit();
      }
   }

   /*******************************************************************************
    ** Validate samples and syntax, compile MJML, then atomically retire and activate.
    *******************************************************************************/
   public static void activate(Integer versionId) throws QException
   {
      QRecord initial = requiredVersion(versionId, null);
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.TEMPLATE)))
      {
         QRecord template = lock(initial.getValueInteger("templateId"), tx);
         QRecord version = requiredVersion(versionId, tx);
         if(!"DRAFT".equals(version.getValueString("status")))
         {
            throw (new IllegalArgumentException("Only a DRAFT version can be activated"));
         }
         validate(template, version);
         for(QRecord previous : SystemWrites.query(EmailTables.VERSION, EmailStore.equal("templateId", template.getValueInteger("id")), tx))
         {
            if("ACTIVE".equals(previous.getValueString("status")) && Objects.equals(version.getValue("locale"), previous.getValue("locale")))
            {
               EmailStore.update(EmailTables.VERSION, new QRecord().withValue("id", previous.getValue("id")).withValue("status", "RETIRED"), tx);
            }
         }
         EmailStore.update(EmailTables.VERSION, new QRecord().withValue("id", versionId).withValue("status", "ACTIVE")
            .withValue("compiledHtml", version.getValue("compiledHtml")).withValue("activatedDate", EmailRuntime.clock().instant()), tx);
         QRecord change = new QRecord().withValue("id", template.getValue("id")).withValue("activeVersionId", versionId);
         if(!Objects.equals(template.getValue("approvedVersionId"), versionId))
         {
            change.withValue("phiApproved", false).withValue("approvedVersionId", null).withValue("phiApprovedBy", null).withValue("phiApprovedDate", null);
         }
         EmailStore.update(EmailTables.TEMPLATE, change, tx);
         tx.commit();
      }
   }

   /*******************************************************************************
    ** Review validation is side-effect-free; compiled output stays in memory.
    *******************************************************************************/
   public static void validate(QRecord template, QRecord version) throws QException
   {
      requireEdit(template, false);
      if("MJML".equals(template.getValueString("kind")))
      {
         CliMjmlCompiler.rejectIncludes(version.getValueString("bodySource"));
         EmailTemplateEngine.validateHtml(version.getValueString("bodySource"));
         if(EmailRuntime.config().getMjmlCompiler() == null)
         {
            throw (new IllegalArgumentException("MJML activation needs a configured compiler"));
         }
         new EmailTemplateEngine().parse(version.getValueString("bodySource"), new VariableSchema(version.getValueString("variablesSchema")));
         version.setValue("compiledHtml", EmailRuntime.config().getMjmlCompiler().compile(version.getValueString("bodySource")));
      }
      EmailTemplateRenderer renderer = new EmailTemplateRenderer(template, version,
         new EmailRequest().withVariables(VariableSchema.values(version.getValueString("sampleVariables"))));
      renderer.render("preview", "https://example.invalid/unsubscribe/preview", false);
   }

   /*******************************************************************************
    ** Load a required version without exposing database diagnostics.
    *******************************************************************************/
   public static QRecord requiredVersion(Integer id, QBackendTransaction tx) throws QException
   {
      QRecord version = EmailStore.get(EmailTables.VERSION, id, tx);
      if(version == null)
      {
         throw (new IllegalArgumentException("Template version not found"));
      }
      return (version);
   }

   /*******************************************************************************
    ** Stable session actor identity, with an explicit system fallback.
    *******************************************************************************/
   private static String actor()
   {
      return (QContext.getQSession() == null || QContext.getQSession().getUser() == null ? "system" : QContext.getQSession().getUser().getIdReference());
   }
}
