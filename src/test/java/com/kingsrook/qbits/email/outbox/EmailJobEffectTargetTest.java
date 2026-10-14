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

package com.kingsrook.qbits.email.outbox;


import java.io.Serializable;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailQBitProducer;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.jobs.DurableJobsMetaData;
import com.kingsrook.qqq.backend.core.jobs.DurableJobsMetaDataProvider;
import com.kingsrook.qqq.backend.core.jobs.DurableJobsTables;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectRequest;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectResult;
import com.kingsrook.qqq.backend.core.jobs.effects.JobEffectType;
import com.kingsrook.qqq.backend.core.jobs.effects.ReconcileResult;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.FullyAnonymousAuthenticationModule;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Real H2 outbox and jobs lookup; no transport or mocked owner lookup.
 *******************************************************************************/
class EmailJobEffectTargetTest
{
   private final EmailJobEffectTarget target = new EmailJobEffectTarget();
   private static QSession observedSession;



   /*******************************************************************************
    ** Install a local outbox and provider without dispatching any messages.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(config ->
      {
      });
      H2Support.provider(25);
      observedSession = null;
   }



   /*******************************************************************************
    ** Restore per-test context and hook state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
      observedSession = null;
   }



   /*******************************************************************************
    ** A normal effect contains only serializable payload values.
    *******************************************************************************/
   private EffectRequest request(String key, Integer jobId, Map<String, Serializable> payload)
   {
      return (new EffectRequest(key, "welcomeEmail", JobEffectType.IDEMPOTENT_EXTERNAL, jobId, 1, "row", payload, null, 1));
   }



   /*******************************************************************************
    ** Valid ad-hoc email payload.
    *******************************************************************************/
   private Map<String, Serializable> payload()
   {
      return (new LinkedHashMap<>(Map.of("to", "person@example.com", "subject", "Welcome", "textBody", "Hello")));
   }



   /*******************************************************************************
    ** Replays acknowledge the same committed row and ignore payload identity keys.
    *******************************************************************************/
   @Test
   void deliversOnceAndReconciles() throws Exception
   {
      var payload = payload();
      payload.put("senderQbit", "spoofed");
      payload.put("senderProcess", "spoofed");
      payload.put("idempotencyKey", "spoofed");
      payload.put("transaction", "ignored");
      var request = request("same", null, payload);
      assertThat(target.reconcile(request)).isEqualTo(ReconcileResult.notFound());
      var first = target.deliver(request);
      assertThat(first.outcome()).isEqualTo(EffectResult.Outcome.SENT);
      assertThat(target.deliver(request)).isEqualTo(first);
      var rows = H2Support.all(EmailTables.MESSAGE);
      assertThat(rows).hasSize(1);
      var row = rows.getFirst();
      assertThat(first.providerRef()).isEqualTo(row.getValueString("id"));
      assertThat(row.getValueString("senderQbit")).isEqualTo("jobs");
      assertThat(row.getValueString("senderProcess")).isEqualTo("welcomeEmail");
      assertThat(row.getValueString("idempotencyKey")).isEqualTo("same");
      assertThat(target.reconcile(request)).isEqualTo(ReconcileResult.found(first.providerRef()));
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", row.getValueInteger("id")).withValue("status", "DEAD_LETTER"));
      assertThat(target.reconcile(request)).isEqualTo(ReconcileResult.found(first.providerRef()));
   }



   /*******************************************************************************
    ** Suppression is a permanent refusal on every attempt, but reconciles by row.
    *******************************************************************************/
   @Test
   void suppressedRecipientFails() throws Exception
   {
      H2Support.insert(EmailTables.SUPPRESSION, new QRecord().withValue("address", "person@example.com")
         .withValue("scope", "ALL").withValue("reason", "HARD_BOUNCE"));
      var request = request("suppressed", null, payload());
      assertThat(target.deliver(request)).isEqualTo(EffectResult.failed("All recipients are suppressed"));
      assertThat(target.deliver(request).outcome()).isEqualTo(EffectResult.Outcome.FAILED);
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
      assertThat(target.reconcile(request).outcome()).isEqualTo(ReconcileResult.Outcome.FOUND);
   }



   /*******************************************************************************
    ** Service validation reasons reach the effect without bypassing template rules.
    *******************************************************************************/
   @Test
   void validationRefusalRetainsReasons() throws Exception
   {
      var payload = payload();
      payload.put("variables", new LinkedHashMap<>(Map.of("name", "Person")));
      assertThat(target.deliver(request("variables", null, payload)))
         .isEqualTo(EffectResult.failed("Variables require a template with a schema"));
      assertThat(target.deliver(request("empty", null, null)).outcome()).isEqualTo(EffectResult.Outcome.FAILED);
      assertThat(target.deliver(request(" ", null, payload())).message()).contains("valid, matching effect key");
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** A database failure remains uncertain and does not disclose raw diagnostics.
    *******************************************************************************/
   @Test
   void storageFailureIsUnknown() throws Exception
   {
      var request = request("collision", null, payload());
      var sent = target.deliver(request);
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", Integer.valueOf(sent.providerRef())).withValue("idempotencyKey", "changed"));
      var result = target.deliver(request);
      assertThat(result.outcome()).isEqualTo(EffectResult.Outcome.UNKNOWN);
      assertThat(result.message()).doesNotContain("person@example.com", "INSERT", "Hello");
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** Malformed supported values follow the specified safe same-key retry policy.
    *******************************************************************************/
   @Test
   void decodingExceptionsAreUnknown() throws Exception
   {
      for(var invalid : List.of(Map.entry("to", 4), Map.entry("category", "invalid"), Map.entry("variables", "invalid")))
      {
         var payload = payload();
         payload.put(invalid.getKey(), (Serializable) invalid.getValue());
         assertThat(target.deliver(request("bad", null, payload)).outcome()).isEqualTo(EffectResult.Outcome.UNKNOWN);
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** Every documented optional value reaches the request without losing types.
    *******************************************************************************/
   @Test
   void decodesSupportedPayload()
   {
      var payload = payload();
      payload.put("to", new ArrayList<>(List.of("one@example.com", "two@example.com")));
      payload.put("cc", "cc@example.com");
      payload.put("bcc", new ArrayList<>(List.of("bcc@example.com")));
      for(String key : List.of("from", "replyTo", "templateName", "markdownBody", "htmlBody", "providerName", "brandName", "linkedTableName"))
      {
         payload.put(key, key);
      }
      payload.put("fromAddressId", 7);
      payload.put("locale", "fr");
      payload.put("category", "SECURITY");
      payload.put("sendAfter", "2026-10-14T12:00:00Z");
      payload.put("linkedRecordId", 42);
      payload.put("variables", new LinkedHashMap<>(Map.of("count", 3, "active", true)));
      var decoded = EmailJobEffectTarget.decode(payload);
      assertThat(decoded.getTo()).containsExactly("one@example.com", "two@example.com");
      assertThat(decoded.getCc()).containsExactly("cc@example.com");
      assertThat(decoded.getBcc()).containsExactly("bcc@example.com");
      assertThat(decoded.getFrom()).isEqualTo("from");
      assertThat(decoded.getFromAddressId()).isEqualTo(7);
      assertThat(decoded.getReplyTo()).isEqualTo("replyTo");
      assertThat(decoded.getSubject()).isEqualTo("Welcome");
      assertThat(decoded.getTemplateName()).isEqualTo("templateName");
      assertThat(decoded.getMarkdownBody()).isEqualTo("markdownBody");
      assertThat(decoded.getHtmlBody()).isEqualTo("htmlBody");
      assertThat(decoded.getTextBody()).isEqualTo("Hello");
      assertThat(decoded.getProviderName()).isEqualTo("providerName");
      assertThat(decoded.getBrandName()).isEqualTo("brandName");
      assertThat(decoded.getLocale()).isEqualTo("fr");
      assertThat(decoded.getCategory()).isEqualTo(EmailCategory.SECURITY);
      assertThat(decoded.getSendAfter()).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"));
      assertThat(decoded.getLinkedTableName()).isEqualTo("linkedTableName");
      assertThat(decoded.getLinkedRecordId()).isEqualTo("42");
      assertThat(decoded.getVariables()).containsEntry("count", 3).containsEntry("active", true);
      assertThat(EmailJobEffectTarget.decode(Map.of()).getLocale()).isEqualTo("en");
   }



   /*******************************************************************************
    ** Use core's jobs metadata and a real H2 jobs_job table with all its columns.
    ** Only the lookup table needs physical storage; no jobs worker runs here.
    *******************************************************************************/
   private void enableJobs() throws Exception
   {
      QInstance instance = QContext.getQInstance();
      instance.withSupplementalMetaData(new DurableJobsMetaData());
      new DurableJobsMetaDataProvider().defineAll(instance, "rdbms", null);
      instance.getAuthentication().withModuleCodeReference(new QCodeReference(OwnerAuthentication.class));
      QTableMetaData table = instance.getTable(DurableJobsTables.TABLE_JOB);
      List<String> columns = new ArrayList<>();
      for(var field : table.getFields().values())
      {
         String sqlType = switch(field.getType())
         {
            case INTEGER -> "INTEGER";
            case BOOLEAN -> "BOOLEAN";
            case DATE_TIME -> "TIMESTAMP";
            default -> "VARCHAR(10000)";
         };
         columns.add("\"" + field.getName() + "\" " + sqlType);
      }
      try(Connection connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) instance.getBackend("rdbms")))
      {
         connection.createStatement().execute("CREATE TABLE jobs_job (" + String.join(", ", columns) + ")");
      }
      instance.getTable(EmailTables.MESSAGE).withCustomizer(TableCustomizers.PRE_INSERT_RECORD, new QCodeReference(ObserveOwner.class));
      QContext.init(instance, new QSystemUserSession());
   }



   /*******************************************************************************
    ** Seed just the job identity and captured scope through JDBC, like a stored job.
    *******************************************************************************/
   private void job(String userId) throws Exception
   {
      try(Connection connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) QContext.getQInstance().getBackend("rdbms"));
         var statement = connection.prepareStatement("INSERT INTO jobs_job (\"id\", \"userId\", \"credentialScopeJson\", \"clientChannel\") VALUES (1, ?, ?, ?)"))
      {
         statement.setString(1, userId);
         statement.setString(2, "{\"tenant\":[\"allowed\"]}");
         statement.setString(3, "assistant");
         statement.executeUpdate();
      }
   }



   /*******************************************************************************
    ** The actual enqueue observes the owner and narrowed scope, then restores system.
    *******************************************************************************/
   @Test
   void sendsAsJobOwner() throws Exception
   {
      enableJobs();
      job("owner");
      QSession dispatcher = QContext.getQSession();
      assertThat(target.deliver(request("owner", 1, payload())).outcome()).isEqualTo(EffectResult.Outcome.SENT);
      assertThat(observedSession.getUser().getIdReference()).isEqualTo("owner");
      assertThat(observedSession.getClientChannel()).isEqualTo("assistant");
      assertThat(observedSession.getSecurityKeyValues().get("tenant")).containsExactly("allowed");
      assertThat(QContext.getQSession()).isSameAs(dispatcher);
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** A missing owner fails permanently instead of sending as the system user.
    *******************************************************************************/
   @Test
   void missingOwnerFails() throws Exception
   {
      enableJobs();
      job(null);
      assertOwnerFailure();
   }



   /*******************************************************************************
    ** Authentication refusal fails permanently.
    *******************************************************************************/
   @Test
   void authenticationFailureFails() throws Exception
   {
      enableJobs();
      job("denied");
      assertOwnerFailure();
   }



   /*******************************************************************************
    ** Core rejects a session for someone other than the persisted job owner.
    *******************************************************************************/
   @Test
   void wrongOwnerFails() throws Exception
   {
      enableJobs();
      job("wrong");
      assertOwnerFailure();
   }



   /*******************************************************************************
    ** A deleted job cannot fall back to the dispatcher identity.
    *******************************************************************************/
   @Test
   void missingJobFails() throws Exception
   {
      enableJobs();
      assertOwnerFailure();
   }



   /*******************************************************************************
    ** Refusal happens before the outbox sees the request and preserves the context.
    *******************************************************************************/
   private void assertOwnerFailure() throws Exception
   {
      QSession dispatcher = QContext.getQSession();
      var result = target.deliver(request("denied", 1, payload()));
      assertThat(result.outcome()).isEqualTo(EffectResult.Outcome.FAILED);
      assertThat(result.message()).startsWith("The job's owner can no longer send it:");
      assertThat(QContext.getQSession()).isSameAs(dispatcher);
      assertThat(observedSession).isNull();
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** A failed send also restores the system session after using the owner context.
    *******************************************************************************/
   @Test
   void ownerContextRestoredAfterException() throws Exception
   {
      enableJobs();
      job("owner");
      var request = request("collision", 1, payload());
      var sent = target.deliver(request);
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", Integer.valueOf(sent.providerRef())).withValue("idempotencyKey", "changed"));
      QSession dispatcher = QContext.getQSession();
      assertThat(target.deliver(request).outcome()).isEqualTo(EffectResult.Outcome.UNKNOWN);
      assertThat(QContext.getQSession()).isSameAs(dispatcher);
      assertThat(observedSession.getUser().getIdReference()).isEqualTo("owner");
   }



   /*******************************************************************************
    ** A failed lookup must never be interpreted as proof of no previous handoff.
    *******************************************************************************/
   @Test
   void reconcileStorageFailurePropagates() throws Exception
   {
      try(Connection connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) QContext.getQInstance().getBackend("rdbms")))
      {
         connection.createStatement().execute("ALTER TABLE email_message RENAME TO unavailable_message");
      }
      assertThatThrownBy(() -> target.reconcile(request("missing", null, payload()))).isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QException.class);
   }



   /*******************************************************************************
    ** The helper is optional, and producing email after jobs registers automatically.
    *******************************************************************************/
   @Test
   void registersOnlyWhenEnabled() throws Exception
   {
      QInstance disabled = QContext.getQInstance();
      EmailJobEffectTarget.register(disabled);
      assertThat(DurableJobsMetaData.of(disabled)).isNull();
      QInstance enabled = new QInstance().withSupplementalMetaData(new DurableJobsMetaData());
      enabled.addBackend(H2Support.backend());
      new DurableJobsMetaDataProvider().defineAll(enabled, "rdbms", null);
      new EmailQBitProducer().withQBitConfig(new EmailQBitConfig().withBackendName("rdbms")).produce(enabled);
      assertThat(DurableJobsMetaData.of(enabled).getEffectTargets()).containsOnlyKeys("email");
      assertThat(DurableJobsMetaData.of(enabled).getEffectTargets().get("email").getName()).isEqualTo(EmailJobEffectTarget.class.getName());
      EmailJobEffectTarget.register(enabled);
      assertThat(DurableJobsMetaData.of(enabled).getEffectTargets()).hasSize(1);
   }



   /*******************************************************************************
    ** Test authentication supplies current permissions and a wider live scope.
    *******************************************************************************/
   public static class OwnerAuthentication extends FullyAnonymousAuthenticationModule
   {
      /*******************************************************************************
       ** Simulate an active owner, revoked authentication, or mismatched identity.
       *******************************************************************************/
      @Override
      public QSession createAutomatedSessionForUser(QInstance instance, Serializable userId) throws QAuthenticationException
      {
         if("denied".equals(userId))
         {
            throw (new QAuthenticationException("Access revoked"));
         }
         return (H2Support.admin().withUser(new QUser().withIdReference("wrong".equals(userId) ? "someone-else" : String.valueOf(userId)))
            .withSecurityKeyValues(Map.of("tenant", List.of("allowed", "new"))));
      }
   }



   /*******************************************************************************
    ** Observe the session inside the real send without adding production test seams.
    *******************************************************************************/
   public static class ObserveOwner implements TableCustomizerInterface
   {
      /*******************************************************************************
       ** Email messages have no built-in user field, so observe at the insert hook.
       *******************************************************************************/
      @Override
      public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean preview, Optional<List<QRecord>> oldRecords)
      {
         observedSession = QContext.getQSession();
         return (records);
      }
   }
}
