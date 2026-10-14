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

package com.kingsrook.qbits.email.support;


import com.kingsrook.qbits.email.api.EmailApiRequest;
import com.kingsrook.qbits.email.api.EmailApiResult;
import com.kingsrook.qbits.email.api.EmailApiStatus;
import com.kingsrook.qbits.email.api.EmailApiTemplate;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.template.EmailTemplateDefinition;


/*******************************************************************************
 ** Internal adapter between the stable contract and the implementation.
 *******************************************************************************/
public final class EmailApiMapper
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailApiMapper()
   {
   }



   /*******************************************************************************
    ** Map every published request field without bypassing send validation.
    *******************************************************************************/
   public static EmailRequest request(EmailApiRequest request)
   {
      if(request == null)
      {
         return (null);
      }
      EmailRequest mapped = new EmailRequest()
         .withTo(request.getTo())
         .withCc(request.getCc())
         .withBcc(request.getBcc())
         .withFrom(request.getFrom())
         .withFromAddressId(request.getFromAddressId())
         .withReplyTo(request.getReplyTo())
         .withSubject(request.getSubject())
         .withMarkdownBody(request.getMarkdownBody())
         .withHtmlBody(request.getHtmlBody())
         .withTextBody(request.getTextBody())
         .withProviderName(request.getProviderName())
         .withTemplateName(request.getTemplateName())
         .withBrandName(request.getBrandName())
         .withLocale(request.getLocale())
         .withIdempotencyKey(request.getIdempotencyKey())
         .withTransaction(request.getTransaction(), request.getTransactionBackendName())
         .withSendAfter(request.getSendAfter())
         .withLinkedTableName(request.getLinkedTableName())
         .withLinkedRecordId(request.getLinkedRecordId())
         .withSenderQbit(request.getSenderQbit())
         .withSenderProcess(request.getSenderProcess())
         .withVariables(request.getVariables())
         .withCategory(request.getCategory() == null ? null : EmailCategory.valueOf(request.getCategory().name()));
      request.getRecordVariables().forEach((name, reference) -> mapped.withRecordVariable(name, reference.table(), reference.id()));
      return (mapped);
   }



   /*******************************************************************************
    ** Map the complete create-once template contract.
    *******************************************************************************/
   public static EmailTemplateDefinition template(EmailApiTemplate template)
   {
      EmailTemplateDefinition mapped = new EmailTemplateDefinition()
         .withName(template.getName())
         .withLabel(template.getLabel())
         .withKind(template.getKind())
         .withSubject(template.getSubject())
         .withBody(template.getBody())
         .withTextBody(template.getTextBody())
         .withVariablesSchema(template.getVariablesSchema())
         .withSampleVariables(template.getSampleVariables())
         .withOwnerQbit(template.getOwnerQbit())
         .withEditPermissionName(template.getEditPermissionName())
         .withCategory(template.getCategory() == null ? null : EmailCategory.valueOf(template.getCategory().name()));
      template.getVariables().forEach(variable -> mapped.withVariable(variable.name(), variable.label(), variable.type(), variable.required(), variable.classification()));
      return (mapped);
   }



   /*******************************************************************************
    ** Map enqueue outcomes by their stable names.
    *******************************************************************************/
   public static EmailApiResult result(EmailSendResult result)
   {
      return (new EmailApiResult(result.messageId(), EmailApiStatus.valueOf(result.status().name()), result.reasons()));
   }
}
