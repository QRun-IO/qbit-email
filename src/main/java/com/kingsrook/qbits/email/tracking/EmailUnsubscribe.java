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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.render.EmailUnsubscribeLinks;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.template.EmailTemplateEngine;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import jakarta.mail.internet.InternetAddress;
import org.json.JSONObject;


/*******************************************************************************
 ** Stateless token validation and atomic, idempotent category opt-out.
 *******************************************************************************/
public final class EmailUnsubscribe
{
   /*******************************************************************************
    ** Resolve the signed message and unchanged recipient envelope, without writes.
    *******************************************************************************/
   public QRecord resolve(String token) throws QException
   {
      JSONObject payload = EmailUnsubscribeLinks.verify(token);
      if(payload == null)
      {
         return (null);
      }
      try
      {
         QRecord message = EmailStore.get(EmailTables.MESSAGE, Integer.valueOf(payload.getString("messageId")), null);
         if(message == null || !payload.getString("category").equals(message.getValueString("category")))
         {
            return (null);
         }
         String recipients = message.getValueString("toAddresses") + "," + message.getValueString("ccAddresses") + "," + message.getValueString("bccAddresses");
         return (WebhookVerification.equal(payload.getString("addressHash"), WebhookVerification.hash(recipients.getBytes(StandardCharsets.UTF_8))) ? message : null);
      }
      catch(IllegalArgumentException | org.json.JSONException e)
      {
         return (null);
      }
   }

   /*******************************************************************************
    ** The legacy token binds the complete envelope, so opt out every bound mailbox.
    ** Never display those addresses on the public page or emit them to logs.
    *******************************************************************************/
   public void unsubscribe(String token) throws QException
   {
      QRecord initial = resolve(token);
      if(initial == null)
      {
         throw (new IllegalArgumentException("Invalid unsubscribe link"));
      }
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         EmailTrackingStore.lock(initial.getValueInteger("providerId"), tx);
         QRecord message = EmailStore.get(EmailTables.MESSAGE, initial.getValueInteger("id"), tx);
         if(message == null || !List.of("MARKETING", "NOTIFICATION").contains(message.getValueString("category")))
         {
            throw (new IllegalArgumentException("Invalid unsubscribe link"));
         }
         for(String recipient : recipients(message))
         {
            String key = "unsubscribe:" + message.getValueInteger("id") + ":" + WebhookVerification.hash(recipient.getBytes(StandardCharsets.UTF_8));
            var event = new EmailEventParser.Event(key, message.getValueString("id"), null, "UNSUBSCRIBED", null, recipient, EmailRuntime.clock().instant(), null, null);
            QRecord stored = EmailTrackingStore.event(message, event, key, tx);
            if(stored == null)
            {
               stored = com.kingsrook.qbits.email.support.SystemWrites.query(EmailTables.EVENT, EmailStore.equal("eventKey", key), tx).getFirst();
            }
            EmailTrackingStore.suppress(message, stored, message.getValueString("category"), "UNSUBSCRIBE", tx);
         }
         tx.commit();
      }
   }

   /*******************************************************************************
    ** RFC mailbox parsing preserves quoted display names and deduplicates mailboxes.
    *******************************************************************************/
   private static Set<String> recipients(QRecord message)
   {
      Set<String> recipients = new LinkedHashSet<>();
      try
      {
         for(String field : List.of("toAddresses", "ccAddresses", "bccAddresses"))
         {
            String value = message.getValueString(field);
            if(value != null && !value.isBlank())
            {
               for(InternetAddress address : InternetAddress.parse(value, true))
               {
                  recipients.add(EmailTrackingStore.address(address.getAddress()));
               }
            }
         }
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Invalid unsubscribe recipients"));
      }
      return (recipients);
   }

   /*******************************************************************************
    ** Self-contained accessible page; an empty form action posts to this same URL.
    *******************************************************************************/
   public String page(QRecord message, boolean confirmed) throws QException
   {
      QRecord brand = message.getValueInteger("brandId") == null ? null : EmailStore.get(EmailTables.BRAND, message.getValueInteger("brandId"), null);
      String label = brand == null || brand.getValueString("label") == null ? EmailRuntime.config().getAppLabel() : brand.getValueString("label");
      String category = "MARKETING".equals(message.getValueString("category")) ? "Marketing" : "Notification";
      String question = confirmed ? "You are unsubscribed from " + category + " email from " + label + "."
         : "Unsubscribe from " + category + " email from " + label + "?";
      return ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         + "<meta name=\"color-scheme\" content=\"light dark\"><title>Email preferences</title><style>"
         + ":root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;padding:20px;font:18px/1.5 system-ui;background:#fff;color:#172033}"
         + "main{max-width:560px;margin:40px auto;overflow-wrap:anywhere}h1{font-size:1.5rem}button{max-width:100%;padding:12px 20px;font:inherit;background:#174ea6;color:#fff;border:0;border-radius:6px;cursor:pointer}"
         + "@media(prefers-color-scheme:dark){body{background:#111827;color:#f9fafb}button{background:#a9c7ff;color:#111827}}</style></head><body><main><p>"
         + EmailTemplateEngine.escape(label) + "</p><h1>" + EmailTemplateEngine.escape(question) + "</h1>"
         + (confirmed ? "" : "<form method=\"post\"><input type=\"hidden\" name=\"confirm\" value=\"unsubscribe\"><button type=\"submit\">Unsubscribe</button></form>")
         + "</main></body></html>");
   }
}
