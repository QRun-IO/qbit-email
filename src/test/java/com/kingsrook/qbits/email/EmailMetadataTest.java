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

import java.util.*;
import com.kingsrook.qbits.email.app.EmailApp;
import com.kingsrook.qbits.email.metadata.*;
import com.kingsrook.qbits.email.outbox.*;
import com.kingsrook.qbits.email.permissions.*;
import com.kingsrook.qbits.email.processes.*;
import com.kingsrook.qbits.email.support.*;
import com.kingsrook.qqq.backend.core.actions.tables.*;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.*;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/*******************************************************************************
 ** Email Metadata Test.
 *******************************************************************************/
class EmailMetadataTest
{
   /*******************************************************************************
    ** Setup.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c ->
      {
      });
   }

   /*******************************************************************************
    ** Teardown.
    *******************************************************************************/
   @AfterEach
   void teardown()
   {
      QContext.clear();
   }

   /*******************************************************************************
    ** Producer Standards And Integration Fields.
    *******************************************************************************/
   @Test
   void producerStandardsAndIntegrationFields() throws Exception
   {
      var instance = QContext.getQInstance();
      assertThat(instance.getTables()).hasSize(10);
      for(var table : instance.getTables().values())
      {
         assertThat(table.getName()).matches("email_[a-z_]+");
         assertThat(table.getPermissionRules().getPermissionBaseName()).isEqualTo("email");
         assertThat(table.getBackendName()).isEqualTo("rdbms");
         assertThat(table.getIsHidden()).isFalse();
         for(var field : table.getFields().values())
         {
            assertThat(field.getBackendName()).matches("[a-z][a-z0-9_]*");
         }
      }
      var children = instance.getApp("email").getChildren();
      assertThat(children).extracting(c -> c.getName()).containsExactlyElementsOf(EmailTables.MAIN);
      assertThat(EmailTables.SYSTEM_WRITTEN)
            .allSatisfy(
                  t ->
                        assertThat(instance.getTable(t).getDisabledCapabilities())
                              .contains(
                                    Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE));
      assertThat(instance.getMessagingProvider("qbitEmail").getType()).isEqualTo("QBIT_EMAIL");
      assertThat(EmailIntegrations.esbTableNames())
            .containsExactly(EmailTables.MESSAGE, EmailTables.TEMPLATE, EmailTables.SUPPRESSION);
      assertThat(EmailIntegrations.apiTableNames()).isEqualTo(EmailIntegrations.esbTableNames());
      assertThat(EmailIntegrations.quickSearchTables())
            .allSatisfy(
                  t ->
                        assertThat(t.fieldWeights())
                              .doesNotContainKeys("subject", "htmlBody", "textBody", "variablesSnapshot"));
      assertThat(
                  new EmailApp.HomeRenderer()
                        .render(
                              new com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput().withWidgetMetaData(instance.getWidget(EmailApp.HOME_WIDGET))))
            .isNotNull();
      assertThat(EmailRuntime.isInstalled()).isTrue();
      assertThat(EmailRuntime.config(new QInstance())).isNull();
      assertThat(EmailRuntime.config(null)).isNull();
   }

   /*******************************************************************************
    ** Provider.
    *******************************************************************************/
   QRecord provider(String name)
   {
      return (new QRecord()
            .withValue("name", name)
            .withValue("kind", "SMTP")
            .withValue("host", "localhost")
            .withValue("port", 25)
            .withValue("fromAddress", "sender@example.com")
            .withValue("enabled", true)
            .withValue("isDefault", true));
   }

   /*******************************************************************************
    ** Insert.
    *******************************************************************************/
   QRecord insert(QRecord row) throws Exception
   {
      return (new InsertAction()
            .execute(new InsertInput(EmailTables.PROVIDER).withRecords(List.of(row)))
            .getRecords()
            .get(0));
   }

   /*******************************************************************************
    ** Validates Env Name Allowlist And Never Returns Values.
    *******************************************************************************/
   @Test
   void validatesEnvNameAllowlistAndNeverReturnsValues() throws Exception
   {
      EmailRuntime.config().withSecretResolver(n -> "top-secret-do-not-expose");
      assertThat(insert(provider("bad").withValue("secretEnvName", "literal-password")).getErrors())
            .isNotEmpty();
      assertThat(insert(provider("bad2").withValue("secretEnvName", "OTHER_SECRET")).getErrors())
            .isNotEmpty();
      assertThat(
                  insert(provider("domain").withValue("sendingDomain", "different.example")).getErrors())
            .isNotEmpty();
      assertThat(insert(provider("port").withValue("port", 0)).getErrors()).isNotEmpty();
      assertThat(insert(provider("no-default").withValue("isDefault", false)).getErrors())
            .isNotEmpty();
      var good = insert(provider("relay").withValue("secretEnvName", "EMAIL_SMTP_PASSWORD"));
      assertThat(good.getErrors()).isNullOrEmpty();
      var queried =
            new QueryAction().execute(new QueryInput(EmailTables.PROVIDER)).getRecords().getFirst();
      assertThat(queried.getValueString("secretEnvNameStatus")).isEqualTo("Set");
      assertThat(queried.getValueString("usernameEnvNameStatus")).isEqualTo("Not set");
      assertThat(queried.getValues().values().toString()).doesNotContain("top-secret");
      EmailRuntime.config()
            .withSecretResolver(
                  n ->
                  {
                     throw new RuntimeException("secret in exception");
                  });
      queried =
            new QueryAction().execute(new QueryInput(EmailTables.PROVIDER)).getRecords().getFirst();
      assertThat(queried.getValueString("secretEnvNameStatus")).isEqualTo("Not set");
      assertThat(insert(provider("duplicate-default")).getErrors()).isNotEmpty();
      assertThat(insert(provider("secondary").withValue("isDefault", false)).getErrors())
            .isNullOrEmpty();
      var updated =
            new UpdateAction()
                  .execute(
                        new UpdateInput(EmailTables.PROVIDER)
                              .withRecords(
                                    List.of(
                                          new QRecord()
                                                .withValue("id", good.getValue("id"))
                                                .withValue("label", "New label"))));
      assertThat(updated.getRecords().getFirst().getErrors()).isNullOrEmpty();
      var invalid =
            new UpdateAction()
                  .execute(
                        new UpdateInput(EmailTables.PROVIDER)
                              .withRecords(
                                    List.of(
                                          new QRecord()
                                                .withValue("id", good.getValue("id"))
                                                .withValue("enabled", false))));
      assertThat(invalid.getRecords().getFirst().getErrors()).isNotEmpty();
      var delete =
            new DeleteAction()
                  .execute(
                        new DeleteInput(EmailTables.PROVIDER)
                              .withQueryFilter(EmailStore.equal("id", good.getValueInteger("id"))));
      assertThat(H2Support.all(EmailTables.PROVIDER)).hasSize(2);
   }

   /*******************************************************************************
    ** Service Refuses Invalid Inputs And Rendering Escapes Raw Html.
    *******************************************************************************/
   @Test
   void serviceRefusesInvalidInputsAndRenderingEscapesRawHtml() throws Exception
   {
      H2Support.provider(25);
      var good =
            new EmailRequest()
                  .withTo("a@example.com")
                  .withSubject("s")
                  .withMarkdownBody("<script>alert(1)</script>\n\n**Hello**");
      assertThat(EmailService.send(new EmailRequest().withTemplateName("later")).reasons())
            .contains("Unknown email template");
      assertThat(EmailService.send(null).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(EmailService.send(new EmailRequest()).status())
            .isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(EmailService.send(new EmailRequest().withSubject("s").withTextBody("body")).status())
            .isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(
                  EmailService.send(
                              new EmailRequest().withTo("not-address").withSubject("s").withTextBody("body"))
                        .status())
            .isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(
                  EmailService.send(
                              new EmailRequest()
                                    .withTo("a@example.com\r\nBcc:x@example.com")
                                    .withSubject("s")
                                    .withTextBody("body"))
                        .status())
            .isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(
                  EmailService.send(
                              new EmailRequest()
                                    .withTo("a@example.com")
                                    .withSubject("s")
                                    .withTextBody("body")
                                    .withVariable("phi", "no"))
                        .status())
            .isEqualTo(EmailSendResult.Status.REFUSED);
      var rendered = new AdHocEmailRenderer().render(good);
      assertThat(rendered.html()).contains("&lt;script&gt;").doesNotContain("<script>");
      assertThat(new AdHocEmailRenderer().render(new EmailRequest().withTextBody("<safe>")).html())
            .contains("&lt;safe&gt;");
      assertThat(
                  new AdHocEmailRenderer()
                        .render(new EmailRequest().withHtmlBody("<p>Hello &amp; bye</p>"))
                        .text())
            .isEqualTo("Hello & bye");
      assertThat(EmailService.send(good.withProviderName("missing")).status())
            .isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }

   /*******************************************************************************
    ** Permission Checker Protects System Tables Providers And Actions.
    *******************************************************************************/
   @Test
   void permissionCheckerProtectsSystemTablesProvidersAndActions() throws Exception
   {
      var checker = new EmailPermissionChecker();
      QContext.getQSession().setPermissions(Set.of("email.view"));
      var instance = QContext.getQInstance();
      checker.checkPermissionsThrowing(
            new QueryInput(EmailTables.MESSAGE), instance.getTable(EmailTables.MESSAGE));
      assertThatThrownBy(
                  () ->
                        checker.checkPermissionsThrowing(
                              new InsertInput(EmailTables.MESSAGE), instance.getTable(EmailTables.MESSAGE)))
            .hasMessageContaining("only");
      assertThatThrownBy(
                  () ->
                        checker.checkPermissionsThrowing(
                              new QueryInput(EmailTables.PROVIDER), instance.getTable(EmailTables.PROVIDER)))
            .hasMessageContaining("email.admin");
      assertThatThrownBy(
                  () ->
                        checker.checkPermissionsThrowing(
                              new com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput(),
                              instance.getProcess(EmailProcesses.SEND_TEMPLATED)))
            .hasMessageContaining("email.send");
      QContext.getQSession().setPermissions(Set.of("email.send"));
      assertThat(EmailPermissionChecker.has("email.send")).isTrue();
      assertThat(EmailPermissionChecker.has("email.view")).isFalse();
      instance.setEnforcePermissionsInCoreActions(true);
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(EmailTables.MESSAGE)))
         .hasMessageContaining("email.view");
      checker.checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput(),
         instance.getProcess(EmailProcesses.SEND_TEMPLATED));
      assertThat(EmailPermissionChecker.has("email.admin")).isFalse();
      QContext.getQSession().setPermissions(Set.of("email.manage"));
      assertThat(EmailPermissionChecker.has("email.send")).isTrue();
      assertThat(EmailPermissionChecker.has("email.view")).isTrue();
      assertThat(EmailProcesses.permissionFor(EmailProcesses.SEND_TEST)).isEqualTo("email.admin");
   }

   /*******************************************************************************
    ** Credentialed SMTP needs encryption unless loopback or explicitly allowed.
    *******************************************************************************/
   @Test
   void plaintextSmtpAuthenticationValidation()
   {
      var config = EmailRuntime.config();
      assertThat(config.getAllowPlaintextSmtpAuth()).isFalse();
      for(String kind : List.of("SMTP"))
      {
         for(String credential : List.of("usernameEnvName", "secretEnvName"))
         {
            var row = provider("auth").withValue("kind", kind).withValue("host", "relay.example.com")
               .withValue("security", "NONE").withValue(credential, "EMAIL_SMTP_USERNAME");
            assertThat(EmailTableCustomizers.validateProvider(row, config)).contains("SMTP sign-in needs STARTTLS or TLS");
            for(String security : List.of("STARTTLS", "TLS"))
            {
               assertThat(EmailTableCustomizers.validateProvider(row.withValue("security", security), config)).isEmpty();
            }
            row.withValue("security", "NONE");
            assertThat(EmailTableCustomizers.validateProvider(row, config.withAllowPlaintextSmtpAuth(true))).isEmpty();
            config.withAllowPlaintextSmtpAuth(false);
         }
      }
      for(String host : List.of("localhost", "LOCALHOST", "127.0.0.1", "::1"))
      {
         assertThat(EmailTableCustomizers.validateProvider(provider("local").withValue("host", host)
            .withValue("security", "NONE").withValue("usernameEnvName", "EMAIL_SMTP_USERNAME"), config)).isEmpty();
      }
      assertThat(EmailTableCustomizers.validateProvider(provider("relay").withValue("host", "relay.example.com")
         .withValue("security", "NONE"), config)).isEmpty();
   }

   /*******************************************************************************
    ** Sender headers cannot inject new headers, including sparse UI updates.
    *******************************************************************************/
   @Test
   void providerHeadersRefuseLineBreaks() throws Exception
   {
      var stored = insert(provider("headers"));
      for(String field : List.of("fromName", "fromAddress", "replyTo"))
      {
         for(String newline : List.of("\r", "\n"))
         {
            var row = provider("bad").withValue(field, "sender@example.com" + newline + "Bcc: hidden@example.com");
            assertThat(EmailTableCustomizers.validateProvider(row, EmailRuntime.config())).isNotEmpty();
            var updated = new UpdateAction().execute(new UpdateInput(EmailTables.PROVIDER)
               .withRecords(List.of(new QRecord().withValue("id", stored.getValue("id")).withValue(field, row.getValue(field)))));
            assertThat(updated.getRecords().getFirst().getErrors()).isNotEmpty();
         }
      }
   }

   /*******************************************************************************
    ** Complaint Suppression Is Admin Protected On Update And Delete.
    *******************************************************************************/
   @Test
   void complaintSuppressionIsAdminProtectedOnUpdateAndDelete() throws Exception
   {
      var row =
            H2Support.insert(
                  EmailTables.SUPPRESSION,
                  new QRecord()
                        .withValue("address", "a@example.com")
                        .withValue("scope", "ALL")
                        .withValue("reason", "COMPLAINT"));
      QContext.getQSession().setPermissions(Set.of("email.manage", "email.hasAccess"));
      var update =
            new UpdateAction()
                  .execute(
                        new UpdateInput(EmailTables.SUPPRESSION)
                              .withRecords(
                                    List.of(
                                          new QRecord()
                                                .withValue("id", row.getValue("id"))
                                                .withValue("reason", "MANUAL"))));
      assertThat(update.getRecords().getFirst().getErrors()).isNotEmpty();
      new DeleteAction()
            .execute(new DeleteInput(EmailTables.SUPPRESSION).withQueryFilter(new QQueryFilter()));
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
   }

   /*******************************************************************************
    ** Configuration Validation And Customizers.
    *******************************************************************************/
   @Test
   void configurationValidationAndCustomizers() throws Exception
   {
      var config =
            new EmailQBitConfig()
                  .withBackendName("rdbms")
                  .withSchedulerName("missing")
                  .withMaxAttempts(0)
                  .withBatchSize(0)
                  .withRetryMinutes(List.of())
                  .withLeaseSeconds(1)
                  .withBodyRetentionDays(-1)
                  .withAllowedEnvNames(Set.of("bad-name"))
                  .withAppName("");
      List<String> errors = new ArrayList<>();
      config.validate(QContext.getQInstance(), errors);
      assertThat(errors).hasSizeGreaterThanOrEqualTo(6);
      assertThat(new EmailQBitConfig().retryMinutesAfterAttempt(20)).isEqualTo(720);
      for(String kind :
            List.of(
                  "SMTP",
                  "SES",
                  "GMAIL_SMTP",
                  "POSTMARK",
                  "SENDGRID",
                  "MAILGUN",
                  "GMAIL_API",
                  "future"))
                  {
         assertThat(
                     new EmailQBitConfig()
                           .withAllowedEnvNames(Set.of("CUSTOM"))
                           .effectiveAllowedEnvNames(kind))
               .contains("CUSTOM");
      }
      H2Support.setup(
            c ->
                  c.withTableMetaDataCustomizer(
                              (instance, table) -> table.withLabel("Custom " + table.getLabel()))
                        .withSecurityFields(List.of(new QFieldMetaData("tenantId", QFieldType.INTEGER))));
      assertThat(
                  QContext.getQInstance()
                        .getTable(EmailTables.MESSAGE)
                        .getField("tenantId")
                        .getBackendName())
            .isEqualTo("tenant_id");
   }

   /*******************************************************************************
    ** Core Enforcement Rejects Direct Writes But Permits Authorized Enqueue.
    *******************************************************************************/
   @Test
   void coreEnforcementRejectsDirectWritesButPermitsAuthorizedEnqueue() throws Exception
   {
      H2Support.provider(25);
      QContext.getQInstance().setEnforcePermissionsInCoreActions(true);
      QContext.getQSession().setPermissions(Set.of("email.view", "email.hasAccess"));
      var request = new EmailRequest().withTo("a@example.com").withSubject("s").withTextBody("body");
      assertThat(EmailService.send(request).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThatThrownBy(
                  () ->
                        new InsertAction()
                              .execute(
                                    new InsertInput(EmailTables.MESSAGE).withRecords(List.of(new QRecord()))))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(EmailTables.PROVIDER)))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      assertThat(SystemWrites.call(() -> EmailService.send(request)).status())
            .isEqualTo(EmailSendResult.Status.QUEUED);
      QContext.getQSession().setPermissions(Set.of("email.send", "email.hasAccess"));
      assertThat(EmailService.send(request).status()).isEqualTo(EmailSendResult.Status.QUEUED);
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(EmailTables.MESSAGE)))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      assertThat(SystemWrites.active()).isFalse();
   }

   /*******************************************************************************
    ** Bridge Refuses Attachments And Invalid Requests.
    *******************************************************************************/
   @Test
   void bridgeRefusesAttachmentsAndInvalidRequests() throws Exception
   {
      H2Support.provider(25);
      var bridge = new com.kingsrook.qbits.email.bridge.EmailOutboxMessagingProvider();
      assertThatThrownBy(
                  () ->
                        bridge.sendMessage(
                              new com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput()
                                    .withAttachment(
                                          new com.kingsrook.qqq.backend.core.model.actions.messaging
                                                .Attachment())))
            .hasMessageContaining("attachments");
      assertThatThrownBy(
                  () ->
                        bridge.sendMessage(
                              new com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput()))
            .hasMessageContaining("subject");
   }

   /*******************************************************************************
    ** Scheduled metadata uses the configured scheduler and exact intervals.
    *******************************************************************************/
   @Test
   void scheduledMetadataUsesConfiguredIntervals() throws Exception
   {
      QContext.getQInstance().addScheduler(new com.kingsrook.qqq.backend.core.model.metadata.scheduleing.simple.SimpleSchedulerMetaData().withName("scheduler"));
      EmailQBitConfig config = new EmailQBitConfig().withBackendName("rdbms").withSchedulerName("scheduler");
      List<String> errors = new ArrayList<>();
      config.validate(QContext.getQInstance(), errors);
      assertThat(errors).isEmpty();
      var scheduled = EmailProcesses.processes(config).stream().filter(p -> p.getSchedule() != null).toList();
      assertThat(scheduled).hasSize(4).allMatch(p -> p.getIsHidden());
      assertThat(scheduled).extracting(p -> p.getSchedule().getRepeatSeconds()).containsExactly(30, 15, 300, 86400);
   }

}
