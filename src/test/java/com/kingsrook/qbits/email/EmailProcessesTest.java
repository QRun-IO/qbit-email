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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Email Processes Test.
 *******************************************************************************/
class EmailProcessesTest
{
   GreenMail mail;

   /*******************************************************************************
    ** Setup.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c ->
      {
      });
      mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
      mail.start();
      H2Support.provider(mail.getSmtp().getPort());
   }

   /*******************************************************************************
    ** Cleanup.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      if(mail != null)
      {
         mail.stop();
      }
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** Request.
    *******************************************************************************/
   EmailRequest request()
   {
      return (new EmailRequest()
            .withTo("a@example.com")
            .withSubject("Test")
            .withMarkdownBody("Hello"));
   }

   /*******************************************************************************
    ** Cancel Send Now Resend And Send Test Use Review And Result.
    *******************************************************************************/
   @Test
   void cancelSendNowResendAndSendTestUseReviewAndResult() throws Exception
   {
      var cancelled = EmailService.send(request());
      var cancel = ProcessRunner.run(EmailProcesses.CANCEL, cancelled.messageId());
      assertThat(cancel.review()).isNotEmpty();
      assertThat(cancel.result()).anyMatch(s -> s.contains("CANCELLED"));
      assertThat(
                  EmailStore.get(EmailTables.MESSAGE, cancelled.messageId(), null)
                        .getValueString("status"))
            .isEqualTo("CANCELLED");
      var queued = EmailService.send(request().withSendAfter(Instant.now().plusSeconds(3600)));
      var sent = ProcessRunner.run(EmailProcesses.SEND_NOW, queued.messageId());
      assertThat(sent.review()).isNotEmpty();
      assertThat(sent.result()).anyMatch(s -> s.contains("SENT"));
      assertThat(mail.waitForIncomingEmail(2000, 1)).isTrue();
      var dead = EmailService.send(request());
      SystemWrites.updateOne(
            EmailTables.MESSAGE,
            new QRecord().withValue("id", dead.messageId()).withValue("status", "DEAD_LETTER"));
      var replay = ProcessRunner.run(EmailProcesses.RESEND, dead.messageId());
      assertThat(replay.result()).anyMatch(s -> s.contains("QUEUED"));
      var messages = H2Support.all(EmailTables.MESSAGE);
      assertThat(messages).hasSize(4);
      assertThat(messages.getLast().getValueString("linkedTableName")).isEqualTo(EmailTables.MESSAGE);
      assertThat(messages.getLast().getValueString("htmlBody"))
            .isEqualTo(messages.get(2).getValueString("htmlBody"));
      var linked = new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(
         new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput(EmailTables.MESSAGE)
            .withFilter(EmailStore.equal("id", messages.getLast().getValueInteger("id"))).withShouldGenerateDisplayValues(true)).getRecords().getFirst();
      assertThat(linked.getDisplayValue("linkedRecordId:toRecordFromTableDynamic")).isEqualTo(EmailTables.MESSAGE);
      var test =
            ProcessRunner.runWithValues(
                  EmailProcesses.SEND_TEST, Map.of("toAddress", "test@example.com"), 1);
      assertThat(test.result()).anyMatch(s -> s.contains("SENT"));
      var skipped = ProcessRunner.run(EmailProcesses.CANCEL, queued.messageId());
      assertThat(skipped.review()).anyMatch(s -> s.contains("skipped"));
   }

   /*******************************************************************************
    ** Run.
    *******************************************************************************/
   RunProcessOutput run(String name, Map<String, java.io.Serializable> values) throws Exception
   {
      var input = new RunProcessInput();
      input.setProcessName(name);
      values.forEach(input::addValue);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      var result = new RunProcessAction().execute(input);
      if(result.getException().isPresent())
      {
         throw result.getException().get();
      }
      return (result);
   }

   /*******************************************************************************
    ** Backend Send And Scheduled Jobs.
    *******************************************************************************/
   @Test
   void backendSendAndScheduledJobs() throws Exception
   {
      assertThat(QContext.getQInstance().getProcess(EmailProcesses.SEND_TEMPLATED).getBackendStep("send").getInputMetaData().getField("category")).isEmpty();
      assertThat(QContext.getQInstance().getProcess(EmailProcesses.SEND_AD_HOC).getBackendStep("send").getInputMetaData().getField("category")).isPresent();
      var queued =
            run(
                  EmailProcesses.SEND_AD_HOC,
                  Map.of(
                        "to",
                        "a@example.com",
                        "subject",
                        "Backend",
                        "markdownBody",
                        "Hello",
                        "category",
                        "NOTIFICATION"));
      assertThat(queued.getValues().get("status")).isEqualTo("QUEUED");
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("category")).isEqualTo("NOTIFICATION");
      run(EmailProcesses.DISPATCH, Map.of());
      assertThat(mail.waitForIncomingEmail(2000, 1)).isTrue();
      run(EmailProcesses.MAINTAIN, Map.of());
      EmailRuntime.setClock(Clock.fixed(Instant.now().plus(Duration.ofDays(31)), ZoneOffset.UTC));
      run(EmailProcesses.RETENTION, Map.of());
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("htmlBody")).isNull();
      var refused = run(EmailProcesses.SEND_TEMPLATED, Map.of("templateName", "later"));
      assertThat(refused.getValues().get("status")).isEqualTo("REFUSED");
   }
}
