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


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Durable handoff semantics and stored identity using the real H2 outbox.
 *******************************************************************************/
class EmailEffectsTest
{
   /*******************************************************************************
    ** Prepare a provider without opening a transport connection.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(config ->
      {
      });
      H2Support.provider(25);
   }



   /*******************************************************************************
    ** Release the per-thread context.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** A plain message uses the configured sender.
    *******************************************************************************/
   private EmailRequest request()
   {
      return (new EmailRequest().withTo("person@example.com").withSubject("Effect").withTextBody("Body"));
   }



   /*******************************************************************************
    ** Replays return the same persisted reference without another row.
    *******************************************************************************/
   @Test
   void handoffDeduplicatesAndLookupsReturnCurrentReferences() throws Exception
   {
      String key = "job:2:row:email";
      EmailRequest request = request();
      var first = EmailEffects.send(request, key);
      var second = EmailEffects.send(request(), key);
      assertThat(request.getIdempotencyKey()).isEqualTo(key);
      assertThat(first.outcome()).isEqualTo(EmailEffectOutcome.QUEUED_OR_DUPLICATE);
      assertThat(second).isEqualTo(first);
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
      String token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
      assertThat(first.message().rfcMessageId()).isEqualTo("<" + token + "@example.com>");
      assertThat(first.message().status()).isEqualTo("QUEUED");
      assertThat(first.message().providerMessageId()).isNull();
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", first.message().id())
         .withValue("status", "SENT").withValue("providerMessageId", "provider-reference"));
      QContext.init(QContext.getQInstance(), new QSession().withPermissions(List.of()));
      var found = EmailEffects.findByIdempotencyKey(key).orElseThrow();
      assertThat(found.id()).isEqualTo(first.message().id());
      assertThat(found.status()).isEqualTo("SENT");
      assertThat(found.providerMessageId()).isEqualTo("provider-reference");
      assertThat(EmailEffects.findByRfcMessageId(first.message().rfcMessageId())).contains(found);
      assertThat(EmailEffects.findByIdempotencyKey("missing")).isEmpty();
      assertThat(EmailEffects.findByRfcMessageId("<missing@example.com>")).isEmpty();
      assertThat(EmailEffects.findByIdempotencyKey(null)).isEmpty();
      assertThat(EmailEffects.findByRfcMessageId(null)).isEmpty();
      assertThat(EmailEffects.findByIdempotencyKey(" ")).isEmpty();
      assertThat(EmailEffects.findByRfcMessageId(" ")).isEmpty();
   }



   /*******************************************************************************
    ** Conflicting or unusable requests never enqueue or change an existing key.
    *******************************************************************************/
   @Test
   void refusesConflictingKeysAndInvalidRequests() throws Exception
   {
      EmailRequest conflict = request().withIdempotencyKey("other");
      assertThat(EmailEffects.send(conflict, "effect").outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      assertThat(conflict.getIdempotencyKey()).isEqualTo("other");
      assertThat(EmailEffects.send(request(), null).outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      assertThat(EmailEffects.send(request(), " ").outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      assertThat(EmailEffects.send(request(), "a".repeat(501)).outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      assertThat(EmailEffects.send(null, "effect").outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      var invalid = EmailEffects.send(request().withSubject(null), "invalid");
      assertThat(invalid.outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      assertThat(invalid.message()).isNull();
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
      assertThat(EmailEffects.send(request().withIdempotencyKey("same"), "same").outcome()).isEqualTo(EmailEffectOutcome.QUEUED_OR_DUPLICATE);
   }



   /*******************************************************************************
    ** A suppressed audit row stays refused on replay, including duplicate results.
    *******************************************************************************/
   @Test
   void suppressedRecipientsAreRefusedOnEveryHandoff() throws Exception
   {
      H2Support.insert(EmailTables.SUPPRESSION, new QRecord().withValue("address", "person@example.com")
         .withValue("scope", "ALL").withValue("reason", "HARD_BOUNCE"));
      var first = EmailEffects.send(request(), "suppressed");
      var second = EmailEffects.send(request(), "suppressed");
      assertThat(first.outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
      assertThat(first.message().status()).isEqualTo("SUPPRESSED");
      assertThat(second).isEqualTo(first);
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** Never acknowledge an uncommitted or deferred handoff to the jobs dispatcher.
    *******************************************************************************/
   @Test
   void callerTransactionsAreRefused() throws Exception
   {
      try(QBackendTransaction transaction = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         assertThat(EmailEffects.send(request().withTransaction(transaction, "rdbms"), "same-backend").outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
         assertThat(EmailEffects.send(request().withTransaction(transaction, "other"), "other-backend").outcome()).isEqualTo(EmailEffectOutcome.REFUSED);
         transaction.commit();
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** Database uniqueness failures must escape, rather than become terminal refusals.
    *******************************************************************************/
   @Test
   void storageFailurePropagatesAndRfcIdIsUnique() throws Exception
   {
      var first = EmailEffects.send(request(), "collision");
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", first.message().id()).withValue("idempotencyKey", "changed"));
      assertThatThrownBy(() -> EmailEffects.send(request(), "collision")).isInstanceOf(QException.class);
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
      assertThat(EmailEffects.findByIdempotencyKey("collision")).isEmpty();
   }



   /*******************************************************************************
    ** Identity hashing is deterministic, ignores display names and has a safe fallback.
    *******************************************************************************/
   @Test
   void generationAndUnkeyedMessages() throws Exception
   {
      assertThat(EmailService.messageId("key", "Display <sender@EXAMPLE.com>"))
         .isEqualTo(EmailService.messageId("key", "sender@example.com"));
      for(String from : new String[] {null, "local", "broken <", "sender@[]"})
      {
         assertThat(EmailService.messageId("key", from)).matches("<[0-9a-f]{32}@qbit-email\\.local>");
      }
      var first = EmailService.send(request());
      var second = EmailService.send(request());
      var rows = H2Support.all(EmailTables.MESSAGE);
      assertThat(rows).hasSize(2);
      assertThat(rows.get(0).getValueString("rfcMessageId")).matches("<[0-9a-f]{32}@example\\.com>")
         .isNotEqualTo(rows.get(1).getValueString("rfcMessageId"));
      assertThat(first.messageId()).isNotEqualTo(second.messageId());
   }
}
