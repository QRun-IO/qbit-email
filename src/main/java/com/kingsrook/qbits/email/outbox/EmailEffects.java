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


import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Outbox handoff and system-read reconciliation for trusted effect dispatchers.
 ** This API has no dependency on core durable-job types. Sending retains the
 ** EmailService permission checks; callers must supply a request without a transaction.
 *******************************************************************************/
public final class EmailEffects
{
   /*******************************************************************************
    ** A refusal can have a reference when suppression retained an audit row.
    *******************************************************************************/
   public record Result(EmailEffectOutcome outcome, EmailMessageRef message)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailEffects()
   {
   }



   /*******************************************************************************
    ** Hand off once with the effect's identity. Refuse invalid or conflicting keys
    ** and caller transactions: success must mean the outbox has committed already.
    ** Database failures throw QException and must not become a permanent refusal.
    *******************************************************************************/
   public static Result send(EmailRequest request, String effectIdempotencyKey) throws QException
   {
      if(request == null || effectIdempotencyKey == null || effectIdempotencyKey.isBlank() || effectIdempotencyKey.length() > 500
         || request.getTransaction() != null
         || request.getIdempotencyKey() != null && !request.getIdempotencyKey().equals(effectIdempotencyKey))
      {
         return (new Result(EmailEffectOutcome.REFUSED, null));
      }
      request.withIdempotencyKey(effectIdempotencyKey);
      EmailSendResult result = EmailService.send(request);
      EmailMessageRef message = result.messageId() == null ? null : find("id", result.messageId()).orElseThrow(() -> new QException("Queued email reference is unavailable"));
      boolean refused = result.status() == EmailSendResult.Status.REFUSED || result.status() == EmailSendResult.Status.SUPPRESSED
         || message != null && "SUPPRESSED".equals(message.status());
      return (new Result(refused ? EmailEffectOutcome.REFUSED : EmailEffectOutcome.QUEUED_OR_DUPLICATE, message));
   }



   /*******************************************************************************
    ** Look up a committed outbox row by its exact effect or application key.
    *******************************************************************************/
   public static Optional<EmailMessageRef> findByIdempotencyKey(String key) throws QException
   {
      return (key == null || key.isBlank() ? Optional.empty() : find("idempotencyKey", key));
   }



   /*******************************************************************************
    ** Look up the exact bracketed RFC Message-ID stored at enqueue time.
    *******************************************************************************/
   public static Optional<EmailMessageRef> findByRfcMessageId(String messageId) throws QException
   {
      return (messageId == null || messageId.isBlank() ? Optional.empty() : find("rfcMessageId", messageId));
   }



   /*******************************************************************************
    ** Use the same authorized read scope as the dispatcher; expose only references.
    *******************************************************************************/
   private static Optional<EmailMessageRef> find(String field, java.io.Serializable value) throws QException
   {
      List<QRecord> rows = SystemWrites.query(EmailTables.MESSAGE, EmailStore.equal(field, value).withLimit(1));
      if(rows.isEmpty())
      {
         return (Optional.empty());
      }
      QRecord row = rows.getFirst();
      return (Optional.of(new EmailMessageRef(row.getValueInteger("id"), row.getValueString("status"), row.getValueString("rfcMessageId"), row.getValueString("providerMessageId"))));
   }
}
