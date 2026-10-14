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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;


/*******************************************************************************
 ** Synchronous enqueue request. Templates and classified variables arrive in part 2.
 *******************************************************************************/
public class EmailRequest
{
   private List<String> to = new ArrayList<>();
   private List<String> cc = new ArrayList<>();
   private List<String> bcc = new ArrayList<>();
   private String from;
   private String replyTo;
   private String subject;
   private String markdownBody;
   private String htmlBody;
   private String textBody;
   private String providerName;
   private String templateName;
   private String brandName;
   private String locale = "en";
   private EmailCategory category = EmailCategory.TRANSACTIONAL;
   private String idempotencyKey;
   private Instant sendAfter;
   private String linkedTableName;
   private String linkedRecordId;
   private String senderQbit;
   private String senderProcess;
   private QBackendTransaction transaction;
   private String transactionBackendName;
   private Map<String, Serializable> variables = new LinkedHashMap<>();
   private Map<String, Serializable> securityValues = new LinkedHashMap<>();

   /*******************************************************************************
    ** Append to recipients.
    *******************************************************************************/
   public EmailRequest withTo(String... addresses)
   {
      to.addAll(List.of(addresses));
      return (this);
   }

   /*******************************************************************************
    ** Append cc recipients.
    *******************************************************************************/
   public EmailRequest withCc(String... addresses)
   {
      cc.addAll(List.of(addresses));
      return (this);
   }

   /*******************************************************************************
    ** Append bcc recipients.
    *******************************************************************************/
   public EmailRequest withBcc(String... addresses)
   {
      bcc.addAll(List.of(addresses));
      return (this);
   }

   /*******************************************************************************
    ** Link the message to an application record.
    *******************************************************************************/
   public EmailRequest withLinkedRecord(String table, Object id)
   {
      linkedTableName = table;
      linkedRecordId = id == null ? null : String.valueOf(id);
      return (this);
   }

   /*******************************************************************************
    ** Identify the producing qbit and process.
    *******************************************************************************/
   public EmailRequest withSender(String qbit, String process)
   {
      senderQbit = qbit;
      senderProcess = process;
      return (this);
   }

   /*******************************************************************************
    ** Declare the backend of the caller transaction; QBackendTransaction does not expose it.
    *******************************************************************************/
   public EmailRequest withTransaction(QBackendTransaction transaction, String backendName)
   {
      this.transaction = transaction;
      this.transactionBackendName = backendName;
      return (this);
   }

   /*******************************************************************************
    ** Accept template variables as a seam; part 1 refuses variable-bearing requests.
    *******************************************************************************/
   public EmailRequest withVariable(String name, Serializable value)
   {
      variables.put(name, value);
      return (this);
   }

   /*******************************************************************************
    ** Accept a record reference without reading or snapshotting the record.
    *******************************************************************************/
   public EmailRequest withRecordVariable(String name, String table, Serializable id)
   {
      variables.put(name, new LinkedHashMap<>(Map.of("table", table, "id", id)));
      return (this);
   }


   /*******************************************************************************
    ** Getter for to.
    *******************************************************************************/
   public List<String> getTo()
   {
      return (to);
   }


   /*******************************************************************************
    ** Fluent setter for to.
    *******************************************************************************/
   public EmailRequest withTo(List<String> to)
   {
      this.to = to;
      return (this);
   }


   /*******************************************************************************
    ** Getter for cc.
    *******************************************************************************/
   public List<String> getCc()
   {
      return (cc);
   }


   /*******************************************************************************
    ** Fluent setter for cc.
    *******************************************************************************/
   public EmailRequest withCc(List<String> cc)
   {
      this.cc = cc;
      return (this);
   }


   /*******************************************************************************
    ** Getter for bcc.
    *******************************************************************************/
   public List<String> getBcc()
   {
      return (bcc);
   }


   /*******************************************************************************
    ** Fluent setter for bcc.
    *******************************************************************************/
   public EmailRequest withBcc(List<String> bcc)
   {
      this.bcc = bcc;
      return (this);
   }


   /*******************************************************************************
    ** Getter for from.
    *******************************************************************************/
   public String getFrom()
   {
      return (from);
   }


   /*******************************************************************************
    ** Fluent setter for from.
    *******************************************************************************/
   public EmailRequest withFrom(String from)
   {
      this.from = from;
      return (this);
   }


   /*******************************************************************************
    ** Getter for replyTo.
    *******************************************************************************/
   public String getReplyTo()
   {
      return (replyTo);
   }


   /*******************************************************************************
    ** Fluent setter for replyTo.
    *******************************************************************************/
   public EmailRequest withReplyTo(String replyTo)
   {
      this.replyTo = replyTo;
      return (this);
   }


   /*******************************************************************************
    ** Getter for subject.
    *******************************************************************************/
   public String getSubject()
   {
      return (subject);
   }


   /*******************************************************************************
    ** Fluent setter for subject.
    *******************************************************************************/
   public EmailRequest withSubject(String subject)
   {
      this.subject = subject;
      return (this);
   }


   /*******************************************************************************
    ** Getter for markdownBody.
    *******************************************************************************/
   public String getMarkdownBody()
   {
      return (markdownBody);
   }


   /*******************************************************************************
    ** Fluent setter for markdownBody.
    *******************************************************************************/
   public EmailRequest withMarkdownBody(String markdownBody)
   {
      this.markdownBody = markdownBody;
      return (this);
   }


   /*******************************************************************************
    ** Getter for htmlBody.
    *******************************************************************************/
   public String getHtmlBody()
   {
      return (htmlBody);
   }


   /*******************************************************************************
    ** Fluent setter for htmlBody.
    *******************************************************************************/
   public EmailRequest withHtmlBody(String htmlBody)
   {
      this.htmlBody = htmlBody;
      return (this);
   }


   /*******************************************************************************
    ** Getter for textBody.
    *******************************************************************************/
   public String getTextBody()
   {
      return (textBody);
   }


   /*******************************************************************************
    ** Fluent setter for textBody.
    *******************************************************************************/
   public EmailRequest withTextBody(String textBody)
   {
      this.textBody = textBody;
      return (this);
   }


   /*******************************************************************************
    ** Getter for providerName.
    *******************************************************************************/
   public String getProviderName()
   {
      return (providerName);
   }


   /*******************************************************************************
    ** Fluent setter for providerName.
    *******************************************************************************/
   public EmailRequest withProviderName(String providerName)
   {
      this.providerName = providerName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for templateName.
    *******************************************************************************/
   public String getTemplateName()
   {
      return (templateName);
   }


   /*******************************************************************************
    ** Fluent setter for templateName.
    *******************************************************************************/
   public EmailRequest withTemplateName(String templateName)
   {
      this.templateName = templateName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for brandName.
    *******************************************************************************/
   public String getBrandName()
   {
      return (brandName);
   }


   /*******************************************************************************
    ** Fluent setter for brandName.
    *******************************************************************************/
   public EmailRequest withBrandName(String brandName)
   {
      this.brandName = brandName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for locale.
    *******************************************************************************/
   public String getLocale()
   {
      return (locale);
   }


   /*******************************************************************************
    ** Fluent setter for locale.
    *******************************************************************************/
   public EmailRequest withLocale(String locale)
   {
      this.locale = locale;
      return (this);
   }


   /*******************************************************************************
    ** Getter for category.
    *******************************************************************************/
   public EmailCategory getCategory()
   {
      return (category);
   }


   /*******************************************************************************
    ** Fluent setter for category.
    *******************************************************************************/
   public EmailRequest withCategory(EmailCategory category)
   {
      this.category = category;
      return (this);
   }


   /*******************************************************************************
    ** Getter for idempotencyKey.
    *******************************************************************************/
   public String getIdempotencyKey()
   {
      return (idempotencyKey);
   }


   /*******************************************************************************
    ** Fluent setter for idempotencyKey.
    *******************************************************************************/
   public EmailRequest withIdempotencyKey(String idempotencyKey)
   {
      this.idempotencyKey = idempotencyKey;
      return (this);
   }


   /*******************************************************************************
    ** Getter for sendAfter.
    *******************************************************************************/
   public Instant getSendAfter()
   {
      return (sendAfter);
   }


   /*******************************************************************************
    ** Fluent setter for sendAfter.
    *******************************************************************************/
   public EmailRequest withSendAfter(Instant sendAfter)
   {
      this.sendAfter = sendAfter;
      return (this);
   }


   /*******************************************************************************
    ** Getter for linkedTableName.
    *******************************************************************************/
   public String getLinkedTableName()
   {
      return (linkedTableName);
   }


   /*******************************************************************************
    ** Fluent setter for linkedTableName.
    *******************************************************************************/
   public EmailRequest withLinkedTableName(String linkedTableName)
   {
      this.linkedTableName = linkedTableName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for linkedRecordId.
    *******************************************************************************/
   public String getLinkedRecordId()
   {
      return (linkedRecordId);
   }


   /*******************************************************************************
    ** Fluent setter for linkedRecordId.
    *******************************************************************************/
   public EmailRequest withLinkedRecordId(String linkedRecordId)
   {
      this.linkedRecordId = linkedRecordId;
      return (this);
   }


   /*******************************************************************************
    ** Getter for senderQbit.
    *******************************************************************************/
   public String getSenderQbit()
   {
      return (senderQbit);
   }


   /*******************************************************************************
    ** Fluent setter for senderQbit.
    *******************************************************************************/
   public EmailRequest withSenderQbit(String senderQbit)
   {
      this.senderQbit = senderQbit;
      return (this);
   }


   /*******************************************************************************
    ** Getter for senderProcess.
    *******************************************************************************/
   public String getSenderProcess()
   {
      return (senderProcess);
   }


   /*******************************************************************************
    ** Fluent setter for senderProcess.
    *******************************************************************************/
   public EmailRequest withSenderProcess(String senderProcess)
   {
      this.senderProcess = senderProcess;
      return (this);
   }


   /*******************************************************************************
    ** Getter for transaction.
    *******************************************************************************/
   public QBackendTransaction getTransaction()
   {
      return (transaction);
   }


   /*******************************************************************************
    ** Fluent setter for transaction.
    *******************************************************************************/
   public EmailRequest withTransaction(QBackendTransaction transaction)
   {
      this.transaction = transaction;
      return (this);
   }


   /*******************************************************************************
    ** Getter for transactionBackendName.
    *******************************************************************************/
   public String getTransactionBackendName()
   {
      return (transactionBackendName);
   }


   /*******************************************************************************
    ** Fluent setter for transactionBackendName.
    *******************************************************************************/
   public EmailRequest withTransactionBackendName(String transactionBackendName)
   {
      this.transactionBackendName = transactionBackendName;
      return (this);
   }


   /*******************************************************************************
    ** Getter for variables.
    *******************************************************************************/
   public Map<String, Serializable> getVariables()
   {
      return (variables);
   }


   /*******************************************************************************
    ** Fluent setter for variables.
    *******************************************************************************/
   public EmailRequest withVariables(Map<String, Serializable> variables)
   {
      this.variables = variables;
      return (this);
   }


   /*******************************************************************************
    ** Getter for securityValues.
    *******************************************************************************/
   public Map<String, Serializable> getSecurityValues()
   {
      return (securityValues);
   }


   /*******************************************************************************
    ** Fluent setter for securityValues.
    *******************************************************************************/
   public EmailRequest withSecurityValues(Map<String, Serializable> securityValues)
   {
      this.securityValues = securityValues;
      return (this);
   }
}
