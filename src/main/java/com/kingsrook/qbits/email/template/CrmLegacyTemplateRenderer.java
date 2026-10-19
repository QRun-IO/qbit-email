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

package com.kingsrook.qbits.email.template;


import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Compatibility for migrated CRM content. Customer values are never programs.
 *******************************************************************************/
public final class CrmLegacyTemplateRenderer
{
   private static final Pattern TOKENS = Pattern.compile("\\{\\{(contact.firstName|contact.lastName|company.name|owner.name|unsubscribeUrl)\\}\\}");

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CrmLegacyTemplateRenderer()
   {
   }

   /*******************************************************************************
    ** Preserve the original subject/body override and text-before-HTML behavior.
    *******************************************************************************/
   public static QRecord render(QRecord template, QRecord version, Map<String, Serializable> values, String subjectOverride, String bodyOverride)
   {
      if(!"crm".equals(template.getValueString("ownerQbit")) || Boolean.TRUE.equals(template.getValueBoolean("phiApproved")))
      {
         throw (new IllegalArgumentException("Legacy CRM content requires CRM ownership and cannot be PHI approved"));
      }
      String subject = subjectOverride == null || subjectOverride.isBlank() ? version.getValueString("subject") : subjectOverride;
      String body = bodyOverride == null || bodyOverride.isBlank() ? version.getValueString("textSource") : bodyOverride;
      if(body == null)
      {
         body = version.getValueString("bodySource");
      }
      if(subject == null || subject.isBlank() || body == null || body.isBlank())
      {
         throw (new IllegalArgumentException("Email subject and body are required"));
      }
      subject = replace(subject, values);
      body = replace(body, values);
      if(subject.contains("\r") || subject.contains("\n") || (long) subject.getBytes(StandardCharsets.UTF_8).length + body.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
      {
         throw (new IllegalArgumentException("Invalid or oversized template output"));
      }
      return (new QRecord().withValue("subject", subject).withValue("markdownBody", body)
         .withValue("templateId", template.getValue("id")).withValue("templateVersionId", version.getValue("id")));
   }

   /*******************************************************************************
    ** Replace only tokens in the original source, retaining unknown literals.
    *******************************************************************************/
   private static String replace(String source, Map<String, Serializable> values)
   {
      Matcher matcher = TOKENS.matcher(source);
      StringBuilder result = new StringBuilder();
      while(matcher.find())
      {
         Serializable value = values.get(matcher.group(1));
         matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value.toString()));
      }
      matcher.appendTail(result);
      return (result.toString());
   }
}
