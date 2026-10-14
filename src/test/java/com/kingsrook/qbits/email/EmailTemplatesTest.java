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

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.kingsrook.qbits.email.app.EmailHome;
import com.kingsrook.qbits.email.delivery.EmailDispatcher;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailVersions;
import com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Version lifecycle, permissions, metadata filters, previews and real delivery.
 *******************************************************************************/
class EmailTemplatesTest
{
   /*******************************************************************************
    ** Isolated database and sender session.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c -> c.withPublicBaseUrl("https://app.example.com"));
      H2Support.provider(25);
   }

   /*******************************************************************************
    ** Clear thread and clock state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** Reusable real template/version fixture.
    *******************************************************************************/
   static QRecord draft(String name, String kind, String schema, String samples, String body) throws Exception
   {
      QRecord template = H2Support.insert(EmailTables.TEMPLATE, new QRecord().withValue("name", name).withValue("kind", kind).withValue("category", "TRANSACTIONAL"));
      QRecord version = EmailVersions.newVersion(template.getValueInteger("id"));
      QRecord update = new QRecord().withValue("id", version.getValue("id")).withValue("subject", "Hello {{brand.label}} {{message.id}}")
         .withValue("bodySource", body).withValue("variablesSchema", schema).withValue("sampleVariables", samples);
      var output = new UpdateAction().execute(new UpdateInput(EmailTables.VERSION).withRecords(List.of(update)));
      assertThat(output.getRecords().getFirst().getErrors()).isNullOrEmpty();
      return (EmailStore.get(EmailTables.VERSION, version.getValueInteger("id"), null));
   }

   /*******************************************************************************
    ** Review/Result creates drafts and atomically retires the old active locale.
    *******************************************************************************/
   @Test
   void lifecycleActionsAndImmutability() throws Exception
   {
      QRecord first = draft("welcome", "MARKDOWN", "[]", "{}", "Hello");
      assertThat(ProcessRunner.run(EmailProcesses.ACTIVATE_VERSION, first.getValue("id")).result().toString()).contains("ACTIVE");
      var attempted = new UpdateAction().execute(new UpdateInput(EmailTables.VERSION).withRecords(List.of(new QRecord().withValue("id", first.getValue("id")).withValue("bodySource", "changed"))));
      assertThat(attempted.getRecords().getFirst().getErrors()).isNotEmpty();
      assertThat(ProcessRunner.run(EmailProcesses.NEW_VERSION, first.getValue("templateId")).result().toString()).contains("DRAFT 2");
      QRecord next = H2Support.all(EmailTables.VERSION).stream().filter(v -> v.getValueInteger("versionNumber") == 2).findFirst().orElseThrow();
      assertThat(next.getValueString("bodySource")).isEqualTo("Hello");
      EmailVersions.activate(next.getValueInteger("id"));
      assertThat(EmailStore.get(EmailTables.VERSION, first.getValueInteger("id"), null).getValueString("status")).isEqualTo("RETIRED");
      assertThat(EmailStore.get(EmailTables.TEMPLATE, first.getValueInteger("templateId"), null).getValue("activeVersionId")).isEqualTo(next.getValue("id"));
      assertThatThrownBy(() -> EmailVersions.activate(first.getValueInteger("id"))).hasMessageContaining("DRAFT");
      attempted = new UpdateAction().execute(new UpdateInput(EmailTables.VERSION).withRecords(List.of(new QRecord().withValue("id", first.getValue("id")).withValue("status", "DRAFT"))));
      assertThat(attempted.getRecords().getFirst().getErrors()).isNotEmpty();
   }

   /*******************************************************************************
    ** New PHI versions need their own approval and drafts freeze on approval.
    *******************************************************************************/
   @Test
   void phiApprovalAndEditPermission() throws Exception
   {
      QRecord version = draft("private_notice", "HTML", "[{\"name\":\"patient\",\"type\":\"STRING\",\"classification\":\"PHI\"}]", "{\"patient\":\"sample\"}", "<p>{{patient}}</p>");
      assertThatThrownBy(() -> EmailVersions.activate(version.getValueInteger("id"))).hasMessageContaining("patient is PHI");
      QContext.getQSession().setPermissions(Set.of("email.manage"));
      assertThatThrownBy(() -> EmailVersions.approve(version.getValueInteger("id"))).hasMessageContaining("email.admin");
      QContext.getQSession().setPermissions(Set.of("email.admin"));
      assertThat(ProcessRunner.run(EmailProcesses.APPROVE_PHI, version.getValue("id")).result().toString()).contains("Approved");
      var edit = new UpdateAction().execute(new UpdateInput(EmailTables.VERSION).withRecords(List.of(new QRecord().withValue("id", version.getValue("id")).withValue("bodySource", "replaced"))));
      assertThat(edit.getRecords().getFirst().getErrors()).isNotEmpty();
      EmailVersions.activate(version.getValueInteger("id"));
      QRecord next = EmailVersions.newVersion(version.getValueInteger("templateId"));
      assertThatThrownBy(() -> EmailVersions.activate(next.getValueInteger("id"))).hasMessageContaining("not approved");
      SystemWrites.updateOne(EmailTables.TEMPLATE, new QRecord().withValue("id", version.getValue("templateId")).withValue("editPermissionName", "clinical.edit"));
      assertThatThrownBy(() -> EmailVersions.newVersion(version.getValueInteger("templateId"))).hasMessageContaining("clinical.edit");
      QContext.getQSession().setPermissions(Set.of("email.admin", "clinical.edit"));
      assertThat(EmailVersions.newVersion(version.getValueInteger("templateId")).getValueInteger("versionNumber")).isEqualTo(3);
   }

   /*******************************************************************************
    ** MJML requires activation compilation, and sending never runs it again.
    *******************************************************************************/
   @Test
   void mjmlCompilerAndLocaleFallback() throws Exception
   {
      QRecord version = draft("mjml", "MJML", "[]", "{}", "<mjml><mj-body>{{brand.label}}</mj-body></mjml>");
      assertThatThrownBy(() -> EmailVersions.activate(version.getValueInteger("id"))).hasMessageContaining("configured compiler");
      EmailRuntime.config().withMjmlCompiler(source -> "<html><body><p>{{brand.label}}</p></body></html>");
      var preview = new RenderWidgetAction().execute(new RenderWidgetInput().withWidgetMetaData(QContext.getQInstance().getWidget("emailTemplatePreview"))
         .withUrlParams(Map.of("id", version.getValueString("id"))));
      assertThat(((RawHTML) preview.getWidgetData()).getHtml()).contains("<p>Email</p>");
      assertThat(EmailStore.get(EmailTables.VERSION, version.getValueInteger("id"), null).getValue("compiledHtml")).isNull();
      EmailVersions.activate(version.getValueInteger("id"));
      EmailRuntime.config().withMjmlCompiler(source ->
      {
         throw new AssertionError("Compiler ran at send time");
      });
      var result = EmailService.send(new EmailRequest().withTemplateName("mjml").withLocale("fr").withTo("p@example.com"));
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord row = EmailStore.get(EmailTables.MESSAGE, result.messageId(), null);
      assertThat(row.getValueString("locale")).isEqualTo("en");
      assertThat(row.getValueString("htmlBody")).contains("<p>Email</p>");
      assertThat(row.getValueString("subject")).endsWith(result.messageId().toString());
   }

   /*******************************************************************************
    ** Template categories and retained resend links enforce the same mailbox boundary.
    *******************************************************************************/
   @Test
   void unsubscribeTemplateCategoryAndResend() throws Exception
   {
      EmailRuntime.config().withSecretResolver(name -> "test-unsubscribe-key");
      QRecord version = draft("notice", "MARKDOWN", "[]", "{}", "A notification");
      SystemWrites.updateOne(EmailTables.TEMPLATE, new QRecord().withValue("id", version.getValue("templateId")).withValue("category", "NOTIFICATION"));
      EmailVersions.activate(version.getValueInteger("id"));
      var refused = EmailService.send(new EmailRequest().withTemplateName("notice").withTo("person@example.com").withBcc("other@example.com"));
      assertThat(refused.status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(refused.reasons()).containsExactly("Notification and marketing email with an unsubscribe link goes to one recipient per message; send one message per recipient.");
      var queued = EmailService.send(new EmailRequest().withTemplateName("notice").withTo("\"Person, One\" <PERSON@example.com>"));
      assertThat(queued.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      var resent = EmailService.resend(queued.messageId());
      assertThat(resent.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord message = EmailStore.get(EmailTables.MESSAGE, queued.messageId(), null);
      String url = message.getValueString("unsubscribeUrl");
      assertThat(EmailStore.get(EmailTables.MESSAGE, resent.messageId(), null).getValueString("unsubscribeUrl")).isEqualTo(url);
      new com.kingsrook.qbits.email.tracking.EmailUnsubscribe().unsubscribe(url.substring(url.lastIndexOf('/') + 1));
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1).allSatisfy(row -> assertThat(row.getValueString("address")).isEqualTo("person@example.com"));
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", queued.messageId()).withValue("ccAddresses", "other@example.com"));
      EmailRuntime.config().withSecretResolver(name -> null);
      assertThat(EmailService.resend(queued.messageId()).status()).isEqualTo(EmailSendResult.Status.REFUSED);
   }

   /*******************************************************************************
    ** Branded rendered templates reach a real SMTP sink with both alternatives.
    *******************************************************************************/
   @Test
   void greenMailAndBuiltIn() throws Exception
   {
      GreenMail mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
      try
      {
         mail.start();
         SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("port", mail.getSmtp().getPort()));
         H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "blue").withValue("label", "Blue Brand").withValue("primaryColor", "#123456").withValue("isDefault", true));
         var result = EmailService.send(new EmailRequest().withTemplateName("email_secure_message").withTo("p@example.com").withVariable("link", "https://example.com/secure"));
         assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
         assertThat(new EmailDispatcher().runOnce().sent()).isEqualTo(1);
         assertThat(mail.waitForIncomingEmail(2000, 1)).isTrue();
         Multipart parts = (Multipart) mail.getReceivedMessages()[0].getContent();
         assertThat(parts.getBodyPart(0).getContent().toString()).contains("Blue Brand", "https://example.com/secure");
         assertThat(parts.getBodyPart(1).getContent().toString()).contains("#123456", "email-button", "Blue Brand");
         EmailService.send(new EmailRequest().withTemplateName("email_secure_message").withTo("p@example.com").withVariable("link", "https://example.com/secure"));
         assertThat(H2Support.all(EmailTables.TEMPLATE)).hasSize(1);
      }
      finally
      {
         mail.stop();
      }
   }

   /*******************************************************************************
    ** Preview and home render through the standard widget endpoint, with empties.
    *******************************************************************************/
   @Test
   void previewAndHome() throws Exception
   {
      assertThat(EmailHome.overview().getHtml()).contains("No messages yet").doesNotContain("Relay", "Provider health").doesNotContain("<style", "overflow:auto");
      assertThat(EmailHome.hourly().getSublabel()).contains("No completed attempts");
      assertThat(EmailHome.suppressions().getSublabel()).contains("No new suppressions");
      QRecord version = draft("preview", "HTML", "[]", "{}", "<html><body><p>Preview text</p><script>evil()</script><img src=x onerror=evil()></body></html>");
      var widget = QContext.getQInstance().getWidget("emailTemplatePreview");
      var output = new RenderWidgetAction().execute(new RenderWidgetInput().withWidgetMetaData(widget).withUrlParams(Map.of("id", version.getValueString("id"))));
      String html = ((RawHTML) output.getWidgetData()).getHtml();
      assertThat(html).contains("Light", "Dark", "390px", "600px", "Plain text", "Preview text").doesNotContain("<iframe", "<script", "onerror", "<style");
      EmailVersions.activate(version.getValueInteger("id"));
      EmailService.send(new EmailRequest().withTemplateName("preview").withTo("Person <person@example.com>"));
      assertThat(EmailHome.overview().getHtml()).contains("p***@example.com", "/app/email_message/");
      QContext.getQSession().setPermissions(Set.of("email.view"));
      assertThat(EmailHome.overview().getHtml()).doesNotContain("Provider health", "needs email.admin");
   }

   /*******************************************************************************
    ** Exact metrics use event dates and viewer permissions, with no sample limits.
    *******************************************************************************/
   @Test
   void homeCountsAndBrandPrecedence() throws Exception
   {
      java.time.Instant now = java.time.Instant.parse("2026-10-13T12:00:00Z");
      EmailRuntime.setClock(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
      SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("status", "DELIVERED").withValue("subject", "Visible")
         .withValue("toAddresses", "person@example.com").withValue("sentDate", now.minusSeconds(60)).withValue("deliveredDate", now)
         .withValue("bouncedDate", now.minusSeconds(2 * 86400)).withValue("complainedDate", now.minusSeconds(8 * 86400)));
      SystemWrites.insertOne(EmailTables.ATTEMPT, new QRecord().withValue("messageId", 1).withValue("providerId", 1).withValue("attemptNumber", 1)
         .withValue("outcome", "SUCCEEDED").withValue("startDate", now.minusSeconds(10)).withValue("endDate", now));
      H2Support.insert(EmailTables.SUPPRESSION, new QRecord().withValue("address", "x@example.com").withValue("scope", "ALL").withValue("reason", "MANUAL"));
      for(String type : List.of("DELIVERED", "BOUNCED", "COMPLAINED"))
      {
         SystemWrites.insertOne(EmailTables.EVENT, new QRecord().withValue("messageId", 1).withValue("eventType", type).withValue("eventKey", type)
            .withValue("occurredDate", "DELIVERED".equals(type) ? now : now.minusSeconds(("BOUNCED".equals(type) ? 2 : 8) * 86400)));
      }
      SystemWrites.insertOne(EmailTables.EVENT, new QRecord().withValue("messageId", 1).withValue("eventType", "DELIVERED").withValue("eventKey", "second-delivery")
         .withValue("occurredDate", now.minusSeconds(60)));
      var tiles = org.jsoup.Jsoup.parse(EmailHome.overview().getHtml()).select("body > div > div > div");
      assertThat(tiles).extracting(org.jsoup.nodes.Element::text).containsExactly("1 Sent (24 h)", "2 Delivered (24 h)", "0 Dead letters (24 h)", "1 Bounced (7 d)", "0 Complaints (7 d)", "1 Suppressed addresses");
      assertThat(EmailHome.hourly().getChartData().getDatasets().getFirst().getData()).contains(1);
      assertThat(EmailHome.suppressions().getChartData().getDatasets().get(3).getData()).contains(1);
      QRecord defaultBrand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "default").withValue("label", "Default").withValue("isDefault", true));
      QRecord appBrand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "app").withValue("label", "App").withValue("appName", "email"));
      assertThat(com.kingsrook.qbits.email.render.EmailBrands.resolve(null, null).getValue("id")).isEqualTo(appBrand.getValue("id"));
      assertThat(com.kingsrook.qbits.email.render.EmailBrands.resolve(null, new QRecord().withValue("brandId", defaultBrand.getValue("id"))).getValue("id")).isEqualTo(defaultBrand.getValue("id"));
      assertThat(com.kingsrook.qbits.email.render.EmailBrands.resolve("app", new QRecord().withValue("brandId", defaultBrand.getValue("id"))).getValue("id")).isEqualTo(appBrand.getValue("id"));
      assertThatThrownBy(() -> H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "bad").withValue("textColor", "#ffffff")))
         .hasMessageContaining("needs 4.5:1");
   }

   /*******************************************************************************
    ** Signed links bind the real database id, and HTML footers precede body end.
    *******************************************************************************/
   @Test
   void signedNotificationFooter() throws Exception
   {
      EmailRuntime.config().withSecretResolver(name -> "QQQ_EMAIL_UNSUBSCRIBE_KEY".equals(name) ? "test-unsubscribe-key" : null);
      QRecord version = draft("notice", "HTML", "[]", "{}", "<html><body><p>Notice</p></body></html>");
      SystemWrites.updateOne(EmailTables.TEMPLATE, new QRecord().withValue("id", version.getValue("templateId")).withValue("category", "NOTIFICATION"));
      EmailVersions.activate(version.getValueInteger("id"));
      var result = EmailService.send(new EmailRequest().withTemplateName("notice").withTo("person@example.com"));
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord message = EmailStore.get(EmailTables.MESSAGE, result.messageId(), null);
      String link = message.getValueString("unsubscribeUrl");
      assertThat(link).startsWith("https://app.example.com/qqq/hooks/email/unsubscribe/v1.").doesNotContain("person@example.com");
      String token = link.substring(link.lastIndexOf('/') + 1);
      String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(payload).contains("\"messageId\":\"" + result.messageId() + "\"", "NOTIFICATION", "addressHash");
      assertThat(message.getValueString("htmlBody").indexOf("Unsubscribe")).isLessThan(message.getValueString("htmlBody").indexOf("</body>"));
      var dispatcher = new EmailDispatcher(new com.kingsrook.qbits.email.transport.EmailTransportRegistry().withTransport("SMTP", (envelope, settings) ->
      {
         assertThat(envelope.getHeaders()).containsEntry("List-Unsubscribe", "<" + link + ">");
         return new com.kingsrook.qbits.email.transport.EmailTransportResult("signed");
      }));
      assertThat(dispatcher.sendNow(result.messageId())).isEqualTo(EmailDispatcher.SendResult.SENT);
      EmailRuntime.config().withSecretResolver(name -> null);
      assertThat(com.kingsrook.qbits.email.render.EmailUnsubscribeLinks.create("1", "address", "NOTIFICATION")).isNull();
      assertThatThrownBy(() -> com.kingsrook.qbits.email.render.EmailUnsubscribeLinks.create("1", "address", "MARKETING")).hasMessageContaining("QQQ_EMAIL_UNSUBSCRIBE_KEY");
      EmailRuntime.config().withSecretResolver(name ->
      {
         throw new IllegalArgumentException("resolver detail");
      });
      assertThatThrownBy(() -> com.kingsrook.qbits.email.render.EmailUnsubscribeLinks.create("1", "person@example.com", "NOTIFICATION"))
         .hasMessage("Opt-out email needs publicBaseUrl and QQQ_EMAIL_UNSUBSCRIBE_KEY");
   }

   /*******************************************************************************
    ** Template active-version possible values are scoped to the template record.
    *******************************************************************************/
   @Test
   void templateVersionPossibleValues() throws Exception
   {
      possibleValues(EmailTables.TEMPLATE, "activeVersionId", "id");
   }

   /*******************************************************************************
    ** Message version possible values follow the selected template input.
    *******************************************************************************/
   @Test
   void messageVersionPossibleValues() throws Exception
   {
      possibleValues(EmailTables.MESSAGE, "templateVersionId", "templateId");
   }

   /*******************************************************************************
    ** Approval pointers use the same parent-scoped possible values.
    *******************************************************************************/
   @Test
   void approvedVersionPossibleValues() throws Exception
   {
      possibleValues(EmailTables.TEMPLATE, "approvedVersionId", "id");
   }

   /*******************************************************************************
    ** Execute core's possible-value search with the interpreted form binding.
    *******************************************************************************/
   private void possibleValues(String table, String field, String inputName) throws Exception
   {
      QRecord a = draft("a", "HTML", "[]", "{}", "A");
      QRecord b = draft("b", "HTML", "[]", "{}", "B");
      var metadata = QContext.getQInstance().getTable(table).getField(field);
      assertThat(metadata.getPossibleValueSourceFilter()).isNotNull();
      var filter = metadata.getPossibleValueSourceFilter().clone();
      assertThat(filter.toString()).contains("${input." + inputName + "}");
      filter.interpretValues(Map.of(inputName, a.getValue("templateId")));
      var found = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput().withPossibleValueSourceName(metadata.getPossibleValueSourceName()).withDefaultQueryFilter(filter));
      assertThat(found.getResults().stream().map(v -> v.getId().toString()).toList()).contains(a.getValueString("id")).doesNotContain(b.getValueString("id"));
   }
}
