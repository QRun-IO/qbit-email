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

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qbits.email.app.EmailHome;
import com.kingsrook.qbits.email.bridge.EmailTemplatedMessageInput;
import com.kingsrook.qbits.email.delivery.EmailDispatcher;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.phi.EmailBodyEncryption;
import com.kingsrook.qbits.email.phi.EmailClassifications;
import com.kingsrook.qbits.email.phi.EmailValueTokens;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailVersions;
import com.kingsrook.qqq.backend.core.actions.messaging.SendMessageAction;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Canary egress across every templated entry path, retry, dead letter, export,
 ** home and Log4j. Transport receives plaintext; no other inspected surface does.
 *******************************************************************************/
class PhiCanaryEgressTest
{
   private String canary;
   private QRecord version;
   private QTableMetaData patient;

   /*******************************************************************************
    ** Plant PHI in an application table and configure only classification overrides.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      canary = "CANARY-PHI-" + UUID.randomUUID();
      H2Support.setup(c -> c.withMaxAttempts(2).withSecretResolver(PhiCanaryEgressTest::secret)
         .withClassificationOverrides(Map.of("test_patient.name", EmailClassifications.Level.PHI)));
      H2Support.provider(25);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("phiAllowed", true));
      patient = new QTableMetaData().withName("test_patient").withBackendName("rdbms").withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withBackendName("id"))
         .withField(new QFieldMetaData("name", QFieldType.STRING).withBackendName("name"));
      QContext.getQInstance().addTable(patient);
      try(Connection connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) QContext.getQInstance().getBackend("rdbms")))
      {
         connection.createStatement().execute("CREATE TABLE test_patient (id INTEGER PRIMARY KEY, name VARCHAR(200))");
         try(var statement = connection.prepareStatement("INSERT INTO test_patient (id, name) VALUES (1, ?)"))
         {
            statement.setString(1, canary);
            statement.executeUpdate();
         }
      }
      version = EmailTemplatesTest.draft("phi_notice", "MARKDOWN", "[{\"name\":\"patient\",\"type\":\"RECORD\",\"required\":true}]",
         "{\"patient\":{\"name\":\"Example\"}}", "# {{patient.name}}\n\nPrivate message");
      SystemWrites.updateOne(EmailTables.VERSION, new QRecord().withValue("id", version.getValue("id")).withValue("subject", "{{patient.name}}"));
   }

   /*******************************************************************************
    ** Fixed non-production keys, resolved in memory only.
    *******************************************************************************/
   private static String secret(String name)
   {
      return (switch(name)
      {
         case "QQQ_EMAIL_BODY_KEY" -> "test-only-body-key";
         case "QQQ_AUDIT_HMAC_KEY" -> Base64.getEncoder().encodeToString("test-only-audit-key-32-bytes-long!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
         default -> null;
      });
   }

   /*******************************************************************************
    ** Clear request-local state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** The record is fetched under the existing sender session at send time.
    *******************************************************************************/
   private EmailRequest request()
   {
      return (new EmailRequest().withTemplateName("phi_notice").withTo("person@example.com").withRecordVariable("patient", "test_patient", 1));
   }

   /*******************************************************************************
    ** No unapproved PHI request can leave any outbox or attempt row.
    *******************************************************************************/
   @Test
   void unapprovedRefusedWithoutStorage() throws Exception
   {
      EmailVersions.activate(version.getValueInteger("id"));
      var refused = EmailService.send(request());
      assertThat(refused.status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(refused.reasons()).contains("The variable patient.name is PHI; this template is not approved for PHI.");
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
      assertThat(H2Support.all(EmailTables.ATTEMPT)).isEmpty();
      noCanary(refused.toString());
   }

   /*******************************************************************************
    ** Every path encrypts, redacts, retries and dead-letters without egress.
    *******************************************************************************/
   @Test
   void canaryAcrossServiceProcessBridgeAndOperationalSurfaces() throws Exception
   {
      EmailVersions.approve(version.getValueInteger("id"));
      EmailVersions.activate(version.getValueInteger("id"));
      List<String> logs = new ArrayList<>();
      AbstractAppender capture = new AbstractAppender("phi-canary", null, null, true, null)
      {
         @Override
         public void append(LogEvent event)
         {
            logs.add(event.getMessage().getFormattedMessage());
            if(event.getThrown() != null)
            {
               logs.add(event.getThrown().toString());
            }
         }
      };
      capture.start();
      LoggerContext context = (LoggerContext) LogManager.getContext(false);
      var root = context.getConfiguration().getRootLogger();
      var priorLevel = root.getLevel();
      root.addAppender(capture, org.apache.logging.log4j.Level.ALL, null);
      root.setLevel(org.apache.logging.log4j.Level.INFO);
      context.updateLoggers();
      try
      {
         var sent = EmailService.send(request());
         assertThat(sent.status()).isEqualTo(EmailSendResult.Status.QUEUED);
         var input = new RunProcessInput();
         input.setProcessName(EmailProcesses.SEND_TEMPLATED);
         input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
         input.addValue("templateName", "phi_notice");
         input.addValue("to", "person@example.com");
         input.addValue("recordVariablesJson", "{\"patient\":{\"table\":\"test_patient\",\"id\":1}}");
         var process = new RunProcessAction().execute(input);
         assertThat(process.getException()).isEmpty();
         assertThat(process.getValues().get("status")).isEqualTo("QUEUED");
         noCanary(process.getValues().toString());
         new SendMessageAction().execute(new EmailTemplatedMessageInput().withEmailRequest(request()).withMessagingProviderName("qbitEmail"));
         assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(3);
         var dispatcher = new EmailDispatcher(new com.kingsrook.qbits.email.transport.EmailTransportRegistry().withTransport("SMTP", (envelope, settings) ->
         {
            assertThat(envelope.getSubject()).isEqualTo(canary);
            assertThat(envelope.getHtmlBody()).contains(canary);
            throw new com.kingsrook.qbits.email.transport.EmailTransportException("Provider echoed " + canary, false);
         }));
         for(QRecord message : H2Support.all(EmailTables.MESSAGE))
         {
            Integer id = message.getValueInteger("id");
            assertThat(dispatcher.sendNow(id)).isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
            assertThat(dispatcher.sendNow(id)).isEqualTo(EmailDispatcher.SendResult.DEAD_LETTER);
            QRecord raw = EmailStore.get(EmailTables.MESSAGE, id, null);
            for(String field : List.of("subject", "htmlBody", "textBody"))
            {
               assertThat(raw.getValueString(field)).startsWith("enc:v1:").doesNotContain(canary);
               assertThat(EmailBodyEncryption.decrypt(raw.getValueString(field), field, raw.getValueString("id"))).contains(canary);
            }
            assertThat(raw.getValueString("variablesSnapshot")).contains("hmac:v1:");
            for(var value : raw.getValues().entrySet())
            {
               if(!List.of("subject", "htmlBody", "textBody").contains(value.getKey()))
               {
                  noCanary(String.valueOf(value.getValue()));
               }
            }
         }
         for(String table : List.of(EmailTables.MESSAGE, EmailTables.ATTEMPT))
         {
            var exported = new QueryAction().execute(new QueryInput(table).withShouldFetchHeavyFields(true).withShouldGenerateDisplayValues(true));
            noCanary(exported.getRecords().toString());
            if(table.equals(EmailTables.MESSAGE))
            {
               assertThat(exported.getRecords()).allSatisfy(row ->
               {
                  assertThat(row.getValueString("subject")).isEqualTo("[encrypted]");
                  assertThat(row.getValueString("htmlBody")).isEqualTo("[encrypted]");
                  assertThat(row.getValueString("textBody")).isEqualTo("[encrypted]");
               });
            }
         }
         noCanary(EmailHome.overview().getHtml());
         assertThat(EmailHome.overview().getHtml()).contains("Secure message");
         noCanary(EmailHome.hourly().getSublabel());
         assertThat(logs).isNotEmpty();
         logs.forEach(this::noCanary);
      }
      finally
      {
         root.removeAppender(capture.getName());
         root.setLevel(priorLevel);
         context.updateLoggers();
         capture.stop();
      }
   }

   /*******************************************************************************
    ** Checking every five-character window detects every longer substring too.
    *******************************************************************************/
   private void noCanary(String output)
   {
      for(int i = 0; i <= canary.length() - 5; i++)
      {
         assertThat(output).doesNotContain(canary.substring(i, i + 5));
      }
   }

   /*******************************************************************************
    ** Provider attestation, tracking, encryption key and category are mandatory.
    *******************************************************************************/
   @Test
   void phiPolicyAndKeyRefusals() throws Exception
   {
      EmailVersions.approve(version.getValueInteger("id"));
      EmailVersions.activate(version.getValueInteger("id"));
      EmailRuntime.config().withSecretResolver(name -> null);
      assertThat(EmailService.send(request()).reasons()).contains("PHI email needs QQQ_EMAIL_BODY_KEY");
      EmailRuntime.config().withSecretResolver(PhiCanaryEgressTest::secret);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("trackOpens", true));
      assertThat(EmailService.send(request()).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("trackOpens", false).withValue("phiAllowed", false));
      assertThat(EmailService.send(request()).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(EmailService.send(request().withCategory(com.kingsrook.qbits.email.outbox.EmailCategory.MARKETING)).reasons().toString()).contains("Template category cannot be overridden");
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }

   /*******************************************************************************
    ** Masked fields remain masked when rendered, even for an approved template.
    *******************************************************************************/
   @Test
   void recordMaskingAndProviderChangesAreRespected() throws Exception
   {
      EmailVersions.approve(version.getValueInteger("id"));
      EmailVersions.activate(version.getValueInteger("id"));
      patient.getField("name").withFieldSecurityLock(new com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock()
         .withSecurityKeyType("clinical").withDefaultBehavior(com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock.Behavior.DENY)
         .withOverrideValues(List.of("allowed")));
      var result = EmailService.send(request());
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      var row = EmailStore.get(EmailTables.MESSAGE, result.messageId(), null);
      assertThat(EmailBodyEncryption.decrypt(row.getValueString("subject"), "subject", row.getValueString("id"))).isEqualTo("************");
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("trackClicks", true));
      var dispatcher = new EmailDispatcher(new com.kingsrook.qbits.email.transport.EmailTransportRegistry().withTransport("SMTP", (envelope, settings) ->
      {
         throw new AssertionError("Unsafe provider received PHI");
      }));
      assertThat(dispatcher.sendNow(result.messageId())).isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
      assertThat(EmailService.resend(result.messageId()).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("trackClicks", false));
      var resent = EmailService.resend(result.messageId());
      assertThat(resent.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord copy = EmailStore.get(EmailTables.MESSAGE, resent.messageId(), null);
      assertThat(copy.getValueBoolean("phiApproved")).isTrue();
      assertThat(copy.getValueString("subject")).isNotEqualTo(row.getValueString("subject"));
      assertThat(EmailBodyEncryption.decrypt(copy.getValueString("subject"), "subject", copy.getValueString("id"))).isEqualTo("************");
      assertThatThrownBy(() -> EmailBodyEncryption.decrypt(copy.getValueString("subject"), "subject", row.getValueString("id"))).hasMessageContaining("could not be opened");
      assertThat(copy.getValue("templateVersionId")).isEqualTo(row.getValue("templateVersionId"));
   }

   /*******************************************************************************
    ** Ad-hoc callers cannot turn a ciphertext string into a decryption request.
    *******************************************************************************/
   @Test
   void ciphertextPrefixIsNotAuthority() throws Exception
   {
      String encrypted = EmailBodyEncryption.encrypt(canary, "subject", "test");
      var result = EmailService.send(new EmailRequest().withTo("p@example.com").withSubject(encrypted).withTextBody(encrypted));
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      var dispatcher = new EmailDispatcher(new com.kingsrook.qbits.email.transport.EmailTransportRegistry().withTransport("SMTP", (envelope, settings) ->
      {
         assertThat(envelope.getSubject()).isEqualTo(encrypted);
         assertThat(envelope.getTextBody()).isEqualTo(encrypted);
         return new com.kingsrook.qbits.email.transport.EmailTransportResult("safe");
      }));
      assertThat(dispatcher.sendNow(result.messageId())).isEqualTo(EmailDispatcher.SendResult.SENT);
   }

   /*******************************************************************************
    ** Token bindings, canonicalization, sentinels and authenticated encryption.
    *******************************************************************************/
   @Test
   void tokensAndEncryption()
   {
      List<String> binding = List.of("email", "template", "variable", "123");
      String token = EmailValueTokens.forContext(binding, canary);
      assertThat(token).startsWith("hmac:v1:");
      assertThat(EmailValueTokens.forContext(binding, canary)).isEqualTo(token);
      assertThat(EmailValueTokens.verifyContext(token, binding, canary)).isTrue();
      assertThat(EmailValueTokens.verifyContext(token, List.of("different"), canary)).isFalse();
      assertThat(EmailValueTokens.forContext(binding, true)).isEqualTo("changed:v1");
      assertThat(EmailValueTokens.forContext(binding, "aaaaaa")).isEqualTo("changed:v1");
      assertThat(EmailValueTokens.verifyContext("shred:v1", binding, true)).isFalse();
      assertThat(EmailValueTokens.verifyContext("changed:v1", binding, true)).isFalse();
      String record = EmailValueTokens.forRecordField(patient, patient.getField("name"), 1, canary);
      assertThat(EmailValueTokens.verifyRecordField(record, patient, patient.getField("name"), 1, canary)).isTrue();
      assertThat(EmailValueTokens.verifyRecordField(record, patient, patient.getField("name"), 2, canary)).isFalse();
      assertThat(EmailValueTokens.canonical(new BigDecimal("1.2000"))).isEqualTo("1.2");
      assertThat(EmailValueTokens.canonical(Instant.parse("2026-10-13T12:00:00Z"))).isEqualTo("2026-10-13T12:00:00Z");
      String encrypted = EmailBodyEncryption.encrypt(canary, "subject", "test");
      assertThat(EmailBodyEncryption.encrypt(canary, "subject", "test")).isNotEqualTo(encrypted);
      assertThat(EmailBodyEncryption.decrypt(encrypted, "subject", "test")).isEqualTo(canary);
      assertThatThrownBy(() -> EmailBodyEncryption.decrypt(encrypted.substring(0, encrypted.length() - 4) + "AAAA", "subject", "test")).hasMessageContaining("could not be opened");
      EmailRuntime.config().withSecretResolver(name -> null);
      assertThat(EmailValueTokens.forContext(binding, canary)).isEqualTo("changed:v1");
      assertThatThrownBy(() -> EmailBodyEncryption.encrypt(canary, "subject", "test")).hasMessageContaining("QQQ_EMAIL_BODY_KEY");
   }

   /*******************************************************************************
    ** Record projection follows parsed references and snapshots fail closed.
    *******************************************************************************/
   @Test
   void referencedFieldsOnlyAndUnclassifiedTokens() throws Exception
   {
      EmailRuntime.config().withClassificationOverrides(Map.of());
      patient.withField(new QFieldMetaData("unused", QFieldType.STRING).withBackendName("unused"));
      try(Connection connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) QContext.getQInstance().getBackend("rdbms")))
      {
         connection.createStatement().execute("ALTER TABLE test_patient ADD unused VARCHAR(200) DEFAULT 'never-load-this'");
      }
      SystemWrites.updateOne(EmailTables.VERSION, new QRecord().withValue("id", version.getValue("id"))
         .withValue("subject", "Notice").withValue("bodySource", "{{#if patient.name}}Hello {{patient.name}}{{/if}}"));
      EmailVersions.activate(version.getValueInteger("id"));
      var result = EmailService.send(request());
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord row = EmailStore.get(EmailTables.MESSAGE, result.messageId(), null);
      assertThat(row.getValueString("htmlBody")).contains(canary);
      assertThat(row.getValueString("variablesSnapshot")).contains("name", "hmac:v1:").doesNotContain(canary, "unused", "never-load-this", "\"id\"");
   }

}
