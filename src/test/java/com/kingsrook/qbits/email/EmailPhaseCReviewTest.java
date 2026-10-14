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


import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.render.EmailUnsubscribeLinks;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailTemplateDefinition;
import com.kingsrook.qbits.email.template.EmailTemplates;
import com.kingsrook.qbits.email.template.EmailVersions;
import com.kingsrook.qbits.email.tracking.EmailUnsubscribe;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.NOW;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Retention of opt-out mail and the consumer-facing template API.
 *******************************************************************************/
class EmailPhaseCReviewTest
{
   /*******************************************************************************
    ** Isolated database with opt-out links configured and message retention on.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c -> c.withPublicBaseUrl("https://app.example.com").withSecretResolver(name -> SECRET).withMessageRetentionDays(30));
      H2Support.provider(25);
      EmailRuntime.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
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
    ** An old final message with content, in the given category.
    *******************************************************************************/
   private static QRecord oldMessage(String category, String address) throws Exception
   {
      return (SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("providerId", H2Support.all(EmailTables.PROVIDER).getFirst().getValue("id"))
         .withValue("status", "SENT").withValue("category", category)
         .withValue("toAddresses", address).withValue("sentDate", NOW.minus(Duration.ofDays(100))).withValue("htmlBody", "<p>hi</p>")
         .withValue("textBody", "hi").withValue("variablesSnapshot", "{\"name\":\"x\"}")));
   }

   /*******************************************************************************
    ** Old marketing and notification mail is kept (content cleared) so its
    ** unsubscribe link works; transactional mail is deleted.
    *******************************************************************************/
   @Test
   void retentionKeepsOptOutMessagesAndTheirUnsubscribeLinks() throws Exception
   {
      QRecord marketing = oldMessage("MARKETING", "pat@example.com");
      QRecord notification = oldMessage("NOTIFICATION", "sam@example.com");
      QRecord transactional = oldMessage("TRANSACTIONAL", "lee@example.com");
      String url = EmailUnsubscribeLinks.create(marketing.getValueString("id"), "pat@example.com", "MARKETING");
      assertThat(url).isNotNull();
      EmailProcesses.retain();
      assertThat(EmailStore.get(EmailTables.MESSAGE, transactional.getValueInteger("id"), null)).isNull();
      for(QRecord kept : List.of(marketing, notification))
      {
         QRecord row = EmailStore.get(EmailTables.MESSAGE, kept.getValueInteger("id"), null);
         assertThat(row).isNotNull();
         assertThat(row.getValueString("htmlBody")).isNull();
         assertThat(row.getValueString("textBody")).isNull();
         assertThat(row.getValueString("variablesSnapshot")).isNull();
      }
      new EmailUnsubscribe().unsubscribe(url.substring(url.lastIndexOf('/') + 1));
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1).allSatisfy(row ->
      {
         assertThat(row.getValueString("address")).isEqualTo("pat@example.com");
         assertThat(row.getValueString("scope")).isEqualTo("MARKETING");
      });
      EmailProcesses.retain();
      assertThat(EmailStore.get(EmailTables.MESSAGE, marketing.getValueInteger("id"), null)).isNotNull();
   }

   /*******************************************************************************
    ** A complete definition used by the template tests.
    *******************************************************************************/
   private static EmailTemplateDefinition definition()
   {
      return (new EmailTemplateDefinition().withName("order_ready").withLabel("Order ready").withCategory(EmailCategory.TRANSACTIONAL)
         .withSubject("Order {{order_number}} is ready").withBody("Hi {{customer_name}}, order {{order_number}} is ready.")
         .withVariable("order_number", "Order number", "STRING", true, "PUBLIC")
         .withVariable("customer_name", "Customer name", "STRING", true, null)
         .withSampleVariables("{\"order_number\":\"A-1\",\"customer_name\":\"Pat\"}").withOwnerQbit("orders"));
   }

   /*******************************************************************************
    ** Created once as an ACTIVE version 1 that sends; the second call changes nothing.
    *******************************************************************************/
   @Test
   void ensureCreatesOnceAndIsIdempotent() throws Exception
   {
      assertThat(EmailTemplates.ensure(definition())).isTrue();
      assertThat(EmailTemplates.ensure(definition())).isFalse();
      QRecord template = H2Support.all(EmailTables.TEMPLATE).getFirst();
      assertThat(H2Support.all(EmailTables.TEMPLATE)).hasSize(1);
      assertThat(template.getValueString("ownerQbit")).isEqualTo("orders");
      assertThat(template.getValueString("kind")).isEqualTo("MARKDOWN");
      assertThat(template.getValueBoolean("phiApproved")).isFalse();
      List<QRecord> versions = H2Support.all(EmailTables.VERSION);
      assertThat(versions).hasSize(1);
      assertThat(versions.getFirst().getValueString("status")).isEqualTo("ACTIVE");
      assertThat(versions.getFirst().getValueInteger("versionNumber")).isEqualTo(1);
      assertThat(versions.getFirst().getValueString("variablesSchema")).contains("order_number").contains("PUBLIC");
      assertThat(template.getValue("activeVersionId")).isEqualTo(versions.getFirst().getValue("id"));
      EmailSendResult result = EmailService.send(new EmailRequest().withTemplateName("order_ready").withTo("pat@example.com")
         .withVariable("order_number", "A-1").withVariable("customer_name", "Pat"));
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
   }

   /*******************************************************************************
    ** An edited template keeps its edits when the defining qbit calls ensure again.
    *******************************************************************************/
   @Test
   void ensureDoesNotTouchAnEditedTemplate() throws Exception
   {
      assertThat(EmailTemplates.ensure(definition())).isTrue();
      QRecord template = H2Support.all(EmailTables.TEMPLATE).getFirst();
      QRecord draft = EmailVersions.newVersion(template.getValueInteger("id"));
      new UpdateAction().execute(new UpdateInput(EmailTables.VERSION).withRecords(List.of(new QRecord().withValue("id", draft.getValue("id"))
         .withValue("bodySource", "Edited by a person for {{order_number}}"))));
      EmailVersions.activate(draft.getValueInteger("id"));
      assertThat(EmailTemplates.ensure(definition().withBody("Changed again {{order_number}} {{customer_name}}"))).isFalse();
      assertThat(H2Support.all(EmailTables.VERSION)).hasSize(2);
      QRecord active = EmailStore.get(EmailTables.VERSION, EmailStore.get(EmailTables.TEMPLATE, template.getValueInteger("id"), null).getValueInteger("activeVersionId"), null);
      assertThat(active.getValueInteger("versionNumber")).isEqualTo(2);
      assertThat(active.getValueString("bodySource")).isEqualTo("Edited by a person for {{order_number}}");
   }

   /*******************************************************************************
    ** Invalid definitions are refused with plain reasons and write nothing.
    *******************************************************************************/
   @Test
   void ensureRefusesInvalidDefinitions() throws Exception
   {
      assertThatThrownBy(() -> EmailTemplates.ensure(new EmailTemplateDefinition().withName("Bad Name").withKind("PDF")))
         .isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Name must be snake_case").hasMessageContaining("Label is required").hasMessageContaining("Category is required")
         .hasMessageContaining("Kind must be").hasMessageContaining("Subject is required").hasMessageContaining("Body is required");
      assertThatThrownBy(() -> EmailTemplates.ensure(definition().withVariablesSchema("[]")))
         .hasMessageContaining("not both");
      assertThatThrownBy(() -> EmailTemplates.ensure(new EmailTemplateDefinition().withName("x").withLabel("X").withCategory(EmailCategory.SECURITY)
         .withSubject("S").withBody("B").withVariablesSchema("{").withSampleVariables("[")))
         .hasMessageContaining("Invalid variables schema").hasMessageContaining("Variables must be a JSON object");
      assertThatThrownBy(() -> EmailTemplates.ensure(definition().withBody("Hi {{undeclared_thing}}")))
         .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refused");
      assertThatThrownBy(() -> EmailTemplates.ensure(definition().withSampleVariables("{}")))
         .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refused");
      assertThat(H2Support.all(EmailTables.TEMPLATE)).isEmpty();
      assertThat(H2Support.all(EmailTables.VERSION)).isEmpty();
   }
}
