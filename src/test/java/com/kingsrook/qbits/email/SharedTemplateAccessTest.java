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


import java.util.Set;
import com.kingsrook.qbits.email.api.EmailApi;
import com.kingsrook.qbits.email.api.EmailApiCategory;
import com.kingsrook.qbits.email.api.EmailApiRequest;
import com.kingsrook.qbits.email.api.EmailApiTemplate;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailVersions;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Host template grants never imply email administration or bypass record locks.
 *******************************************************************************/
class SharedTemplateAccessTest
{
   /*******************************************************************************
    ** Use real QQQ permission and database actions with explicit host opt-in.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(config -> config.withTemplatePermissionBaseName("crm.manage"));
   }

   /*******************************************************************************
    ** Clear request-local state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }

   /*******************************************************************************
    ** Template grants do not expose providers, messages or PHI approval.
    *******************************************************************************/
   @Test
   void narrowPermissionsAndAdditionalEditGrant() throws Exception
   {
      EmailApi.ensureTemplate(new EmailApiTemplate().withName("crm_legacy_1").withLabel("Legacy")
         .withKind("CRM_LEGACY").withOwnerQbit("crm").withEditPermissionName("crm.manage.write")
         .withCategory(EmailApiCategory.TRANSACTIONAL).withSubject("Hello {{contact.firstName}}").withBody("body"));
      var template = SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", "crm_legacy_1")).getFirst();
      Integer id = template.getValueInteger("id");
      QContext.getQSession().setPermissions(Set.of("crm.manage.read"));
      var request = new EmailApiRequest().withCategory(EmailApiCategory.MARKETING).withVariable("contact.firstName", "Ada");
      assertThat(EmailApi.renderTemplate(id, request).subject()).isEqualTo("Hello Ada");
      assertThatThrownBy(() -> EmailVersions.requireEdit(template, false)).hasMessageContaining("email.manage");
      EmailPermissionChecker checker = new EmailPermissionChecker();
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new QueryInput(EmailTables.PROVIDER), QContext.getQInstance().getTable(EmailTables.PROVIDER))).hasMessageContaining("email.admin");
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new QueryInput(EmailTables.MESSAGE), QContext.getQInstance().getTable(EmailTables.MESSAGE))).hasMessageContaining("email.view");
      QContext.getQSession().setPermissions(Set.of("crm.manage.read", "crm.manage.write"));
      EmailVersions.requireEdit(template, false);
      checker.checkPermissionsThrowing(new UpdateInput(EmailTables.TEMPLATE), QContext.getQInstance().getTable(EmailTables.TEMPLATE));
      assertThatThrownBy(() -> EmailVersions.requireEdit(template, true)).hasMessageContaining("email.admin");
      template.setValue("editPermissionName", "another.owner.write");
      assertThatThrownBy(() -> EmailVersions.requireEdit(template, false)).hasMessageContaining("another.owner.write");
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new UpdateInput(EmailTables.BRAND), QContext.getQInstance().getTable(EmailTables.BRAND))).hasMessageContaining("email.manage");
      assertThat(EmailProcesses.permissionFor(EmailProcesses.APPROVE_PHI)).isEqualTo("email.admin");
      QContext.getQSession().setPermissions(Set.of());
      assertThatThrownBy(() -> EmailApi.renderTemplate(id, request)).hasMessageContaining("email.view");
   }

   /*******************************************************************************
    ** A permitted table read still cannot render another tenant's template.
    *******************************************************************************/
   @Test
   void renderHonorsRecordLocks() throws Exception
   {
      EmailApi.ensureTemplate(new EmailApiTemplate().withName("crm_locked").withLabel("Locked")
         .withKind("CRM_LEGACY").withOwnerQbit("crm").withCategory(EmailApiCategory.TRANSACTIONAL)
         .withSubject("subject").withBody("body"));
      var template = SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", "crm_locked")).getFirst();
      QContext.getQInstance().addSecurityKeyType(new QSecurityKeyType().withName("templateAccess"));
      QContext.getQInstance().getTable(EmailTables.TEMPLATE).withRecordSecurityLock(new RecordSecurityLock().withFieldName("id").withSecurityKeyType("templateAccess"));
      QContext.getQSession().setPermissions(Set.of("crm.manage.read"));
      assertThatThrownBy(() -> EmailApi.renderTemplate(template.getValueInteger("id"), new EmailApiRequest().withCategory(EmailApiCategory.TRANSACTIONAL)))
         .hasMessageContaining("unavailable");
   }

   /*******************************************************************************
    ** Native templates retain their category and typed HTML; approval is not exportable.
    *******************************************************************************/
   @Test
   void nativePolicyAndTypedContent() throws Exception
   {
      EmailApi.ensureTemplate(new EmailApiTemplate().withName("native_html").withLabel("Native")
         .withKind("HTML").withCategory(EmailApiCategory.TRANSACTIONAL).withSubject("subject").withBody("<p>body</p>"));
      var template = SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", "native_html")).getFirst();
      Integer id = template.getValueInteger("id");
      QContext.getQSession().setPermissions(Set.of("crm.manage.read"));
      var rendered = EmailApi.renderTemplate(id, new EmailApiRequest().withCategory(EmailApiCategory.TRANSACTIONAL));
      assertThat(rendered.htmlBody()).contains("<p>body</p>");
      assertThat(rendered.markdownBody()).isNull();
      assertThatThrownBy(() -> EmailApi.renderTemplate(id, new EmailApiRequest().withCategory(EmailApiCategory.MARKETING))).hasMessageContaining("category cannot be overridden");
      assertThatThrownBy(() -> EmailApi.renderTemplate(id, new EmailApiRequest().withCategory(EmailApiCategory.TRANSACTIONAL).withSubject("override"))).hasMessageContaining("cannot be overridden");
      EmailStore.update(EmailTables.TEMPLATE, new com.kingsrook.qqq.backend.core.model.data.QRecord().withValue("id", id).withValue("phiApproved", true), null);
      assertThatThrownBy(() -> EmailApi.renderTemplate(id, new EmailApiRequest().withCategory(EmailApiCategory.TRANSACTIONAL))).hasMessageContaining("protected template send path");
   }

   /*******************************************************************************
    ** Compatibility content still uses the real outbox and stable template provenance.
    *******************************************************************************/
   @Test
   void legacyOutboxCategoriesAndIdempotency() throws Exception
   {
      H2Support.provider(25);
      EmailRuntime.config().withPublicBaseUrl("https://app.example.test").withSecretResolver(name -> "synthetic-unsubscribe-key");
      H2Support.insert(EmailTables.BRAND, new com.kingsrook.qqq.backend.core.model.data.QRecord()
         .withValue("name", "postal").withValue("isDefault", true).withValue("postalAddress", "123 Test Street"));
      EmailApi.ensureTemplate(new EmailApiTemplate().withName("crm_delivery").withLabel("Delivery")
         .withKind("CRM_LEGACY").withOwnerQbit("crm").withCategory(EmailApiCategory.TRANSACTIONAL)
         .withSubject("Hi {{contact.firstName}}").withBody("<b>fallback</b>").withTextBody("Hello {{contact.firstName}}"));
      for(EmailApiCategory category : java.util.List.of(EmailApiCategory.TRANSACTIONAL, EmailApiCategory.MARKETING))
      {
         String key = "crm:test:" + category;
         var request = new EmailApiRequest().withTo("recipient@example.test").withTemplateName("crm_delivery")
            .withCategory(category).withVariable("contact.firstName", "{{unknown}} ").withIdempotencyKey(key);
         var first = EmailApi.sendOnce(request, key);
         if(first.outcome() == com.kingsrook.qbits.email.api.EmailApiEffectOutcome.REFUSED)
         {
            throw (new AssertionError(EmailApi.send(request).toString()));
         }
         assertThat(EmailApi.sendOnce(request, key).message().id()).isEqualTo(first.message().id());
         var row = SystemWrites.query(EmailTables.MESSAGE, EmailStore.equal("id", first.message().id())).getFirst();
         assertThat(row.getValueString("category")).isEqualTo(category.name());
         assertThat(row.getValueString("subject")).isEqualTo("Hi {{unknown}} ");
         assertThat(row.getValue("templateId")).isNotNull();
         assertThat(row.getValue("templateVersionId")).isNotNull();
         assertThat(row.getValueString("variablesSnapshot")).isEqualTo("{}");
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(2);
   }

   /*******************************************************************************
    ** Final subjects use the actual message ID; replay never exports another envelope.
    *******************************************************************************/
   @Test
   void checkedSubjectAndReplayBinding() throws Exception
   {
      H2Support.provider(25);
      EmailApi.ensureTemplate(new EmailApiTemplate().withName("native_history").withLabel("History")
         .withKind("HTML").withCategory(EmailApiCategory.TRANSACTIONAL).withSubject("Message {{message.id}}").withBody("<p>body</p>"));
      Integer id = SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", "native_history")).getFirst().getValueInteger("id");
      var request = new EmailApiRequest().withTo("recipient@example.test").withCategory(EmailApiCategory.TRANSACTIONAL)
         .withSender("crm", "manual").withLinkedRecord("crm_contact", 1);
      request.withTo(java.util.List.of("one@example.test,two@example.test"));
      assertThatThrownBy(() -> EmailApi.sendTemplateOnce(id, request, "crm:invalid")).hasMessageContaining("exactly one mailbox");
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
      request.withTo(java.util.List.of("  recipient@example.test  "));
      var first = EmailApi.sendTemplateOnce(id, request, "crm:checked");
      assertThat(first.subject()).isEqualTo("Message " + first.effect().message().id());
      assertThat(EmailApi.sendTemplateOnce(id, request, "crm:checked").subject()).isEqualTo(first.subject());
      request.withTo(java.util.List.of("other@example.test"));
      assertThatThrownBy(() -> EmailApi.sendTemplateOnce(id, request, "crm:checked")).hasMessageContaining("envelope does not match");
      request.withTo(java.util.List.of("recipient@example.test")).withLinkedRecord("crm_contact", 2);
      assertThatThrownBy(() -> EmailApi.sendTemplateOnce(id, request, "crm:checked")).hasMessageContaining("envelope does not match");
      request.withLinkedRecord("crm_contact", 1).withSender("other", "manual");
      assertThatThrownBy(() -> EmailApi.sendTemplateOnce(id, request, "crm:checked")).hasMessageContaining("envelope does not match");
   }

}
