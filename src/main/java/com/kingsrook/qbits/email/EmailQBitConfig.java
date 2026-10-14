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


import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.producers.MetaDataCustomizerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Configuration for phase (a) email delivery.
 *******************************************************************************/
public class EmailQBitConfig implements QBitConfig
{
   private com.kingsrook.qbits.email.template.MjmlCompilerInterface mjmlCompiler;
   private java.util.Map<String, com.kingsrook.qbits.email.phi.EmailClassifications.Level> classificationOverrides = java.util.Map.of();
   private String backendName;
   private String schedulerName;
   private String publicBaseUrl;
   private String defaultProviderName;
   private String bridgeProviderName = "qbitEmail";
   private Function<String, String> secretResolver = System::getenv;
   private boolean allowPlaintextSmtpAuth = false;
   private Set<String> allowedEnvNames = Set.of();
   private Integer dispatchIntervalSeconds = 15;
   private Integer batchSize = 100;
   private Integer maxAttempts = 6;
   private List<Integer> retryMinutes = List.of(1, 5, 15, 60, 240, 720);
   private Integer leaseSeconds = 120;
   private Integer bodyRetentionDays = 30;
   private Integer messageRetentionDays = 0;
   private String appName = "email";
   private String appLabel = "Email";
   private List<QFieldMetaData> securityFields = List.of();
   private List<RecordSecurityLock> recordSecurityLocks = List.of();
   private MetaDataCustomizerInterface<QTableMetaData> tableMetaDataCustomizer;

   public static final Pattern ENV_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]{0,99}$");

   /*******************************************************************************
    ** Whether the host explicitly permits SMTP credentials without TLS.
    *******************************************************************************/
   public boolean getAllowPlaintextSmtpAuth()
   {
      return (allowPlaintextSmtpAuth);
   }

   /*******************************************************************************
    ** Opt in to plaintext SMTP authentication outside loopback.
    *******************************************************************************/
   public EmailQBitConfig withAllowPlaintextSmtpAuth(boolean allowPlaintextSmtpAuth)
   {
      this.allowPlaintextSmtpAuth = allowPlaintextSmtpAuth;
      return (this);
   }

   /*******************************************************************************
    ** Validate against the host instance.
    *******************************************************************************/
   @Override
   public void validate(QInstance instance, List<String> errors)
   {
      if(backendName == null || backendName.isBlank())
      {
         errors.add("backendName is required");
      }
      else if(instance.getBackend(backendName) == null)
      {
         errors.add("backend not found: " + backendName);
      }
      if(schedulerName != null && instance.getScheduler(schedulerName) == null)
      {
         errors.add("schedulerName is not a scheduler in the instance");
      }
      if(maxAttempts == null || maxAttempts < 1 || batchSize == null || batchSize < 1 || dispatchIntervalSeconds == null || dispatchIntervalSeconds < 1)
      {
         errors.add("Attempts, batch size and dispatch interval must be positive");
      }
      if(leaseSeconds == null || leaseSeconds < 60)
      {
         errors.add("leaseSeconds must be at least 60, longer than transport timeouts");
      }
      if(retryMinutes == null || retryMinutes.isEmpty() || retryMinutes.stream().anyMatch(n -> n == null || n < 1))
      {
         errors.add("retryMinutes must contain positive delays");
      }
      if(bodyRetentionDays == null || bodyRetentionDays < 0 || messageRetentionDays == null || messageRetentionDays < 0)
      {
         errors.add("Retention days must not be negative");
      }
      if(secretResolver == null || allowedEnvNames == null || allowedEnvNames.stream().anyMatch(n -> n == null || !ENV_NAME.matcher(n).matches()))
      {
         errors.add("A secret resolver and valid allowed environment names are required");
      }
      if(bridgeProviderName == null || bridgeProviderName.isBlank() || appName == null || appName.isBlank())
      {
         errors.add("Bridge provider and app names are required");
      }
   }

   /*******************************************************************************
    ** Retry minutes after a one-based attempt, repeating the final delay.
    *******************************************************************************/
   public int retryMinutesAfterAttempt(int number)
   {
      return (retryMinutes.get(Math.max(0, Math.min(number - 1, retryMinutes.size() - 1))));
   }

   /*******************************************************************************
    ** Allowed names include the documented defaults for this provider kind.
    *******************************************************************************/
   public Set<String> effectiveAllowedEnvNames(String kind)
   {
      Set<String> names = new HashSet<>(allowedEnvNames);
      names.addAll(switch(kind == null ? "" : kind)
      {
         case "SMTP", "GMAIL_SMTP" -> Set.of("EMAIL_SMTP_USERNAME", "EMAIL_SMTP_PASSWORD", "EMAIL_GMAIL_USERNAME", "EMAIL_GMAIL_APP_PASSWORD");
         case "SES" -> Set.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN");
         case "POSTMARK" -> Set.of("EMAIL_POSTMARK_SERVER_TOKEN", "EMAIL_POSTMARK_WEBHOOK_PASSWORD");
         case "SENDGRID" -> Set.of("EMAIL_SENDGRID_API_KEY", "EMAIL_SENDGRID_WEBHOOK_PUBLIC_KEY");
         case "MAILGUN" -> Set.of("EMAIL_MAILGUN_API_KEY", "EMAIL_MAILGUN_SIGNING_KEY");
         case "GMAIL_API" -> Set.of("EMAIL_GMAIL_CLIENT_ID", "EMAIL_GMAIL_CLIENT_SECRET", "EMAIL_GMAIL_REFRESH_TOKEN");
         default -> Set.of();
      });
      return (names);
   }


   /*******************************************************************************
    ** Getter for backendName.
    *******************************************************************************/
   public String getBackendName()
   {
      return (backendName);
   }


   /*******************************************************************************
    ** Fluent setter for backendName.
    *******************************************************************************/
   public EmailQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for schedulerName.
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (schedulerName);
   }


   /*******************************************************************************
    ** Fluent setter for schedulerName.
    *******************************************************************************/
   public EmailQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for publicBaseUrl.
    *******************************************************************************/
   public String getPublicBaseUrl()
   {
      return (publicBaseUrl);
   }


   /*******************************************************************************
    ** Fluent setter for publicBaseUrl.
    *******************************************************************************/
   public EmailQBitConfig withPublicBaseUrl(String publicBaseUrl)
   {
      this.publicBaseUrl = publicBaseUrl;
      return (this);
   }


   /*******************************************************************************
    ** Getter for defaultProviderName.
    *******************************************************************************/
   public String getDefaultProviderName()
   {
      return (defaultProviderName);
   }


   /*******************************************************************************
    ** Fluent setter for defaultProviderName.
    *******************************************************************************/
   public EmailQBitConfig withDefaultProviderName(String defaultProviderName)
   {
      this.defaultProviderName = defaultProviderName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for bridgeProviderName.
    *******************************************************************************/
   public String getBridgeProviderName()
   {
      return (bridgeProviderName);
   }


   /*******************************************************************************
    ** Fluent setter for bridgeProviderName.
    *******************************************************************************/
   public EmailQBitConfig withBridgeProviderName(String bridgeProviderName)
   {
      this.bridgeProviderName = bridgeProviderName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for secretResolver.
    *******************************************************************************/
   public Function<String, String> getSecretResolver()
   {
      return (secretResolver);
   }


   /*******************************************************************************
    ** Fluent setter for secretResolver.
    *******************************************************************************/
   public EmailQBitConfig withSecretResolver(Function<String, String> secretResolver)
   {
      this.secretResolver = secretResolver;
      return (this);
   }


   /*******************************************************************************
    ** Getter for allowedEnvNames.
    *******************************************************************************/
   public Set<String> getAllowedEnvNames()
   {
      return (allowedEnvNames);
   }


   /*******************************************************************************
    ** Fluent setter for allowedEnvNames.
    *******************************************************************************/
   public EmailQBitConfig withAllowedEnvNames(Set<String> allowedEnvNames)
   {
      this.allowedEnvNames = allowedEnvNames;
      return (this);
   }


   /*******************************************************************************
    ** Getter for dispatchIntervalSeconds.
    *******************************************************************************/
   public Integer getDispatchIntervalSeconds()
   {
      return (dispatchIntervalSeconds);
   }


   /*******************************************************************************
    ** Fluent setter for dispatchIntervalSeconds.
    *******************************************************************************/
   public EmailQBitConfig withDispatchIntervalSeconds(Integer dispatchIntervalSeconds)
   {
      this.dispatchIntervalSeconds = dispatchIntervalSeconds;
      return (this);
   }


   /*******************************************************************************
    ** Getter for batchSize.
    *******************************************************************************/
   public Integer getBatchSize()
   {
      return (batchSize);
   }


   /*******************************************************************************
    ** Fluent setter for batchSize.
    *******************************************************************************/
   public EmailQBitConfig withBatchSize(Integer batchSize)
   {
      this.batchSize = batchSize;
      return (this);
   }


   /*******************************************************************************
    ** Getter for maxAttempts.
    *******************************************************************************/
   public Integer getMaxAttempts()
   {
      return (maxAttempts);
   }


   /*******************************************************************************
    ** Fluent setter for maxAttempts.
    *******************************************************************************/
   public EmailQBitConfig withMaxAttempts(Integer maxAttempts)
   {
      this.maxAttempts = maxAttempts;
      return (this);
   }


   /*******************************************************************************
    ** Getter for retryMinutes.
    *******************************************************************************/
   public List<Integer> getRetryMinutes()
   {
      return (retryMinutes);
   }


   /*******************************************************************************
    ** Fluent setter for retryMinutes.
    *******************************************************************************/
   public EmailQBitConfig withRetryMinutes(List<Integer> retryMinutes)
   {
      this.retryMinutes = retryMinutes;
      return (this);
   }


   /*******************************************************************************
    ** Getter for leaseSeconds.
    *******************************************************************************/
   public Integer getLeaseSeconds()
   {
      return (leaseSeconds);
   }


   /*******************************************************************************
    ** Fluent setter for leaseSeconds.
    *******************************************************************************/
   public EmailQBitConfig withLeaseSeconds(Integer leaseSeconds)
   {
      this.leaseSeconds = leaseSeconds;
      return (this);
   }


   /*******************************************************************************
    ** Getter for bodyRetentionDays.
    *******************************************************************************/
   public Integer getBodyRetentionDays()
   {
      return (bodyRetentionDays);
   }


   /*******************************************************************************
    ** Fluent setter for bodyRetentionDays.
    *******************************************************************************/
   public EmailQBitConfig withBodyRetentionDays(Integer bodyRetentionDays)
   {
      this.bodyRetentionDays = bodyRetentionDays;
      return (this);
   }


   /*******************************************************************************
    ** Getter for messageRetentionDays.
    *******************************************************************************/
   public Integer getMessageRetentionDays()
   {
      return (messageRetentionDays);
   }


   /*******************************************************************************
    ** Fluent setter for messageRetentionDays.
    *******************************************************************************/
   public EmailQBitConfig withMessageRetentionDays(Integer messageRetentionDays)
   {
      this.messageRetentionDays = messageRetentionDays;
      return (this);
   }


   /*******************************************************************************
    ** Getter for appName.
    *******************************************************************************/
   public String getAppName()
   {
      return (appName);
   }


   /*******************************************************************************
    ** Fluent setter for appName.
    *******************************************************************************/
   public EmailQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for appLabel.
    *******************************************************************************/
   public String getAppLabel()
   {
      return (appLabel);
   }


   /*******************************************************************************
    ** Fluent setter for appLabel.
    *******************************************************************************/
   public EmailQBitConfig withAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
      return (this);
   }


   /*******************************************************************************
    ** Getter for securityFields.
    *******************************************************************************/
   public List<QFieldMetaData> getSecurityFields()
   {
      return (securityFields);
   }


   /*******************************************************************************
    ** Fluent setter for securityFields.
    *******************************************************************************/
   public EmailQBitConfig withSecurityFields(List<QFieldMetaData> securityFields)
   {
      this.securityFields = securityFields;
      return (this);
   }


   /*******************************************************************************
    ** Getter for recordSecurityLocks.
    *******************************************************************************/
   public List<RecordSecurityLock> getRecordSecurityLocks()
   {
      return (recordSecurityLocks);
   }


   /*******************************************************************************
    ** Fluent setter for recordSecurityLocks.
    *******************************************************************************/
   public EmailQBitConfig withRecordSecurityLocks(List<RecordSecurityLock> recordSecurityLocks)
   {
      this.recordSecurityLocks = recordSecurityLocks;
      return (this);
   }


   /*******************************************************************************
    ** Getter for tableMetaDataCustomizer.
    *******************************************************************************/
   public MetaDataCustomizerInterface<QTableMetaData> getTableMetaDataCustomizer()
   {
      return (tableMetaDataCustomizer);
   }


   /*******************************************************************************
    ** Fluent setter for tableMetaDataCustomizer.
    *******************************************************************************/
   public EmailQBitConfig withTableMetaDataCustomizer(MetaDataCustomizerInterface<QTableMetaData> tableMetaDataCustomizer)
   {
      this.tableMetaDataCustomizer = tableMetaDataCustomizer;
      return (this);
   }

   /*******************************************************************************
    ** Activation-time compiler, optional unless MJML templates are used.
    *******************************************************************************/
   public com.kingsrook.qbits.email.template.MjmlCompilerInterface getMjmlCompiler()
   {
      return (mjmlCompiler);
   }

   /*******************************************************************************
    ** Configure a compiler without introducing a runtime dependency.
    *******************************************************************************/
   public EmailQBitConfig withMjmlCompiler(com.kingsrook.qbits.email.template.MjmlCompilerInterface compiler)
   {
      mjmlCompiler = compiler;
      return (this);
   }

   /*******************************************************************************
    ** Field classifications used until core supplies classification metadata.
    *******************************************************************************/
   public java.util.Map<String, com.kingsrook.qbits.email.phi.EmailClassifications.Level> getClassificationOverrides()
   {
      return (classificationOverrides);
   }

   /*******************************************************************************
    ** Configure explicit table.field classifications.
    *******************************************************************************/
   public EmailQBitConfig withClassificationOverrides(java.util.Map<String, com.kingsrook.qbits.email.phi.EmailClassifications.Level> overrides)
   {
      classificationOverrides = java.util.Map.copyOf(overrides);
      return (this);
   }
}
