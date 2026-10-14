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

package com.kingsrook.qbits.email.bridge;


import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.messaging.MultiParty;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Party;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageOutput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.email.EmailContentRole;
import com.kingsrook.qqq.backend.core.model.actions.messaging.email.EmailPartyRole;
import com.kingsrook.qqq.backend.core.modules.messaging.MessagingProviderInterface;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** Adapts core messaging calls to durable asynchronous outbox inserts.
 *******************************************************************************/
public class EmailOutboxMessagingProvider implements MessagingProviderInterface
{
   /*******************************************************************************
    ** Distinct provider type.
    *******************************************************************************/
   @Override
   public String getType()
   {
      return (EmailOutboxMessagingProviderMetaData.TYPE);
   }

   /*******************************************************************************
    ** Map core parties and alternatives; refuse unsupported attachments.
    *******************************************************************************/
   @Override
   public SendMessageOutput sendMessage(SendMessageInput input) throws QException
   {
      if(input.getAttachmentList() != null && !input.getAttachmentList().isEmpty())
      {
         throw (new QException("Email attachments are not supported in part 1"));
      }
      EmailRequest request = new EmailRequest().withSubject(input.getSubject());
      parties(request, input.getTo(), EmailPartyRole.TO);
      parties(request, input.getFrom(), EmailPartyRole.FROM);
      if(input.getContentList() != null)
      {
         for(var content : input.getContentList())
         {
            if(content.getContentRole() == EmailContentRole.HTML)
            {
               request.withHtmlBody(content.getBody());
            }
            else if(content.getContentRole() == EmailContentRole.TEXT || content.getContentRole() == null)
            {
               request.withTextBody(content.getBody());
            }
            else
            {
               throw (new QException("Unsupported email content role"));
            }
         }
      }
      EmailSendResult result = EmailService.send(request);
      if(result.status() == EmailSendResult.Status.REFUSED)
      {
         throw (new QException(String.join("; ", result.reasons())));
      }
      return (new SendMessageOutput());
   }

   /*******************************************************************************
    ** Walk nested MultiParty inputs, inheriting a parent role when omitted.
    *******************************************************************************/
   private static void parties(EmailRequest request, Party party, EmailPartyRole inherited) throws QException
   {
      if(party == null)
      {
         return;
      }
      EmailPartyRole role = party.getRole() instanceof EmailPartyRole explicit ? explicit : inherited;
      if(party instanceof MultiParty multi)
      {
         if(multi.getPartyList() != null)
         {
            for(Party child : multi.getPartyList())
            {
               parties(request, child, role);
            }
         }
         return;
      }
      String address = party.getAddress();
      try
      {
         if(party.getLabel() != null)
         {
            address = new InternetAddress(address, party.getLabel(), "UTF-8").toString();
         }
      }
      catch(Exception e)
      {
         throw (new QException("Invalid email party"));
      }
      switch(role)
      {
         case TO -> request.withTo(address);
         case CC -> request.withCc(address);
         case BCC -> request.withBcc(address);
         case FROM -> request.withFrom(address);
         case REPLY_TO -> request.withReplyTo(address);
         default -> throw (new QException("Unsupported email party role"));
      }
   }
}
