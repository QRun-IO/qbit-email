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

package com.kingsrook.qbits.email.tracking;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.ProcessRunner;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/*******************************************************************************
 ** Durable processing, rollback, suppression and race acceptance on real H2.
 *******************************************************************************/
class EmailInboxProcessorTest
{
   private QInstance instance;

   /*******************************************************************************
    ** Use a fixed clock for retry and provider event times.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      instance = H2Support.setup(c -> c.withSecretResolver(name -> SECRET));
      EmailRuntime.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
   }

   /*******************************************************************************
    ** Restore host-independent runtime state.
    *******************************************************************************/
   @AfterEach
   void teardown()
   {
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** Ingest the same authenticated payload boundary used by HTTP.
    *******************************************************************************/
   private static void receive(QRecord provider, String id, String body) throws Exception
   {
      assertThat(EmailTrackingStore.receive(provider, id, null, body.getBytes(StandardCharsets.UTF_8), null)).isEqualTo(EmailTrackingStore.Receipt.RECEIVED);
   }

   /*******************************************************************************
    ** All provider fixtures produce hard-bounce suppression and masked diagnostics.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({"SES,ses-bounce.json,2", "POSTMARK,postmark-bounce.json,1", "SENDGRID,sendgrid-batch.json,2", "MAILGUN,mailgun-failed.json,1"})
   void realisticFixtures(String kind, String fixture, int count) throws Exception
   {
      QRecord provider = provider(kind);
      QRecord message = message(provider);
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", message.getValue("id")).withValue("toAddresses", "recipient@example.com, second@example.com"));
      receive(provider, "fixture", resource("/webhooks/" + fixture));
      assertThat(new EmailInboxProcessor().runOnce()).isEqualTo(1);
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(count);
      assertThat(H2Support.all(EmailTables.INBOX).getFirst().getValueString("status")).isEqualTo("PROCESSED");
      QRecord updated = EmailStore.get(EmailTables.MESSAGE, message.getValueInteger("id"), null);
      assertThat(updated.getValueString("status")).isEqualTo("BOUNCED");
      assertThat(updated.getValueInstant("bouncedDate")).isNotNull();
      for(QRecord suppression : H2Support.all(EmailTables.SUPPRESSION))
      {
         assertThat(suppression.getValueString("scope")).isEqualTo("ALL");
         assertThat(suppression.getValueString("reason")).isEqualTo("HARD_BOUNCE");
         assertThat(suppression.getValueInteger("sourceMessageId")).isEqualTo(message.getValueInteger("id"));
         assertThat(suppression.getValueInteger("sourceEventId")).isPositive();
      }
      assertThat(H2Support.all(EmailTables.EVENT)).allSatisfy(event -> assertThat(event.getValueString("detail")).doesNotContain("recipient@example.com", "second@example.com"));
      assertThat(new EmailInboxProcessor().runOnce()).isZero();
   }

   /*******************************************************************************
    ** Soft bounces do not suppress; complaint dominates all later delivery events.
    *******************************************************************************/
   @Test
   void statusOrderSuppressionUpsertsAndOverlappingBatches() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = message(provider);
      JSONObject event = new JSONObject().put("email", "Recipient@EXAMPLE.COM").put("qqq_email_id", message.getValueString("id"))
         .put("timestamp", NOW.getEpochSecond()).put("event", "bounce").put("type", "blocked").put("sg_event_id", "soft");
      receive(provider, "one", new JSONArray().put(event).toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("status")).isEqualTo("SENT");
      event.put("type", "bounce").put("sg_event_id", "hard");
      receive(provider, "two", new JSONArray().put(event).toString());
      new EmailInboxProcessor().runOnce();
      event.put("event", "spamreport").put("sg_event_id", "complaint");
      receive(provider, "three", new JSONArray().put(event).toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("status")).isEqualTo("COMPLAINED");
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueInstant("complainedDate")).isEqualTo(NOW);
      event.put("event", "delivered").put("sg_event_id", "late-delivery");
      JSONArray overlap = new JSONArray(resource("/webhooks/sendgrid-batch.json")).put(event);
      receive(provider, "four", overlap.toString());
      new EmailInboxProcessor().runOnce();
      receive(provider, "five", overlap.toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.INBOX).getLast().getValueString("status")).isEqualTo("IGNORED");
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(6);
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("status")).isEqualTo("COMPLAINED");
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
      assertThat(H2Support.all(EmailTables.SUPPRESSION).getFirst().getValueString("reason")).isEqualTo("COMPLAINT");
   }

   /*******************************************************************************
    ** Provider unsubscribes cannot suppress account/security mail.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"MARKETING", "NOTIFICATION", "TRANSACTIONAL", "SECURITY"})
   void categoryRule(String category) throws Exception
   {
      QRecord provider = provider("POSTMARK");
      QRecord message = message(provider);
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", message.getValue("id")).withValue("category", category));
      JSONObject event = new JSONObject(resource("/webhooks/postmark-delivery.json")).put("RecordType", "SubscriptionChange").put("SuppressSending", true);
      receive(provider, "unsubscribe", event.toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
      if(List.of("MARKETING", "NOTIFICATION").contains(category))
      {
         assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
         assertThat(H2Support.all(EmailTables.SUPPRESSION).getFirst().getValueString("scope")).isEqualTo(category);
      }
      else
      {
         assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
      }
      receive(provider, "reactivation", event.put("SuppressSending", false).toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
   }

   /*******************************************************************************
    ** Tracking flags apply before matching, and protected mail never stores tracking.
    *******************************************************************************/
   @Test
   void opensAndClicksOnlyWhenEnabled() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = message(provider);
      JSONArray batch = new JSONArray();
      for(String type : List.of("open", "click"))
      {
         batch.put(new JSONObject().put("event", type).put("email", "recipient@example.com").put("qqq_email_id", message.getValue("id"))
            .put("sg_event_id", type).put("url", "https://example.com/link").put("timestamp", NOW.getEpochSecond()));
      }
      receive(provider, "off", batch.toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue("trackOpens", true).withValue("trackClicks", true));
      receive(provider, "on", batch.toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(2);
      assertThat(H2Support.all(EmailTables.EVENT).getFirst().getValueString("url")).isNull();
      assertThat(H2Support.all(EmailTables.EVENT).getLast().getValueString("url")).isEqualTo("https://example.com/link");
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", message.getValue("id")).withValue("phiApproved", true));
      batch.getJSONObject(0).put("sg_event_id", "protected-open");
      batch.getJSONObject(1).put("sg_event_id", "protected-click");
      receive(provider, "protected", batch.toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(2);
   }

   /*******************************************************************************
    ** Partial batch failure rolls back events, suppression and message status.
    *******************************************************************************/
   @Test
   void retriesSixTimesThenReviewReprocess() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = message(provider);
      receive(provider, "retry", "[{not json");
      for(int i = 1; i <= 6; i++)
      {
         assertThat(new EmailInboxProcessor().runOnce()).isZero();
         QRecord row = H2Support.all(EmailTables.INBOX).getFirst();
         assertThat(row.getValueInteger("attempts")).isEqualTo(i);
         assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
         assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
         assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("status")).isEqualTo("SENT");
         if(i < 6)
         {
            assertThat(row.getValueString("status")).isEqualTo("PENDING");
            assertThat(new EmailInboxProcessor().runOnce()).isZero();
            EmailRuntime.setClock(Clock.fixed(row.getValueInstant("nextAttemptDate"), ZoneOffset.UTC));
         }
      }
      QRecord failed = H2Support.all(EmailTables.INBOX).getFirst();
      assertThat(failed.getValueString("status")).isEqualTo("FAILED");
      var lines = ProcessRunner.run(EmailProcesses.REPROCESS, failed.getValue("id"));
      assertThat(lines.review().toString()).contains("will be processed");
      assertThat(lines.result().toString()).contains("PENDING");
      assertThat(EmailInboxProcessor.reprocess(failed.getValueInteger("id"))).isFalse();
      assertThat(EmailInboxProcessor.reprocess(9999)).isFalse();
      SystemWrites.updateOne(EmailTables.INBOX, new QRecord().withValue("id", failed.getValue("id")).withValue("payloadJson", resource("/webhooks/sendgrid-batch.json")));
      new EmailProcesses.ScheduledStep().run(new RunBackendStepInput().withProcessName(EmailProcesses.PROCESS_WEBHOOKS), new RunBackendStepOutput());
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(2);
   }

   /*******************************************************************************
    ** A foreign event never blocks a real hard bounce in the same SendGrid batch; the
    ** foreign one retries, then the row finishes PROCESSED with a note, not FAILED.
    *******************************************************************************/
   @Test
   void unmatchedEventDoesNotBlockMatchedBounce() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = message(provider);
      JSONArray batch = new JSONArray(resource("/webhooks/sendgrid-batch.json"));
      batch.put(new JSONObject().put("email", "stranger@example.com").put("timestamp", NOW.getEpochSecond()).put("event", "bounce")
         .put("type", "bounce").put("sg_event_id", "foreign").put("sg_message_id", "someone-elses-message.filter009"));
      receive(provider, "mixed", batch.toString());
      for(int i = 1; i <= 6; i++)
      {
         assertThat(new EmailInboxProcessor().runOnce()).isEqualTo(1);
         QRecord row = H2Support.all(EmailTables.INBOX).getFirst();
         assertThat(row.getValueInteger("attempts")).isEqualTo(i);
         assertThat(H2Support.all(EmailTables.EVENT)).hasSize(2);
         assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
         assertThat(EmailStore.get(EmailTables.MESSAGE, message.getValueInteger("id"), null).getValueString("status")).isEqualTo("BOUNCED");
         if(i < 6)
         {
            assertThat(row.getValueString("status")).isEqualTo("PENDING");
            assertThat(row.getValueString("lastError")).isEqualTo("1 event did not match a message");
            EmailRuntime.setClock(Clock.fixed(row.getValueInstant("nextAttemptDate"), ZoneOffset.UTC));
         }
      }
      QRecord done = H2Support.all(EmailTables.INBOX).getFirst();
      assertThat(done.getValueString("status")).isEqualTo("PROCESSED");
      assertThat(done.getValueString("lastError")).isEqualTo("1 event did not match a message");
      assertThat(done.getValueInstant("processedDate")).isNotNull();
      assertThat(done.getValue("nextAttemptDate")).isNull();
   }

   /*******************************************************************************
    ** Only a recipient of the matched message can be suppressed, and an SES bounce
    ** without bouncedRecipients is skipped rather than widened to all destinations.
    *******************************************************************************/
   @Test
   void suppressionOnlyForRecipientsOfTheMessage() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = message(provider);
      JSONObject event = new JSONObject().put("event", "bounce").put("type", "bounce").put("email", "intruder@example.com")
         .put("qqq_email_id", message.getValueString("id")).put("timestamp", NOW.getEpochSecond()).put("sg_event_id", "intruder");
      receive(provider, "intruder", new JSONArray().put(event).toString());
      assertThat(new EmailInboxProcessor().runOnce()).isEqualTo(1);
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
      QRecord row = H2Support.all(EmailTables.INBOX).getFirst();
      assertThat(row.getValueString("status")).isEqualTo("IGNORED");
      assertThat(row.getValueString("lastError")).contains("1 event was skipped");
      assertThat(row.getValueString("lastError")).doesNotContain("intruder");

      QRecord ses = provider("SES");
      QRecord sesMessage = message(ses);
      JSONObject envelope = new JSONObject(resource("/webhooks/ses-bounce.json"));
      JSONObject body = new JSONObject(envelope.getString("Message"));
      body.getJSONObject("mail").getJSONArray("headers").getJSONObject(0).put("value", sesMessage.getValueString("id"));
      body.getJSONObject("bounce").remove("bouncedRecipients");
      receive(ses, "no-recipients", envelope.put("Message", body.toString()).toString());
      assertThat(new EmailInboxProcessor().runOnce()).isEqualTo(1);
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
      assertThat(EmailStore.get(EmailTables.MESSAGE, sesMessage.getValueInteger("id"), null).getValueString("status")).isEqualTo("SENT");
   }

   /*******************************************************************************
    ** Our custom id cannot match another provider's message; callbacks may race SENT.
    *******************************************************************************/
   @Test
   void providerBoundaryAndSendingRace() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = message(provider("POSTMARK"));
      JSONObject event = new JSONObject().put("event", "delivered").put("qqq_email_id", message.getValue("id"))
         .put("email", "recipient@example.com").put("sg_event_id", "cross-provider");
      receive(provider, "cross", new JSONArray().put(event).toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      QRecord actual = message(provider);
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", actual.getValue("id")).withValue("status", "SENDING"));
      receive(provider, "racing", new JSONArray().put(event.put("qqq_email_id", actual.getValue("id")).put("sg_event_id", "racing")).toString());
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", actual.getValue("id")).withValue("status", "SENT"));
      EmailRuntime.setClock(Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));
      new EmailInboxProcessor().runOnce();
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
      assertThat(EmailStore.get(EmailTables.MESSAGE, actual.getValueInteger("id"), null).getValueString("status")).isEqualTo("DELIVERED");
   }

   /*******************************************************************************
    ** Custom arguments support the entire integer primary-key range.
    *******************************************************************************/
   @Test
   void fullIntegerMessageId() throws Exception
   {
      QRecord provider = provider("SENDGRID");
      QRecord message = SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("id", 2000000000)
         .withValue("providerId", provider.getValue("id")).withValue("status", "SENT").withValue("category", "NOTIFICATION")
         .withValue("toAddresses", "recipient@example.com"));
      assertThat(message.getValueInteger("id")).isEqualTo(2000000000);
      JSONObject event = new JSONObject().put("event", "delivered").put("qqq_email_id", "2000000000")
         .put("email", "recipient@example.com").put("sg_event_id", "large-message-id");
      receive(provider, "large", new JSONArray().put(event).toString());
      assertThat(new EmailInboxProcessor().runOnce()).isEqualTo(1);
      assertThat(H2Support.all(EmailTables.EVENT).getFirst().getValueInteger("messageId")).isEqualTo(2000000000);
   }

   /*******************************************************************************
    ** Two processor replicas observe one receipt but only one applies it.
    *******************************************************************************/
   @Test
   void concurrentProcessors() throws Exception
   {
      QRecord provider = provider("POSTMARK");
      message(provider);
      receive(provider, "race", resource("/webhooks/postmark-bounce.json"));
      try(var workers = Executors.newFixedThreadPool(2))
      {
         var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
         for(int i = 0; i < 2; i++)
         {
            tasks.add(workers.submit(() ->
            {
               QContext.init(instance, H2Support.admin());
               try
               {
                  return (new EmailInboxProcessor().runOnce());
               }
               finally
               {
                  QContext.clear();
               }
            }));
         }
         assertThat(tasks.get(0).get(20, TimeUnit.SECONDS) + tasks.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(1);
      }
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
   }

   /*******************************************************************************
    ** Core's optional alert sink receives a day-scoped warning after threshold.
    *******************************************************************************/
   @Test
   void complaintAlertThreshold() throws Exception
   {
      QRecord provider = provider("POSTMARK");
      message(provider);
      Alerts.signals.clear();
      com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData.ofOrWithNew(instance)
         .withSink(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(Alerts.class));
      for(int i = 0; i < 5; i++)
      {
         JSONObject event = new JSONObject(resource("/webhooks/postmark-bounce.json")).put("RecordType", "SpamComplaint").put("ID", i);
         receive(provider, "complaint-" + i, event.toString());
         new EmailInboxProcessor().runOnce();
         assertThat(Alerts.signals).hasSize(Math.max(0, i - 2));
      }
      assertThat(Alerts.signals).allSatisfy(signal ->
      {
         assertThat(signal.getAlertTypeName()).isEqualTo("email.complaint");
         assertThat(signal.getDedupKey()).startsWith("complaints:");
      });
      assertThat(Alerts.signals.get(0).getDedupKey()).isEqualTo(Alerts.signals.get(1).getDedupKey());
   }

   /*******************************************************************************
    ** Captures content-free core alert signals.
    *******************************************************************************/
   public static class Alerts implements com.kingsrook.qqq.backend.core.alerts.AlertSinkInterface
   {
      static final List<com.kingsrook.qqq.backend.core.alerts.AlertSignal> signals = new ArrayList<>();
      /*******************************************************************************
       ** Test adapter implementation.
       *******************************************************************************/
      @Override
      public void raise(com.kingsrook.qqq.backend.core.alerts.AlertSignal signal)
      {
         signals.add(signal);
      }

      /*******************************************************************************
       ** Test adapter implementation.
       *******************************************************************************/
      @Override
      public void resolve(com.kingsrook.qqq.backend.core.alerts.AlertResolution resolution)
      {
      }

      /*******************************************************************************
       ** Test adapter implementation.
       *******************************************************************************/
      @Override
      public void heartbeat(String source, java.time.Instant timestamp)
      {
      }

   }
}
