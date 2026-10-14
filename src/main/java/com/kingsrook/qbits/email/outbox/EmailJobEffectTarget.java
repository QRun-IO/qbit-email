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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.jobs.DurableJobsMetaData;
import com.kingsrook.qqq.backend.core.jobs.JobSessions;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectRequest;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectResult;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectTarget;
import com.kingsrook.qqq.backend.core.jobs.effects.ReconcileResult;
import com.kingsrook.qqq.backend.core.jobs.store.DurableJobStore;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;


/*******************************************************************************
 ** Durable jobs target for IDEMPOTENT_EXTERNAL email effects. SENT acknowledges
 ** the committed email outbox, whose key deduplication makes retries safe. The
 ** email dispatcher owns provider delivery and preserves the stored Message-ID.
 ** Payload keys and types are documented in docs/DURABLE-JOBS.md.
 *******************************************************************************/
public class EmailJobEffectTarget implements EffectTarget
{
   public static final String TARGET_NAME = "email";



   /*******************************************************************************
    ** Register after enabling durable jobs; leave instances without jobs alone.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      DurableJobsMetaData jobs = DurableJobsMetaData.of(instance);
      if(jobs != null)
      {
         jobs.withEffectTarget(TARGET_NAME, new QCodeReference(EmailJobEffectTarget.class));
      }
   }



   /*******************************************************************************
    ** Enqueue under the job owner's current permissions and captured scope.
    ** Other exceptions are uncertain; core may safely resend using the same key.
    *******************************************************************************/
   @Override
   public EffectResult deliver(EffectRequest r)
   {
      try
      {
         EmailRequest request = decode(r.payload()).withSender("jobs", r.effectName());
         EmailEffects.Result result;
         if(r.jobId() == null)
         {
            result = EmailEffects.send(request, r.idempotencyKey());
         }
         else
         {
            QInstance instance = QContext.getQInstance();
            QSession owner;
            try
            {
               QRecord job = new DurableJobStore(DurableJobsMetaData.of(instance)).getJob(r.jobId());
               if(job == null)
               {
                  return (EffectResult.failed("The job's owner can no longer send it: job not found"));
               }
               owner = JobSessions.forJob(instance, job);
            }
            catch(QPermissionDeniedException | QAuthenticationException e)
            {
               return (EffectResult.failed("The job's owner can no longer send it: owner session unavailable"));
            }
            AtomicReference<EmailEffects.Result> handoff = new AtomicReference<>();
            QContext.withTemporaryContext(new CapturedContext(instance, owner),
               () -> handoff.set(EmailEffects.send(request, r.idempotencyKey())));
            result = handoff.get();
         }
         if(result.outcome() == EmailEffectOutcome.REFUSED)
         {
            return (EffectResult.failed(String.join("; ", result.reasons())));
         }
         return (EffectResult.sent(String.valueOf(result.message().id())));
      }
      catch(Exception e)
      {
         // Exception details can contain payloads or database values.
         return (EffectResult.unknown("Email handoff could not be confirmed (" + e.getClass().getSimpleName() + ")"));
      }
   }



   /*******************************************************************************
    ** Reconcile handoff by existence, including suppressed and terminal audit rows.
    *******************************************************************************/
   @Override
   public ReconcileResult reconcile(EffectRequest r) throws QException
   {
      return (EmailEffects.findByIdempotencyKey(r.idempotencyKey())
         .map(message -> ReconcileResult.found(String.valueOf(message.id())))
         .orElseGet(ReconcileResult::notFound));
   }



   /*******************************************************************************
    ** Decode only supported values; identity, sender and transaction cannot be
    ** overridden by payload keys. Variables retain normal template/PHI validation.
    *******************************************************************************/
   static EmailRequest decode(Map<String, Serializable> payload)
   {
      Map<String, Serializable> values = payload == null ? Map.of() : payload;
      EmailRequest request = new EmailRequest()
         .withTo(addresses(values.get("to")))
         .withCc(addresses(values.get("cc")))
         .withBcc(addresses(values.get("bcc")))
         .withFrom((String) values.get("from"))
         .withFromAddressId((Integer) values.get("fromAddressId"))
         .withReplyTo((String) values.get("replyTo"))
         .withSubject((String) values.get("subject"))
         .withTemplateName((String) values.get("templateName"))
         .withMarkdownBody((String) values.get("markdownBody"))
         .withHtmlBody((String) values.get("htmlBody"))
         .withTextBody((String) values.get("textBody"))
         .withProviderName((String) values.get("providerName"))
         .withBrandName((String) values.get("brandName"))
         .withLinkedRecord((String) values.get("linkedTableName"), values.get("linkedRecordId"));
      if(values.get("locale") != null)
      {
         request.withLocale((String) values.get("locale"));
      }
      if(values.get("category") != null)
      {
         request.withCategory(EmailCategory.valueOf((String) values.get("category")));
      }
      if(values.get("sendAfter") != null)
      {
         request.withSendAfter(Instant.parse((String) values.get("sendAfter")));
      }
      if(values.get("variables") instanceof Map<?, ?> variables)
      {
         variables.forEach((key, value) -> request.withVariable((String) key, (Serializable) value));
      }
      else if(values.get("variables") != null)
      {
         throw (new IllegalArgumentException("variables must be a map"));
      }
      return (request);
   }



   /*******************************************************************************
    ** Accept one mailbox or a list of mailboxes, without comma splitting.
    *******************************************************************************/
   private static List<String> addresses(Object value)
   {
      if(value == null)
      {
         return (List.of());
      }
      if(value instanceof String address)
      {
         return (List.of(address));
      }
      return (((List<?>) value).stream().map(String.class::cast).toList());
   }
}
