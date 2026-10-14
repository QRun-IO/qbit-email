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


import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** Stable enqueue request. One send always goes through the outbox.
 ** Null category means TRANSACTIONAL for ad-hoc mail; templates select their category.
 ** Recipient lists and variable maps are mutable request-building state.
 *******************************************************************************/
public final class EmailApiRequest
{
   private List<String> to = new ArrayList<>();
   private List<String> cc = new ArrayList<>();
   private List<String> bcc = new ArrayList<>();
   private String from;
   private Integer fromAddressId;
   private String replyTo;
   private String subject;
   private String markdownBody;
   private String htmlBody;
   private String textBody;
   private String providerName;
   private String templateName;
   private String brandName;
   private String locale = "en";
   private EmailApiCategory category;
   private String idempotencyKey;
   private Instant sendAfter;
   private String linkedTableName;
   private String linkedRecordId;
   private String senderQbit;
   private String senderProcess;
   private Map<String, Serializable> variables = new LinkedHashMap<>();
   private Map<String, RecordVariable> recordVariables = new LinkedHashMap<>();



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
   public EmailApiRequest withTo(List<String> to)
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
   public EmailApiRequest withCc(List<String> cc)
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
   public EmailApiRequest withBcc(List<String> bcc)
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
   public EmailApiRequest withFrom(String from)
   {
      this.from = from;
      return (this);
   }



   /*******************************************************************************
    ** Getter for fromAddressId.
    *******************************************************************************/
   public Integer getFromAddressId()
   {
      return (fromAddressId);
   }



   /*******************************************************************************
    ** Fluent setter for fromAddressId.
    *******************************************************************************/
   public EmailApiRequest withFromAddressId(Integer fromAddressId)
   {
      this.fromAddressId = fromAddressId;
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
   public EmailApiRequest withReplyTo(String replyTo)
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
   public EmailApiRequest withSubject(String subject)
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
   public EmailApiRequest withMarkdownBody(String markdownBody)
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
   public EmailApiRequest withHtmlBody(String htmlBody)
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
   public EmailApiRequest withTextBody(String textBody)
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
   public EmailApiRequest withProviderName(String providerName)
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
   public EmailApiRequest withTemplateName(String templateName)
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
   public EmailApiRequest withBrandName(String brandName)
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
   public EmailApiRequest withLocale(String locale)
   {
      this.locale = locale;
      return (this);
   }



   /*******************************************************************************
    ** Getter for category.
    *******************************************************************************/
   public EmailApiCategory getCategory()
   {
      return (category);
   }



   /*******************************************************************************
    ** Fluent setter for category.
    *******************************************************************************/
   public EmailApiRequest withCategory(EmailApiCategory category)
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
   public EmailApiRequest withIdempotencyKey(String idempotencyKey)
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
   public EmailApiRequest withSendAfter(Instant sendAfter)
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
   public EmailApiRequest withLinkedTableName(String linkedTableName)
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
   public EmailApiRequest withLinkedRecordId(String linkedRecordId)
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
   public EmailApiRequest withSenderQbit(String senderQbit)
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
   public EmailApiRequest withSenderProcess(String senderProcess)
   {
      this.senderProcess = senderProcess;
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
   public EmailApiRequest withVariables(Map<String, Serializable> variables)
   {
      this.variables = variables;
      return (this);
   }



   /*******************************************************************************
    ** Getter for recordVariables.
    *******************************************************************************/
   public Map<String, RecordVariable> getRecordVariables()
   {
      return (recordVariables);
   }



   /*******************************************************************************
    ** Fluent setter for recordVariables.
    *******************************************************************************/
   public EmailApiRequest withRecordVariables(Map<String, RecordVariable> recordVariables)
   {
      this.recordVariables = recordVariables;
      return (this);
   }



   /*******************************************************************************
    ** Append to recipients. Sending still enforces category and PHI recipient rules.
    *******************************************************************************/
   public EmailApiRequest withTo(String... addresses)
   {
      to.addAll(List.of(addresses));
      return (this);
   }



   /*******************************************************************************
    ** Append cc recipients. Sending still enforces category and PHI recipient rules.
    *******************************************************************************/
   public EmailApiRequest withCc(String... addresses)
   {
      cc.addAll(List.of(addresses));
      return (this);
   }



   /*******************************************************************************
    ** Append bcc recipients. Sending still enforces category and PHI recipient rules.
    *******************************************************************************/
   public EmailApiRequest withBcc(String... addresses)
   {
      bcc.addAll(List.of(addresses));
      return (this);
   }



   /*******************************************************************************
    ** Link the message to an application record.
    *******************************************************************************/
   public EmailApiRequest withLinkedRecord(String table, Object id)
   {
      linkedTableName = table;
      linkedRecordId = id == null ? null : String.valueOf(id);
      return (this);
   }



   /*******************************************************************************
    ** Identify the producing qbit and process.
    *******************************************************************************/
   public EmailApiRequest withSender(String qbit, String process)
   {
      senderQbit = qbit;
      senderProcess = process;
      return (this);
   }



   /*******************************************************************************
    ** Bind one variable; template schema and classification checks still apply.
    *******************************************************************************/
   public EmailApiRequest withVariable(String name, Serializable value)
   {
      variables.put(name, value);
      return (this);
   }



   /*******************************************************************************
    ** Bind record provenance without reading or snapshotting the record.
    *******************************************************************************/
   public EmailApiRequest withRecordVariable(String name, String table, Serializable pk)
   {
      recordVariables.put(name, new RecordVariable(table, pk));
      return (this);
   }



   /*******************************************************************************
    ** A record reference; values are resolved and classified by the sending service.
    *******************************************************************************/
   public record RecordVariable(String table, Serializable id)
   {
   }
}
