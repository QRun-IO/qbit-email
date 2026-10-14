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

package com.kingsrook.qbits.email.api;


import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailEffects;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.support.EmailApiMapper;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.messaging.SendMessageAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Content;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Party;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.email.EmailContentRole;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Stable API mapping and behavior against the real outbox and H2 database.
 *******************************************************************************/
class EmailApiTest
{
   private QInstance instance;
   private QRecord provider;



   /*******************************************************************************
    ** No environment secrets are used by these tests.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      instance = H2Support.setup(config -> config.withSecretResolver(name -> null));
      provider = H2Support.provider(25);
   }



   /*******************************************************************************
    ** Release thread-local context.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Every request property reaches the internal request with the same value.
    *******************************************************************************/
   @Test
   void mapsEveryRequestField() throws Exception
   {
      Instant after = Instant.parse("2030-01-02T03:04:05Z");
      EmailApiRequest request = new EmailApiRequest().withTo(new ArrayList<>(List.of("to@example.com"))).withTo("second@example.com")
         .withCc(new ArrayList<>(List.of("cc@example.com"))).withCc("cc2@example.com")
         .withBcc(new ArrayList<>(List.of("bcc@example.com"))).withBcc("bcc2@example.com")
         .withReplyTo("reply@example.com").withFrom("from@example.com").withFromAddressId(8)
         .withSubject("Subject").withMarkdownBody("Markdown").withHtmlBody("<p>HTML</p>").withTextBody("Text")
         .withTemplateName("notice").withVariables(new java.util.LinkedHashMap<>(Map.of("number", 7)))
         .withVariable("label", "value").withRecordVariables(new java.util.LinkedHashMap<>())
         .withRecordVariable("order", "orders", 42).withCategory(EmailApiCategory.SECURITY)
         .withProviderName("named").withBrandName("brand").withLocale("fr").withIdempotencyKey("api:42")
         .withLinkedTableName("old").withLinkedRecordId("old").withLinkedRecord("orders", 42)
         .withSenderQbit("old").withSenderProcess("old").withSender("orders", "complete").withSendAfter(after)
         .withTransaction(new QBackendTransaction(), "rdbms");
      EmailRequest mapped = EmailApiMapper.request(request);
      for(Method getter : EmailApiRequest.class.getDeclaredMethods())
      {
         if(!getter.getName().startsWith("get"))
         {
            continue;
         }
         Object value = getter.invoke(request);
         assertThat(value).as(getter.getName()).isNotNull();
         if(getter.getName().equals("getCategory"))
         {
            assertThat(mapped.getCategory().name()).isEqualTo(request.getCategory().name());
         }
         else if(getter.getName().equals("getRecordVariables"))
         {
            assertThat(mapped.getRecordVariables()).hasSize(1);
            assertThat(mapped.getRecordVariables().get("order").table()).isEqualTo("orders");
            assertThat(mapped.getRecordVariables().get("order").id()).isEqualTo(42);
         }
         else
         {
            assertThat(EmailRequest.class.getMethod(getter.getName()).invoke(mapped)).as(getter.getName()).isEqualTo(value);
         }
      }
      assertThat(mapped.getTo()).containsExactly("to@example.com", "second@example.com");
      assertThat(mapped.getLinkedRecordId()).isEqualTo("42");
      assertThat(mapped.getSenderQbit()).isEqualTo("orders");
      assertThat(new EmailApiRequest().withLinkedRecord("orders", null).getLinkedRecordId()).isNull();
      assertThat(EmailApiMapper.request(new EmailApiRequest()).getCategory()).isNull();
      assertThat(EmailApiMapper.request(new EmailApiRequest()).getLocale()).isEqualTo("en");
      for(EmailApiCategory category : EmailApiCategory.values())
      {
         assertThat(EmailApiMapper.request(new EmailApiRequest().withCategory(category)).getCategory()).isEqualTo(EmailCategory.valueOf(category.name()));
      }
   }



   /*******************************************************************************
    ** All statuses and refusal reasons survive mapping without sharing lists.
    *******************************************************************************/
   @Test
   void mapsEveryStatus()
   {
      for(EmailSendResult.Status status : EmailSendResult.Status.values())
      {
         List<String> reasons = new ArrayList<>(List.of("Reason"));
         EmailApiResult result = EmailApiMapper.result(new EmailSendResult(42, status, reasons));
         reasons.clear();
         assertThat(result.messageId()).isEqualTo(42);
         assertThat(result.status()).isEqualTo(EmailApiStatus.valueOf(status.name()));
         assertThat(result.reasons()).containsExactly("Reason");
         assertThatThrownBy(() -> result.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
      }
   }



   /*******************************************************************************
    ** Sends keep validation, idempotency, suppression and permission behavior.
    *******************************************************************************/
   @Test
   void sendsThroughExistingService() throws Exception
   {
      EmailApiResult queued = EmailApi.send(request("same"));
      assertThat(queued.status()).isEqualTo(EmailApiStatus.QUEUED);
      assertThat(EmailApi.send(request("same")).status()).isEqualTo(EmailApiStatus.DUPLICATE);
      assertThat(EmailApi.send(request("same")).messageId()).isEqualTo(queued.messageId());
      assertThat(EmailApi.send(null).status()).isEqualTo(EmailApiStatus.REFUSED);
      assertThat(EmailApi.send(request("bad").withSubject("bad\nsubject")).status()).isEqualTo(EmailApiStatus.REFUSED);
      SystemWrites.insertOne(EmailTables.SUPPRESSION, new QRecord().withValue("address", "pat@example.com")
         .withValue("reason", "HARD_BOUNCE").withValue("scope", "ALL"));
      assertThat(EmailApi.send(request("suppressed")).status()).isEqualTo(EmailApiStatus.SUPPRESSED);
      instance.setEnforcePermissionsInCoreActions(true);
      QContext.getQSession().setPermissions(Set.of());
      assertThat(EmailApi.send(request("denied")).reasons()).contains("Sending email needs email.send");
      assertThat(EmailApi.getMessage(queued.messageId())).isPresent();
      assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix("same", 1)).hasSize(1);
   }



   /*******************************************************************************
    ** Durable handoffs and internal replays agree on the committed row and header.
    *******************************************************************************/
   @Test
   void sendsOnceAndReconcilesCurrentMetadata() throws Exception
   {
      String key = "job:2:row:email_%";
      EmailApiRequest request = request(null).withLinkedRecord("orders", 42);
      EmailApiEffectResult first = EmailApi.sendOnce(request, key);
      assertThat(request.getIdempotencyKey()).isEqualTo(key);
      assertThat(first.outcome()).isEqualTo(EmailApiEffectOutcome.QUEUED_OR_DUPLICATE);
      assertThat(first.message().status()).isEqualTo("QUEUED");
      assertThat(first.message().idempotencyKey()).isEqualTo(key);
      assertThat(first.message().rfcMessageId()).matches("<[0-9a-f]{32}@example\\.com>");
      assertThat(EmailApi.sendOnce(request(key), key)).isEqualTo(first);
      var internal = EmailEffects.send(EmailApiMapper.request(request(null)), key);
      assertThat(internal.outcome().name()).isEqualTo(first.outcome().name());
      assertThat(internal.message().id()).isEqualTo(first.message().id());
      assertThat(internal.message().rfcMessageId()).isEqualTo(first.message().rfcMessageId());
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
      assertThat(EmailApi.getMessage(first.message().id())).contains(first.message());
      assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix("job:", 5)).containsExactly(first.message());
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", first.message().id())
         .withValue("status", "SENT").withValue("providerMessageId", "provider-reference"));
      instance.setEnforcePermissionsInCoreActions(true);
      QContext.getQSession().setPermissions(Set.of());
      EmailApiMessage found = EmailApi.getMessageByIdempotencyKey(key).orElseThrow();
      assertThat(found.id()).isEqualTo(first.message().id());
      assertThat(found.status()).isEqualTo("SENT");
      assertThat(found.providerMessageId()).isEqualTo("provider-reference");
      assertThat(found.rfcMessageId()).isEqualTo(first.message().rfcMessageId());
      assertThat(found.linkedRecordId()).isEqualTo("42");
      assertThat(EmailApi.getMessageByRfcMessageId(found.rfcMessageId())).contains(found);
      for(String missing : new String[] {null, "", " ", "missing", "job:", "job:2:row:email_", "provider-reference"})
      {
         assertThat(EmailApi.getMessageByIdempotencyKey(missing)).isEmpty();
         assertThat(EmailApi.getMessageByRfcMessageId(missing)).isEmpty();
      }
      assertThat(EmailApi.getMessageByRfcMessageId(found.rfcMessageId().replace("<", "").replace(">", ""))).isEmpty();
   }



   /*******************************************************************************
    ** Invalid handoffs preserve caller keys and never create an outbox row.
    *******************************************************************************/
   @Test
   void sendOnceRefusesInvalidRequestsAndKeys() throws Exception
   {
      for(String key : new String[] {null, "", " ", "a".repeat(501)})
      {
         EmailApiRequest request = request(null);
         assertThat(EmailApi.sendOnce(request, key)).isEqualTo(new EmailApiEffectResult(EmailApiEffectOutcome.REFUSED, null));
         assertThat(request.getIdempotencyKey()).isNull();
         assertThat(EmailEffects.send(EmailApiMapper.request(request), key).outcome().name()).isEqualTo("REFUSED");
      }
      EmailApiRequest conflict = request("other");
      assertThat(EmailApi.sendOnce(conflict, "effect").outcome()).isEqualTo(EmailApiEffectOutcome.REFUSED);
      assertThat(conflict.getIdempotencyKey()).isEqualTo("other");
      assertThat(EmailApi.sendOnce(null, "effect")).isEqualTo(new EmailApiEffectResult(EmailApiEffectOutcome.REFUSED, null));
      assertThat(EmailApi.sendOnce(request(null).withSubject(null), "invalid"))
         .isEqualTo(new EmailApiEffectResult(EmailApiEffectOutcome.REFUSED, null));
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
      assertThat(EmailApi.sendOnce(request(null), "a".repeat(500)).outcome()).isEqualTo(EmailApiEffectOutcome.QUEUED_OR_DUPLICATE);
      instance.setEnforcePermissionsInCoreActions(true);
      QContext.getQSession().setPermissions(Set.of());
      assertThat(EmailApi.sendOnce(request(null), "denied")).isEqualTo(new EmailApiEffectResult(EmailApiEffectOutcome.REFUSED, null));
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** Replaying a suppressed audit row remains refused with the same identity.
    *******************************************************************************/
   @Test
   void sendOnceRefusesSuppressedDuplicates() throws Exception
   {
      SystemWrites.insertOne(EmailTables.SUPPRESSION, new QRecord().withValue("address", "pat@example.com")
         .withValue("reason", "HARD_BOUNCE").withValue("scope", "ALL"));
      EmailApiEffectResult first = EmailApi.sendOnce(request(null), "suppressed");
      assertThat(first.outcome()).isEqualTo(EmailApiEffectOutcome.REFUSED);
      assertThat(first.message().status()).isEqualTo("SUPPRESSED");
      assertThat(first.message().rfcMessageId()).isNotBlank();
      assertThat(EmailApi.sendOnce(request(null), "suppressed")).isEqualTo(first);
      var internal = EmailEffects.send(EmailApiMapper.request(request(null)), "suppressed");
      assertThat(internal.outcome().name()).isEqualTo(first.outcome().name());
      assertThat(internal.message().id()).isEqualTo(first.message().id());
      assertThat(internal.message().rfcMessageId()).isEqualTo(first.message().rfcMessageId());
      assertThat(EmailApi.getMessageByIdempotencyKey("suppressed")).contains(first.message());
      assertThat(EmailApi.getMessageByRfcMessageId(first.message().rfcMessageId())).contains(first.message());
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** Neither a shared-backend transaction nor a deferred callback is a handoff.
    *******************************************************************************/
   @Test
   void sendOnceRefusesCallerTransactions() throws Exception
   {
      try(QBackendTransaction transaction = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         for(String backend : new String[] {null, "rdbms", "other"})
         {
            EmailApiRequest request = request(null).withTransaction(transaction, backend);
            assertThat(EmailApi.sendOnce(request, "transaction")).isEqualTo(new EmailApiEffectResult(EmailApiEffectOutcome.REFUSED, null));
            assertThat(request.getIdempotencyKey()).isNull();
         }
         transaction.commit();
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** Ordinary sends retain caller transaction behavior through the public mapper.
    *******************************************************************************/
   @Test
   void ordinarySendCanRollBackWithCaller() throws Exception
   {
      try(QBackendTransaction transaction = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         assertThat(EmailApi.send(request("rollback").withTransaction(transaction, "rdbms")).status()).isEqualTo(EmailApiStatus.QUEUED);
         transaction.rollback();
      }
      assertThat(EmailApi.getMessageByIdempotencyKey("rollback")).isEmpty();
   }



   /*******************************************************************************
    ** Uncertain storage failures propagate so dispatchers can retry or reconcile.
    *******************************************************************************/
   @Test
   void sendOncePropagatesStorageFailure() throws Exception
   {
      EmailApiEffectResult first = EmailApi.sendOnce(request(null), "collision");
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", first.message().id()).withValue("idempotencyKey", "changed"));
      assertThatThrownBy(() -> EmailApi.sendOnce(request(null), "collision")).isInstanceOf(QException.class);
      assertThat(EmailApi.getMessageByIdempotencyKey("collision")).isEmpty();
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** Plain sends need only QQQ core messaging types at the consumer boundary.
    *******************************************************************************/
   @Test
   void coreOnlyBridgeQueuesPlainMail() throws Exception
   {
      new SendMessageAction().execute(new SendMessageInput().withMessagingProviderName("qbitEmail")
         .withTo(new Party().withAddress("pat@example.com")).withSubject("Ready")
         .withContent(new Content().withContentRole(EmailContentRole.TEXT).withBody("Export ready")));
      assertThat(H2Support.all(EmailTables.MESSAGE)).singleElement().satisfies(row ->
      {
         assertThat(row.getValueString("subject")).isEqualTo("Ready");
         assertThat(row.getValueString("status")).isEqualTo("QUEUED");
      });
   }



   /*******************************************************************************
    ** Template fields are preserved, creation is idempotent, and edits survive.
    *******************************************************************************/
   @Test
   void ensuresAndMapsTemplate() throws Exception
   {
      EmailApiTemplate template = template().withLabel("Notice").withKind("HTML").withTextBody("Text {{number}}")
         .withVariables(List.of(new EmailApiVariable("number", "Number", "STRING", true, "PUBLIC")))
         .withSampleVariables("{\"number\":\"42\"}").withOwnerQbit("orders").withEditPermissionName("orders.edit");
      var mapped = EmailApiMapper.template(template);
      for(Method getter : EmailApiTemplate.class.getDeclaredMethods())
      {
         if(getter.getName().startsWith("get") && !Set.of("getVariables", "getVariablesSchema", "getCategory").contains(getter.getName()))
         {
            assertThat(mapped.getClass().getMethod(getter.getName()).invoke(mapped)).as(getter.getName()).isEqualTo(getter.invoke(template));
         }
      }
      assertThat(mapped.getCategory()).isEqualTo(EmailCategory.TRANSACTIONAL);
      assertThat(mapped.getVariablesSchema()).contains("number", "Number", "STRING", "true", "PUBLIC");
      assertThat(EmailApi.ensureTemplate(template)).isTrue();
      assertThat(EmailApi.ensureTemplate(template.withBody("Replacement"))).isFalse();
      assertThat(H2Support.all(EmailTables.TEMPLATE)).singleElement().satisfies(row ->
      {
         assertThat(row.getValueString("ownerQbit")).isEqualTo("orders");
         assertThat(row.getValueString("editPermissionName")).isEqualTo("orders.edit");
         assertThat(row.getValueBoolean("phiApproved")).isFalse();
      });
      assertThat(H2Support.all(EmailTables.VERSION)).singleElement().satisfies(row ->
      {
         assertThat(row.getValueString("bodySource")).isEqualTo("Hello {{number}}");
         assertThat(row.getValueString("textSource")).isEqualTo("Text {{number}}");
         assertThat(row.getValueString("status")).isEqualTo("ACTIVE");
      });
      assertThat(EmailApi.send(new EmailApiRequest().withTo("pat@example.com").withTemplateName("notice").withVariable("number", "42")).status())
         .isEqualTo(EmailApiStatus.QUEUED);
      assertThat(EmailApi.ensureTemplate(template().withName("raw_schema").withVariablesSchema("[]").withBody("Body"))).isTrue();
      assertThatThrownBy(() -> EmailApi.ensureTemplate(new EmailApiTemplate())).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> EmailApi.ensureTemplate(template().withVariable("number", "Number", "STRING", true, "PUBLIC")
         .withVariablesSchema("[]"))).hasMessageContaining("not both");
      assertThatThrownBy(() -> EmailApi.ensureTemplate(template().withName("private_notice")
         .withVariable(new EmailApiVariable("number", "Number", "STRING", true, "PHI"))
         .withSampleVariables("{\"number\":\"synthetic\"}"))).hasMessageContaining("PHI");
   }



   /*******************************************************************************
    ** Lookups expose only metadata and handle literal wildcard characters.
    *******************************************************************************/
   @Test
   void looksUpMetadataAndLiteralPrefixes() throws Exception
   {
      EmailApi.ensureTemplate(template().withBody("Body"));
      int templateId = H2Support.all(EmailTables.TEMPLATE).getFirst().getValueInteger("id");
      Instant sent = Instant.parse("2030-01-02T03:04:05Z");
      Instant delivered = sent.plusSeconds(5);
      EmailApiResult result = EmailApi.send(request("orders:1").withLinkedRecord("orders", 9));
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", result.messageId()).withValue("templateId", templateId)
         .withValue("status", "DELIVERED").withValue("providerMessageId", "provider-42").withValue("sentDate", sent).withValue("deliveredDate", delivered));
      EmailApiMessage message = EmailApi.getMessage(result.messageId()).orElseThrow();
      assertThat(message.id()).isEqualTo(result.messageId());
      assertThat(message.status()).isEqualTo("DELIVERED");
      assertThat(message.category()).isEqualTo(EmailApiCategory.TRANSACTIONAL);
      assertThat(message.idempotencyKey()).isEqualTo("orders:1");
      assertThat(message.rfcMessageId()).matches("<[0-9a-f]{32}@example\\.com>");
      assertThat(message.providerMessageId()).isEqualTo("provider-42");
      assertThat(message.templateName()).isEqualTo("notice");
      assertThat(message.linkedTableName()).isEqualTo("orders");
      assertThat(message.linkedRecordId()).isEqualTo("9");
      assertThat(message.createDate()).isNotNull();
      assertThat(message.sentDate()).isEqualTo(sent);
      assertThat(message.deliveredDate()).isEqualTo(delivered);
      assertThat(EmailApi.getMessage(null)).isEmpty();
      assertThat(EmailApi.getMessage(Integer.MAX_VALUE)).isEmpty();
      assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix("missing", 3)).isEmpty();
      EmailApi.send(request("orders:2"));
      assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix("orders:", 1)).containsExactly(message);
      assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix("orders:", 10)).hasSize(2);
      for(String prefix : List.of("literal_", "literal%", "literal\\"))
      {
         EmailApi.send(request("literalX" + prefix));
         EmailApiResult matching = EmailApi.send(request(prefix + "match"));
         assertThat(EmailApi.findMessagesByIdempotencyKeyPrefix(prefix, 1)).extracting(EmailApiMessage::id).containsExactly(matching.messageId());
      }
      for(String prefix : new String[] {null, "", " "})
      {
         assertThatThrownBy(() -> EmailApi.findMessagesByIdempotencyKeyPrefix(prefix, 1)).isInstanceOf(IllegalArgumentException.class);
      }
      assertThatThrownBy(() -> EmailApi.findMessagesByIdempotencyKeyPrefix("orders", 0)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> EmailApi.findMessagesByIdempotencyKeyPrefix("orders", -1)).isInstanceOf(IllegalArgumentException.class);
      assertThat(List.of(EmailApiMessage.class.getRecordComponents())).extracting(component -> component.getName())
         .containsExactly("id", "status", "category", "idempotencyKey", "rfcMessageId", "providerMessageId", "templateName", "linkedTableName", "linkedRecordId", "createDate", "sentDate", "deliveredDate");
   }



   /*******************************************************************************
    ** Default selection follows configuration and excludes disabled senders.
    *******************************************************************************/
   @Test
   void selectsProviderAndEnabledSenders() throws Exception
   {
      assertThat(EmailApi.isInstalled(instance)).isTrue();
      assertThat(EmailApi.isInstalled(null)).isFalse();
      assertThat(EmailApi.isInstalled(new QInstance())).isFalse();
      assertThat(EmailApi.defaultProviderName(instance)).contains("relay");
      assertThat(EmailApi.defaultProviderName(null)).isEmpty();
      assertThat(EmailApi.listSenderAddresses("unknown")).isEmpty();
      assertThat(EmailApi.listSenderAddresses("relay")).isEmpty();
      QRecord first = SystemWrites.insertOne(EmailTables.SENDER, new QRecord().withValue("providerId", provider.getValue("id"))
         .withValue("address", "first@example.com").withValue("displayName", "First").withValue("enabled", true).withValue("isDefault", true));
      SystemWrites.insertOne(EmailTables.SENDER, new QRecord().withValue("providerId", provider.getValue("id"))
         .withValue("address", "disabled@example.com").withValue("enabled", false));
      assertThat(EmailApi.listSenderAddresses(null)).containsExactly(new EmailApiSenderAddress(first.getValueInteger("id"), "first@example.com", "First", true));
      EmailApiSenderAddress address = EmailApi.listSenderAddresses("relay").getFirst();
      assertThat(address.id()).isEqualTo(first.getValueInteger("id"));
      assertThat(address.address()).isEqualTo("first@example.com");
      assertThat(address.displayName()).isEqualTo("First");
      assertThat(address.isDefault()).isTrue();
      EmailQBitConfig config = EmailRuntime.config();
      config.withDefaultProviderName("unknown");
      assertThat(EmailApi.defaultProviderName(instance)).isEmpty();
      config.withDefaultProviderName("relay");
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue("isDefault", false));
      assertThat(EmailApi.defaultProviderName(instance)).contains("relay");
      config.withDefaultProviderName(null);
      assertThat(EmailApi.defaultProviderName(instance)).isEmpty();
      config.withDefaultProviderName("relay");
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue("enabled", false));
      assertThat(EmailApi.defaultProviderName(instance)).isEmpty();
      assertThat(EmailApi.listSenderAddresses("relay")).isEmpty();
      QInstance other = H2Support.instance(H2Support.backend(), c -> c.withSecretResolver(name -> null));
      assertThatThrownBy(() -> EmailApi.defaultProviderName(other)).hasMessageContaining("current QContext");
   }



   /*******************************************************************************
    ** Readiness reports each missing prerequisite without leaking resolver data.
    *******************************************************************************/
   @Test
   void reportsReadinessAndPhiPrerequisites() throws Exception
   {
      assertThat(EmailApi.readiness(null, null).reasons()).containsExactly("Email qbit is not installed");
      assertThat(EmailApi.readiness(null, null).failedChecks()).containsExactly(EmailApiReadinessCheck.NOT_INSTALLED);
      assertThat(EmailApi.readiness(instance, null).ready()).isTrue();
      EmailApiReadiness marketing = EmailApi.readiness(instance, EmailApiCategory.MARKETING);
      assertThat(marketing.ready()).isFalse();
      assertThat(marketing.reasons()).hasSize(3);
      assertThat(String.join(";", marketing.reasons())).contains("publicBaseUrl", "QQQ_EMAIL_UNSUBSCRIBE_KEY", "postal address");
      assertThat(marketing.failedChecks()).containsExactlyInAnyOrder(EmailApiReadinessCheck.PUBLIC_BASE_URL, EmailApiReadinessCheck.UNSUBSCRIBE_KEY, EmailApiReadinessCheck.BRAND_POSTAL_ADDRESS);
      assertThat(marketing.failed(EmailApiReadinessCheck.PROVIDER_MISSING)).isFalse();
      EmailQBitConfig config = EmailRuntime.config();
      config.withPublicBaseUrl("https://app.example.com").withSecretResolver(name -> "synthetic-test-key");
      QRecord brand = SystemWrites.insertOne(EmailTables.BRAND, new QRecord().withValue("name", "default").withValue("label", "Default").withValue("isDefault", true));
      assertThat(EmailApi.readiness(instance, EmailApiCategory.MARKETING).reasons()).singleElement().asString().contains("postal address");
      SystemWrites.updateOne(EmailTables.BRAND, new QRecord().withValue("id", brand.getValue("id")).withValue("postalAddress", "1 Main St"));
      assertThat(EmailApi.readiness(instance, EmailApiCategory.MARKETING).ready()).isTrue();
      assertThat(EmailApi.send(request("multi").withTo("other@example.com").withCategory(EmailApiCategory.MARKETING)).status()).isEqualTo(EmailApiStatus.REFUSED);
      assertThat(EmailApi.send(request("marketing").withCategory(EmailApiCategory.MARKETING)).status()).isEqualTo(EmailApiStatus.QUEUED);
      config.withPublicBaseUrl("javascript:bad");
      assertThat(EmailApi.readiness(instance, EmailApiCategory.MARKETING).reasons()).containsExactly("MARKETING email needs a valid publicBaseUrl");
      config.withPublicBaseUrl("https://app.example.com").withSecretResolver(name ->
      {
         throw (new IllegalStateException("DO_NOT_EXPOSE_RESOLVER_DETAIL"));
      });
      assertThat(EmailApi.readiness(instance, EmailApiCategory.MARKETING).reasons()).containsExactly("MARKETING email needs QQQ_EMAIL_UNSUBSCRIBE_KEY");
      EmailApiReadiness phi = EmailApi.readiness(instance, EmailApiCategory.TRANSACTIONAL, true);
      assertThat(phi.reasons()).hasSize(2);
      assertThat(String.join(";", phi.reasons())).contains("QQQ_EMAIL_BODY_KEY", "PHI-enabled").doesNotContain("DO_NOT_EXPOSE");
      assertThat(phi.failedChecks()).containsExactlyInAnyOrder(EmailApiReadinessCheck.BODY_KEY, EmailApiReadinessCheck.PHI_PROVIDER);
      config.withSecretResolver(name -> " ");
      assertThat(EmailApi.readiness(instance, EmailApiCategory.SECURITY, true).ready()).isFalse();
      config.withSecretResolver(name -> "synthetic-test-key");
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id"))
         .withValue("phiAllowed", true).withValue("trackOpens", false).withValue("trackClicks", false));
      assertThat(EmailApi.readiness(instance, EmailApiCategory.SECURITY, true).ready()).isTrue();
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue("enabled", false));
      assertThat(EmailApi.readiness(instance, EmailApiCategory.TRANSACTIONAL).reasons()).containsExactly("An enabled named or default provider is required");
   }



   /*******************************************************************************
    ** Small valid ad-hoc request.
    *******************************************************************************/
   private EmailApiRequest request(String key)
   {
      return (new EmailApiRequest().withTo("pat@example.com").withSubject("Ready").withTextBody("Body").withIdempotencyKey(key));
   }



   /*******************************************************************************
    ** Template body may be replaced by tests using a different schema.
    *******************************************************************************/
   private EmailApiTemplate template()
   {
      return (new EmailApiTemplate().withName("notice").withLabel("Notice").withCategory(EmailApiCategory.TRANSACTIONAL)
         .withSubject("Ready").withBody("Hello {{number}}"));
   }
}
