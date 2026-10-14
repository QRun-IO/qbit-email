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

package com.kingsrook.qbits.email.transport;


import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** Rendered, immutable-by-convention content passed to a transport.
 *******************************************************************************/
public class EmailEnvelope
{
   private String messageId;
   private String from;
   private List<String> to = List.of();
   private List<String> cc = List.of();
   private List<String> bcc = List.of();
   private String replyTo;
   private String subject;
   private String htmlBody;
   private String textBody;
   private Map<String, String> headers = Map.of();



   /*******************************************************************************
    ** Getter for messageId.
    *******************************************************************************/
   public String getMessageId()
   {
      return (messageId);
   }


   /*******************************************************************************
    ** Fluent setter for messageId.
    *******************************************************************************/
   public EmailEnvelope withMessageId(String messageId)
   {
      this.messageId = messageId;
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
   public EmailEnvelope withFrom(String from)
   {
      this.from = from;
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
   public EmailEnvelope withTo(List<String> to)
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
   public EmailEnvelope withCc(List<String> cc)
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
   public EmailEnvelope withBcc(List<String> bcc)
   {
      this.bcc = bcc;
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
   public EmailEnvelope withReplyTo(String replyTo)
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
   public EmailEnvelope withSubject(String subject)
   {
      this.subject = subject;
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
   public EmailEnvelope withHtmlBody(String htmlBody)
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
   public EmailEnvelope withTextBody(String textBody)
   {
      this.textBody = textBody;
      return (this);
   }


   /*******************************************************************************
    ** Getter for headers.
    *******************************************************************************/
   public Map<String, String> getHeaders()
   {
      return (headers);
   }


   /*******************************************************************************
    ** Fluent setter for headers.
    *******************************************************************************/
   public EmailEnvelope withHeaders(Map<String, String> headers)
   {
      this.headers = headers;
      return (this);
   }
}
