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

package com.kingsrook.qbits.email.processes;


import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.delivery.EmailDispatcher;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailRenderer;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** Standard Review/Result record actions and hidden scheduled maintenance.
 *******************************************************************************/
public final class EmailProcesses
{
   public static final String RESEND = "email_resend";
   public static final String CANCEL = "email_cancel";
   public static final String SEND_NOW = "email_send_now";
   public static final String SEND_TEST = "email_send_test";
   public static final String SEND_TEMPLATED = "email_send_templated";
   public static final String DISPATCH = "email_dispatch";
   public static final String MAINTAIN = "email_maintain";
   public static final String RETENTION = "email_retention";

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailProcesses()
   {
   }

   /*******************************************************************************
    ** Required action permission.
    *******************************************************************************/
   public static String permissionFor(String name)
   {
      return (switch(name)
      {
         case RESEND, CANCEL, SEND_NOW -> EmailPermissions.MANAGE;
         case SEND_TEMPLATED -> EmailPermissions.SEND;
         default -> EmailPermissions.ADMIN;
      });
   }

   /*******************************************************************************
    ** All actions and schedules; none is an app child.
    *******************************************************************************/
   public static List<QProcessMetaData> processes(EmailQBitConfig config)
   {
      QProcessMetaData test = action(SEND_TEST, "Send Test Email", EmailTables.PROVIDER);
      test.withStep(0, new QFrontendStepMetaData().withName("input").withLabel("Recipient")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM))
         .withFormField(new QFieldMetaData("toAddress", QFieldType.STRING).withLabel("To address").withIsRequired(true)));
      QFunctionInputMetaData inputs = new QFunctionInputMetaData();
      for(String field : List.of("templateName", "to", "cc", "bcc", "replyTo", "subject", "markdownBody", "htmlBody", "textBody", "providerName", "brandName",
         "category", "variablesJson", "linkedTableName", "linkedRecordId", "idempotencyKey"))
      {
         inputs.withField(new QFieldMetaData(field, QFieldType.STRING));
      }
      QProcessMetaData send = new QProcessMetaData().withName(SEND_TEMPLATED).withLabel("Send Email").withIsHidden(true)
         .withPermissionRules(EmailTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("send").withCode(new QCodeReference(SendStep.class)).withInputData(inputs));
      return (List.of(action(RESEND, "Resend", EmailTables.MESSAGE), action(CANCEL, "Cancel", EmailTables.MESSAGE),
         action(SEND_NOW, "Send Now", EmailTables.MESSAGE), test, send,
         scheduled(config, DISPATCH, "Dispatch Email", config.getDispatchIntervalSeconds()),
         scheduled(config, MAINTAIN, "Maintain Email", 300), scheduled(config, RETENTION, "Email Retention", 86400)));
   }

   /*******************************************************************************
    ** A standard streamed-ETL action.
    *******************************************************************************/
   private static QProcessMetaData action(String name, String label, String table)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_FETCH_HEAVY_FIELDS, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, label + " for the selected records.");
      return (StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, ActionTransform.class, ActionLoad.class, values)
         .withName(name).withLabel(label).withIcon(new QIcon("mail")).withTableName(table).withPermissionRules(EmailTables.permissionRules()));
   }

   /*******************************************************************************
    ** Hidden backend schedule; without a scheduler manual dispatch remains possible.
    *******************************************************************************/
   private static QProcessMetaData scheduled(EmailQBitConfig config, String name, String label, int seconds)
   {
      QProcessMetaData process = new QProcessMetaData().withName(name).withLabel(label).withIsHidden(true)
         .withPermissionRules(EmailTables.permissionRules()).withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(ScheduledStep.class)));
      if(config.getSchedulerName() != null)
      {
         process.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withRepeatSeconds(seconds).withInitialDelaySeconds(Math.min(seconds, 30)));
      }
      return (process);
   }

   /*******************************************************************************
    ** Whether a selected row can participate in the action.
    *******************************************************************************/
   private static boolean eligible(String process, QRecord row)
   {
      if(SEND_TEST.equals(process))
      {
         return (Boolean.TRUE.equals(row.getValueBoolean("enabled")));
      }
      String status = row.getValueString("status");
      if(RESEND.equals(process))
      {
         return (List.of("DEAD_LETTER", "BOUNCED", "COMPLAINED").contains(status)
            && (row.getValueString("htmlBody") != null || row.getValueString("textBody") != null));
      }
      return (EmailDispatcher.DUE.contains(status));
   }

   /*******************************************************************************
    ** Review screen validation without side effects.
    *******************************************************************************/
   public static class ActionTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();

      /*******************************************************************************
       ** Filter rows and explain skipped records.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output)
      {
         for(QRecord row : input.getRecords())
         {
            boolean valid = eligible(input.getProcessName(), row);
            lines.add(new ProcessSummaryLine(valid ? Status.OK : Status.INFO, 1, valid ? "record will be processed" : "record skipped: status or retained content does not permit this action"));
            if(valid)
            {
               output.getRecords().add(row);
            }
         }
      }

      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         return (lines);
      }
   }

   /*******************************************************************************
    ** Execute only after review; re-read rows to avoid stale actions.
    *******************************************************************************/
   public static class ActionLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();

      /*******************************************************************************
       ** Perform and report one selected row at a time.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String process = input.getProcessName();
         for(QRecord row : input.getRecords())
         {
            Integer id = row.getValueInteger("id");
            QRecord current = EmailStore.get(SEND_TEST.equals(process) ? EmailTables.PROVIDER : EmailTables.MESSAGE, id, null);
            String result = "Skipped";
            if(current != null && eligible(process, current))
            {
               result = switch(process)
               {
                  case SEND_NOW -> new EmailDispatcher().sendNow(id).name();
                  case CANCEL -> cancel(id) ? "CANCELLED" : "Skipped";
                  case RESEND -> resend(current).status().name();
                  case SEND_TEST -> sendTest(current, input.getValueString("toAddress"));
                  default -> "Skipped";
               };
            }
            lines.add(new ProcessSummaryLine(Status.INFO, 1, "record: " + result));
            output.getRecords().add(row);
         }
      }

      /*******************************************************************************
       ** Result screen lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         return (lines);
      }
   }

   /*******************************************************************************
    ** Cancel under the same database gate as claims.
    *******************************************************************************/
   public static boolean cancel(Integer id) throws QException
   {
      QRecord row = EmailStore.get(EmailTables.MESSAGE, id, null);
      if(row == null)
      {
         return (false);
      }
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         EmailStore.lockProvider(row.getValueInteger("providerId"), tx);
         row = EmailStore.get(EmailTables.MESSAGE, id, tx);
         if(!EmailDispatcher.DUE.contains(row.getValueString("status")))
         {
            return (false);
         }
         EmailStore.update(EmailTables.MESSAGE, new QRecord().withValue("id", id).withValue("status", "CANCELLED").withValue("finishedDate", EmailRuntime.clock().instant()).withValue("nextAttemptDate", null), tx);
         tx.commit();
         return (true);
      }
   }

   /*******************************************************************************
    ** A fresh, linked message through suppression and provider validation again.
    *******************************************************************************/
   private static EmailSendResult resend(QRecord row) throws QException
   {
      QRecord provider = EmailStore.get(EmailTables.PROVIDER, row.getValueInteger("providerId"), null);
      EmailRequest request = new EmailRequest().withSubject(row.getValueString("subject")).withHtmlBody(row.getValueString("htmlBody"))
         .withTextBody(row.getValueString("textBody")).withFrom(row.getValueString("fromAddress")).withReplyTo(row.getValueString("replyTo"))
         .withProviderName(provider.getValueString("name")).withCategory(EmailCategory.valueOf(row.getValueString("category")))
         .withLinkedRecord(EmailTables.MESSAGE, row.getValue("id")).withSender("email", RESEND);
      for(String field : List.of("toAddresses", "ccAddresses", "bccAddresses"))
      {
         String value = row.getValueString(field);
         if(value != null && !value.isBlank())
         {
            switch(field)
            {
               case "toAddresses" -> request.withTo(value);
               case "ccAddresses" -> request.withCc(value);
               default -> request.withBcc(value);
            }
         }
      }
      for(var field : EmailRuntime.config().getSecurityFields())
      {
         request.getSecurityValues().put(field.getName(), row.getValue(field.getName()));
      }
      return (EmailService.send(request, ignored -> new EmailRenderer.Rendered(row.getValueString("htmlBody"), row.getValueString("textBody"))));
   }

   /*******************************************************************************
    ** Queue and dispatch a fixed provider test message.
    *******************************************************************************/
   private static String sendTest(QRecord provider, String to) throws QException
   {
      if(to == null)
      {
         return ("REFUSED: a to address is required");
      }
      EmailSendResult result = EmailService.send(new EmailRequest().withProviderName(provider.getValueString("name")).withTo(to)
         .withSubject("Email delivery test").withMarkdownBody("This is a test of your email provider.").withSender("email", SEND_TEST));
      return (result.status() == EmailSendResult.Status.QUEUED ? new EmailDispatcher().sendNow(result.messageId()).name() : result.status().name());
   }

   /*******************************************************************************
    ** Backend sending API with inputs declared as QQQ fields.
    *******************************************************************************/
   public static class SendStep implements BackendStep
   {
      /*******************************************************************************
       ** Map process inputs to the same service API.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         EmailRequest request = new EmailRequest().withTemplateName(input.getValueString("templateName"))
            .withSubject(input.getValueString("subject")).withMarkdownBody(input.getValueString("markdownBody"))
            .withHtmlBody(input.getValueString("htmlBody")).withTextBody(input.getValueString("textBody"))
            .withReplyTo(input.getValueString("replyTo")).withProviderName(input.getValueString("providerName"))
            .withBrandName(input.getValueString("brandName")).withIdempotencyKey(input.getValueString("idempotencyKey"))
            .withLinkedRecord(input.getValueString("linkedTableName"), input.getValueString("linkedRecordId"));
         if(input.getValueString("to") != null)
         {
            request.withTo(input.getValueString("to"));
         }
         if(input.getValueString("cc") != null)
         {
            request.withCc(input.getValueString("cc"));
         }
         if(input.getValueString("bcc") != null)
         {
            request.withBcc(input.getValueString("bcc"));
         }
         if(input.getValueString("variablesJson") != null)
         {
            request.withVariable("variablesJson", input.getValueString("variablesJson"));
         }
         if(input.getValueString("category") != null)
         {
            try
            {
               request.withCategory(EmailCategory.valueOf(input.getValueString("category")));
            }
            catch(IllegalArgumentException e)
            {
               throw (new QException("Unknown email category"));
            }
         }
         EmailSendResult result = EmailService.send(request);
         output.addValue("messageId", result.messageId());
         output.addValue("status", result.status().name());
         output.addValue("reasons", String.join("; ", result.reasons()));
      }
   }

   /*******************************************************************************
    ** Scheduler entry point.
    *******************************************************************************/
   public static class ScheduledStep implements BackendStep
   {
      /*******************************************************************************
       ** Dispatch, recover leases and probe health, or retain final messages.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         switch(input.getProcessName())
         {
            case RETENTION -> retain();
            case MAINTAIN -> new EmailDispatcher().maintain();
            default -> new EmailDispatcher().runOnce();
         }
      }
   }

   /*******************************************************************************
    ** Clear retained bodies and optionally delete final messages and their attempts.
    *******************************************************************************/
   public static void retain() throws QException
   {
      EmailQBitConfig config = EmailRuntime.config();
      Instant now = EmailRuntime.clock().instant();
      for(QRecord row : SystemWrites.query(EmailTables.MESSAGE, new QQueryFilter(new QFilterCriteria("status", QCriteriaOperator.IN, new ArrayList<>(EmailDispatcher.FINAL)))))
      {
         Instant finalDate = row.getValueInstant("finishedDate");
         if(finalDate == null)
         {
            finalDate = row.getValueInstant("sentDate");
         }
         if(finalDate == null)
         {
            finalDate = row.getValueInstant("deadLetterDate");
         }
         if(finalDate == null)
         {
            finalDate = row.getValueInstant("createDate");
         }
         if(finalDate == null)
         {
            continue;
         }
         if(config.getMessageRetentionDays() > 0 && finalDate.isBefore(now.minus(Duration.ofDays(config.getMessageRetentionDays()))))
         {
            try(QBackendTransaction transaction = QBackendTransaction.openFor(new UpdateInput(EmailTables.MESSAGE)))
            {
               SystemWrites.delete(EmailTables.ATTEMPT, EmailStore.equal("messageId", row.getValueInteger("id")), transaction);
               SystemWrites.delete(EmailTables.MESSAGE, EmailStore.equal("id", row.getValueInteger("id")), transaction);
               transaction.commit();
            }
         }
         else if(finalDate.isBefore(now.minus(Duration.ofDays(config.getBodyRetentionDays()))) && (row.getValue("htmlBody") != null || row.getValue("textBody") != null))
         {
            SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", row.getValue("id")).withValue("htmlBody", null).withValue("textBody", null));
         }
      }
   }
}
