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
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;


/*******************************************************************************
 ** Minimal part 1 layout. Markdown raw HTML and unsafe URL schemes are disabled.
 *******************************************************************************/
public class AdHocEmailRenderer implements EmailRenderer
{
   /*******************************************************************************
    ** Render markdown, HTML, or plain text into both alternatives.
    *******************************************************************************/
   @Override
   public Rendered render(EmailRequest request)
   {
      String html = request.getHtmlBody();
      if(html == null)
      {
         if(request.getMarkdownBody() != null)
         {
            var extensions = List.of(TablesExtension.create(), AutolinkExtension.create());
            html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).extensions(extensions).build()
               .render(Parser.builder().extensions(extensions).build().parse(request.getMarkdownBody()));
         }
         else
         {
            html = "<pre>" + escape(request.getTextBody()) + "</pre>";
         }
      }
      String text = request.getTextBody();
      if(text == null)
      {
         text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
            .replaceAll("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>", "\n")
            .replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&").strip();
      }
      return (new Rendered("<!doctype html><html><body><main>" + html + "</main></body></html>", text));
   }

   /*******************************************************************************
    ** Escape plain text for the fallback HTML part.
    *******************************************************************************/
   private static String escape(String text)
   {
      return (text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
   }
}
