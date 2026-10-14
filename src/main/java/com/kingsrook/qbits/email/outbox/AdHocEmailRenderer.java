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




/*******************************************************************************
 ** Branded ad-hoc rendering. Markdown raw HTML and unsafe URL schemes are disabled.
 *******************************************************************************/
public class AdHocEmailRenderer implements EmailRenderer
{
   /*******************************************************************************
    ** Render markdown, HTML, or plain text into both alternatives.
    *******************************************************************************/
   @Override
   public Rendered render(EmailRequest request)
   {
      try
      {
         var brand = com.kingsrook.qbits.email.render.EmailBrands.resolve(request.getBrandName(), null);
         String html = request.getHtmlBody();
         if(html == null)
         {
            String body = request.getMarkdownBody() == null ? "<pre>" + com.kingsrook.qbits.email.template.EmailTemplateEngine.escape(request.getTextBody()) + "</pre>"
               : com.kingsrook.qbits.email.render.EmailMarkdown.render(request.getMarkdownBody(), brand);
            html = com.kingsrook.qbits.email.render.EmailLayout.render(body, "", brand,
               request.getCategory() == null ? EmailCategory.TRANSACTIONAL : request.getCategory(), "");
         }
         return (new Rendered(html, request.getTextBody() == null ? com.kingsrook.qbits.email.render.HtmlToText.convert(html) : request.getTextBody()));
      }
      catch(com.kingsrook.qqq.backend.core.exceptions.QException e)
      {
         throw (new IllegalArgumentException("Email brand is unavailable"));
      }
   }
}
