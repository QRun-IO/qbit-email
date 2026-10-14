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

package com.kingsrook.qbits.email.metadata;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.transport.EmailProviderSettings;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** Provider and suppression validation shared by UI, API and code.
 *******************************************************************************/
public final class EmailTableCustomizers
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailTableCustomizers()
   {
   }

   /*******************************************************************************
    ** Merged values for sparse updates.
    *******************************************************************************/
   private static QRecord effective(QRecord record, Optional<List<QRecord>> oldRecords)
   {
      QRecord merged = new QRecord();
      if(oldRecords != null && oldRecords.isPresent())
      {
         oldRecords.get().stream().filter(r -> Objects.equals(r.getValue("id"), record.getValue("id"))).findFirst()
            .ifPresent(r -> merged.getValues().putAll(r.getValues()));
      }
      merged.getValues().putAll(record.getValues());
      return (merged);
   }

   /*******************************************************************************
    ** Validate a provider without resolving any credentials.
    *******************************************************************************/
   public static List<String> validateProvider(QRecord row, EmailQBitConfig config)
   {
      List<String> errors = new ArrayList<>();
      String kind = row.getValueString("kind");
      if("GMAIL_API".equals(kind))
      {
         errors.add("Gmail API sending is specified but not built; use GMAIL_SMTP");
      }
      if(List.of("POSTMARK", "SENDGRID", "MAILGUN", "GMAIL_SMTP").contains(kind == null ? "" : kind)
         && (row.getValueString("secretEnvName") == null || row.getValueString("secretEnvName").isBlank()))
      {
         errors.add("Provider needs a secret environment name");
      }
      if("GMAIL_SMTP".equals(kind))
      {
         if(row.getValueString("usernameEnvName") == null || row.getValueString("usernameEnvName").isBlank())
         {
            errors.add("Gmail SMTP needs an account address environment name");
         }
         if(row.getValueString("security") != null && !"STARTTLS".equals(row.getValueString("security")))
         {
            errors.add("Gmail SMTP requires STARTTLS");
         }
      }
      if("MAILGUN".equals(kind))
      {
         if(!EmailProviderSettings.validSendingDomain(row.getValueString("sendingDomain")))
         {
            errors.add("Mailgun needs a valid sending domain");
         }
         if(!List.of("US", "EU").contains(row.getValueString("region") == null ? "" : row.getValueString("region")))
         {
            errors.add("Mailgun region must be US or EU");
         }
      }
      if(("SMTP".equals(kind) || "GMAIL_SMTP".equals(kind))
         && !new EmailProviderSettings(row, config).allowsSmtpAuth())
      {
         errors.add("SMTP sign-in needs STARTTLS or TLS");
      }
      for(String field : List.of("fromName", "fromAddress", "replyTo"))
      {
         String value = row.getValueString(field);
         if(value != null && (value.contains("\r") || value.contains("\n")))
         {
            errors.add("Provider sender and reply-to fields must not contain line breaks");
         }
      }
      for(String field : List.of("usernameEnvName", "secretEnvName", "webhookSecretEnvName"))
      {
         String name = row.getValueString(field);
         if(name != null && (!EmailQBitConfig.ENV_NAME.matcher(name).matches() || !config.effectiveAllowedEnvNames(kind).contains(name)))
         {
            errors.add("Credential references must be allowed environment variable names");
         }
      }
      if("SMTP".equals(kind) && (row.getValueString("host") == null || row.getValueString("host").isBlank()
         || row.getValueInteger("port") == null || row.getValueInteger("port") < 1 || row.getValueInteger("port") > 65535))
      {
         errors.add("SMTP needs a host and a port between 1 and 65535");
      }
      if("SES".equals(kind) && (row.getValueString("region") == null || row.getValueString("region").isBlank()))
      {
         errors.add("SES needs a region");
      }
      if("SES".equals(kind) && (row.getValueString("usernameEnvName") == null) != (row.getValueString("secretEnvName") == null))
      {
         errors.add("SES needs both credential environment names or neither for the default chain");
      }
      for(String field : List.of("maxPerMinute", "maxPerDay"))
      {
         if(row.getValueInteger(field) != null && row.getValueInteger(field) < 1)
         {
            errors.add("Rate limits must be positive or empty");
         }
      }
      try
      {
         InternetAddress from = new InternetAddress(row.getValueString("fromAddress"), true);
         from.validate();
         String domain = row.getValueString("sendingDomain");
         if(domain != null && !domain.equalsIgnoreCase(from.getAddress().substring(from.getAddress().lastIndexOf('@') + 1)))
         {
            errors.add("From address domain must match sending domain");
         }
      }
      catch(Exception e)
      {
         errors.add("A valid from address is required");
      }
      return (errors);
   }

   /*******************************************************************************
    ** Provider validation and secret-presence display.
    *******************************************************************************/
   public static class ProviderCustomizer implements TableCustomizerInterface
   {
      /*******************************************************************************
       ** Validate the resulting provider set, including sparse and batch writes.
       *******************************************************************************/
      @Override
      public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean preview, Optional<List<QRecord>> oldRecords) throws QException
      {
         if(SystemWrites.active())
         {
            return (records);
         }
         List<QRecord> resulting = new ArrayList<>(SystemWrites.query(EmailTables.PROVIDER, new QQueryFilter(),
            input instanceof com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput insert ? insert.getTransaction()
               : input instanceof com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput update ? update.getTransaction() : null));
         for(QRecord record : records)
         {
            QRecord merged = effective(record, oldRecords);
            validateProvider(merged, EmailRuntime.config()).forEach(error -> record.addError(new BadInputStatusMessage(error)));
            for(String field : List.of("usernameEnvName", "secretEnvName", "webhookSecretEnvName"))
            {
               String name = record.getValueString(field);
               if(name != null && !EmailQBitConfig.ENV_NAME.matcher(name).matches())
               {
                  // An accidentally pasted credential must not be echoed in the failed write.
                  record.setValue(field, null);
               }
            }
            String sendingDomain = merged.getValueString("sendingDomain");
            if(sendingDomain != null && !sendingDomain.isBlank() && merged.getValue("id") != null
               && SystemWrites.query(EmailTables.SENDER, EmailStore.equal("providerId", merged.getValueInteger("id")), null).stream()
                  .anyMatch(s -> !sendingDomain.equalsIgnoreCase(s.getValueString("address").substring(s.getValueString("address").lastIndexOf('@') + 1))))
            {
               record.addError(new BadInputStatusMessage("Sender addresses must match the sending domain"));
            }
            resulting.removeIf(r -> Objects.equals(r.getValue("id"), record.getValue("id")));
            resulting.add(merged);
            record.setValue("defaultSlot", Boolean.TRUE.equals(merged.getValueBoolean("enabled")) && Boolean.TRUE.equals(merged.getValueBoolean("isDefault")) ? 1 : null);
         }
         long enabled = resulting.stream().filter(r -> Boolean.TRUE.equals(r.getValueBoolean("enabled"))).count();
         long defaults = resulting.stream().filter(r -> Boolean.TRUE.equals(r.getValueBoolean("enabled")) && Boolean.TRUE.equals(r.getValueBoolean("isDefault"))).count();
         if(enabled > 0 && defaults != 1)
         {
            records.forEach(r -> r.addError(new BadInputStatusMessage("Exactly one enabled provider must be the default")));
         }
         return (records);
      }

      /*******************************************************************************
       ** Prevent deleting the default while other enabled providers remain.
       *******************************************************************************/
      @Override
      public List<QRecord> preDelete(DeleteInput input, List<QRecord> records, boolean preview) throws QException
      {
         for(QRecord row : records)
         {
            if(!SystemWrites.query(EmailTables.MESSAGE, com.kingsrook.qbits.email.support.EmailStore.equal("providerId", row.getValueInteger("id")).withLimit(1), input.getTransaction()).isEmpty())
            {
               row.addError(new BadInputStatusMessage("Providers with message history cannot be deleted; disable the provider instead"));
            }
         }
         List<QRecord> remaining = new ArrayList<>(SystemWrites.query(EmailTables.PROVIDER, new QQueryFilter(), input.getTransaction()));
         remaining.removeIf(r -> records.stream().anyMatch(d -> Objects.equals(d.getValue("id"), r.getValue("id"))));
         if(remaining.stream().anyMatch(r -> Boolean.TRUE.equals(r.getValueBoolean("enabled")))
            && remaining.stream().noneMatch(r -> Boolean.TRUE.equals(r.getValueBoolean("enabled")) && Boolean.TRUE.equals(r.getValueBoolean("isDefault"))))
         {
            records.forEach(r -> r.addError(new BadInputStatusMessage("Choose another enabled default before deleting this provider")));
         }
         for(QRecord row : records)
         {
            if(!preview && row.getErrors().isEmpty())
            {
               SystemWrites.delete(EmailTables.SENDER, EmailStore.equal("providerId", row.getValueInteger("id")), input.getTransaction());
            }
         }
         return (records);
      }

      /*******************************************************************************
       ** Presence only; internal queries never resolve credentials.
       *******************************************************************************/
      @Override
      public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records)
      {
         for(QRecord row : records)
         {
            String label = row.getValueString("label");
            row.setRecordLabel(label == null || label.isBlank() ? row.getValueString("name") : label);
         }
         if(!SystemWrites.active())
         {
            for(QRecord row : records)
            {
               for(String field : List.of("usernameEnvName", "secretEnvName", "webhookSecretEnvName"))
               {
                  String value = null;
                  String name = row.getValueString(field);
                  EmailQBitConfig config = EmailRuntime.config();
                  if(name != null && EmailQBitConfig.ENV_NAME.matcher(name).matches() && config.effectiveAllowedEnvNames(row.getValueString("kind")).contains(name))
                  {
                     try
                     {
                        value = config.getSecretResolver().apply(name);
                     }
                     catch(RuntimeException e)
                     {
                        // A failing resolver must never leak exception text or values.
                     }
                  }
                  row.setValue(field + "Status", value == null || value.isBlank() ? "Not set" : "Set");
               }
            }
         }
         return (records);
      }
   }

   /*******************************************************************************
    ** Sender addresses: canonical lowercase address, the provider's sending domain,
    ** unique per provider and at most one enabled default per provider.
    *******************************************************************************/
   public static class SenderAddressCustomizer implements TableCustomizerInterface
   {
      /*******************************************************************************
       ** Validate each row against its provider and the resulting set of rows.
       *******************************************************************************/
      @Override
      public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean preview, Optional<List<QRecord>> oldRecords) throws QException
      {
         if(SystemWrites.active())
         {
            return (records);
         }
         QBackendTransaction transaction = input instanceof InsertInput insert ? insert.getTransaction() : input instanceof UpdateInput update ? update.getTransaction() : null;
         List<QRecord> resulting = new ArrayList<>();
         Set<Serializable> providerIds = new LinkedHashSet<>();
         for(QRecord record : records)
         {
            QRecord merged = effective(record, oldRecords);
            if(record.getValueString("address") != null)
            {
               try
               {
                  String text = record.getValueString("address");
                  if(text.contains("\r") || text.contains("\n"))
                  {
                     throw (new IllegalArgumentException("Sender address must not contain line breaks"));
                  }
                  InternetAddress address = new InternetAddress(text, true);
                  address.validate();
                  record.setValue("address", address.getAddress().toLowerCase(Locale.ROOT));
                  merged.setValue("address", record.getValue("address"));
               }
               catch(Exception e)
               {
                  record.addError(new BadInputStatusMessage("A valid sender address is required"));
               }
            }
            String name = merged.getValueString("displayName");
            if(name != null && (name.contains("\r") || name.contains("\n")))
            {
               record.addError(new BadInputStatusMessage("Display name must not contain line breaks"));
            }
            QRecord provider = merged.getValue("providerId") == null ? null : EmailStore.get(EmailTables.PROVIDER, merged.getValueInteger("providerId"), transaction);
            if(provider == null)
            {
               record.addError(new BadInputStatusMessage("An existing provider is required"));
            }
            else if(merged.getValueString("address") != null && record.getErrors().isEmpty())
            {
               String domain = provider.getValueString("sendingDomain");
               String address = merged.getValueString("address");
               if(domain != null && !domain.isBlank() && !domain.equalsIgnoreCase(address.substring(address.lastIndexOf('@') + 1)))
               {
                  record.addError(new BadInputStatusMessage("Sender address domain must match the provider sending domain"));
               }
            }
            if(Boolean.TRUE.equals(merged.getValueBoolean("isDefault")) && !Boolean.TRUE.equals(merged.getValueBoolean("enabled")))
            {
               record.addError(new BadInputStatusMessage("A default sender address must be enabled"));
            }
            if(merged.getValue("providerId") != null)
            {
               providerIds.add(merged.getValue("providerId"));
            }
            resulting.add(merged);
         }
         if(!providerIds.isEmpty())
         {
            List<QRecord> existing = SystemWrites.query(EmailTables.SENDER, new QQueryFilter(new QFilterCriteria("providerId", QCriteriaOperator.IN, new ArrayList<>(providerIds))), transaction);
            existing.removeIf(r -> records.stream().anyMatch(n -> Objects.equals(n.getValue("id"), r.getValue("id"))));
            for(int i = 0; i < records.size(); i++)
            {
               QRecord merged = resulting.get(i);
               List<QRecord> peers = new ArrayList<>(existing);
               for(int j = 0; j < resulting.size(); j++)
               {
                  if(j != i)
                  {
                     peers.add(resulting.get(j));
                  }
               }
               peers.removeIf(r -> !Objects.equals(r.getValue("providerId"), merged.getValue("providerId")));
               if(peers.stream().anyMatch(r -> Objects.equals(r.getValueString("address"), merged.getValueString("address"))))
               {
                  records.get(i).addError(new BadInputStatusMessage("That sender address already exists for this provider"));
               }
               if(Boolean.TRUE.equals(merged.getValueBoolean("isDefault")) && peers.stream().anyMatch(r -> Boolean.TRUE.equals(r.getValueBoolean("isDefault"))))
               {
                  records.get(i).addError(new BadInputStatusMessage("Only one sender address per provider can be the default"));
               }
            }
         }
         return (records);
      }
   }

   /*******************************************************************************
    ** Canonical addresses and administrator protection for complaint removal.
    *******************************************************************************/
   public static class SuppressionCustomizer implements TableCustomizerInterface
   {
      /*******************************************************************************
       ** Normalize and protect existing complaint rows from being weakened.
       *******************************************************************************/
      @Override
      public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean preview, Optional<List<QRecord>> oldRecords)
      {
         for(QRecord record : records)
         {
            QRecord merged = effective(record, oldRecords);
            if(merged.getValue("createdDate") == null)
            {
               record.setValue("createdDate", EmailRuntime.clock().instant());
            }
            if(record.getValueString("address") != null)
            {
               try
               {
                  InternetAddress address = new InternetAddress(record.getValueString("address"), true);
                  address.validate();
                  record.setValue("address", address.getAddress().toLowerCase(Locale.ROOT));
               }
               catch(Exception e)
               {
                  record.addError(new BadInputStatusMessage("A valid suppression address is required"));
               }
            }
            if(oldRecords != null && oldRecords.isPresent() && oldRecords.get().stream().anyMatch(r -> Objects.equals(r.getValue("id"), merged.getValue("id")) && "COMPLAINT".equals(r.getValueString("reason"))))
            {
               protect(record);
            }
         }
         return (records);
      }

      /*******************************************************************************
       ** Only administrators remove complaint suppressions.
       *******************************************************************************/
      @Override
      public List<QRecord> preDelete(DeleteInput input, List<QRecord> records, boolean preview)
      {
         records.stream().filter(r -> "COMPLAINT".equals(r.getValueString("reason"))).forEach(SuppressionCustomizer::protect);
         return (records);
      }

      /*******************************************************************************
       ** Apply the complaint permission even if core enforcement is disabled.
       *******************************************************************************/
      private static void protect(QRecord record)
      {
         if(!SystemWrites.active() && !EmailPermissionChecker.has(EmailPermissions.ADMIN))
         {
            record.addError(new BadInputStatusMessage("Changing a complaint suppression needs email.admin"));
         }
      }
   }

   /*******************************************************************************
    ** Link application records through the standard dynamic record-link adornment.
    *******************************************************************************/
   public static class MessageLinksCustomizer implements TableCustomizerInterface
   {
      /*******************************************************************************
       ** Supply only the target table name; no linked record content is loaded.
       *******************************************************************************/
      @Override
      public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records)
      {
         if(!SystemWrites.active())
         {
            for(QRecord row : records)
            {
               for(String field : List.of("subject", "htmlBody", "textBody"))
               {
                  String value = row.getValueString(field);
                  if(value != null && value.startsWith("enc:v1:"))
                  {
                     row.setValue(field, "[encrypted]");
                     row.setDisplayValue(field, "[encrypted]");
                  }
               }
            }
         }
         for(QRecord row : records)
         {
            String subject = row.getValueString("subject");
            boolean secure = Boolean.TRUE.equals(row.getValueBoolean("phiApproved")) || "[encrypted]".equals(subject) || (subject != null && subject.startsWith("enc:v1:"));
            row.setRecordLabel(secure ? "Secure message" : subject == null || subject.isBlank() ? "Message " + row.getValue("id") : subject);
         }
         if(input.getShouldGenerateDisplayValues())
         {
            for(QRecord row : records)
            {
               String table = row.getValueString("linkedTableName");
               if(table != null && row.getValue("linkedRecordId") != null
                  && com.kingsrook.qqq.backend.core.context.QContext.getQInstance().getTable(table) != null)
               {
                  row.setDisplayValue("linkedRecordId:toRecordFromTableDynamic", table);
               }
            }
         }
         return (records);
      }
   }

}
