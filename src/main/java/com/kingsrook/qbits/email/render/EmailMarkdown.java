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

package com.kingsrook.qbits.email.render;


import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;


/*******************************************************************************
 ** CommonMark with GFM tables, autolinks and a constrained button attribute.
 *******************************************************************************/
public final class EmailMarkdown
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailMarkdown()
   {
   }

   /*******************************************************************************
    ** Raw HTML is escaped and URLs sanitized before adding table buttons.
    *******************************************************************************/
   public static String render(String markdown, QRecord brand)
   {
      var extensions = List.of(TablesExtension.create(), AutolinkExtension.create());
      String html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).extensions(extensions).build()
         .render(Parser.builder().extensions(extensions).build().parse(markdown == null ? "" : markdown))
         .replace("<a ", "<a style=\"color:inherit;text-decoration:underline\" ");
      Matcher links = Pattern.compile("<a[^>]* href=\"([^\"]*)\"[^>]*>((?:(?!</?a\\b)[^\\r\\n])*)</a>\\{\\.button}").matcher(html);
      StringBuilder result = new StringBuilder();
      while(links.find())
      {
         String button = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\"><tr><td class=\"email-button\" bgcolor=\""
            + ContrastCheck.color(brand, "primaryColor") + "\" style=\"border-radius:4px;padding:12px 20px;background-color:"
            + ContrastCheck.color(brand, "primaryColor") + "\"><a href=\"" + links.group(1) + "\" style=\"display:inline-block;text-decoration:none;color:"
            + ContrastCheck.color(brand, "buttonTextColor") + ";font-weight:bold\">" + links.group(2) + "</a></td></tr></table>";
         links.appendReplacement(result, Matcher.quoteReplacement(button));
      }
      links.appendTail(result);
      return (result.toString().replace("<p><table", "<table").replace("</table></p>", "</table>").replace("<table>", "<table style=\"width:100%;table-layout:fixed;border-collapse:collapse\">")
         .replace("<td>", "<td style=\"padding:6px;border:1px solid #888;overflow-wrap:anywhere\">")
         .replace("<th>", "<th style=\"padding:6px;border:1px solid #888;overflow-wrap:anywhere\">")
         .replace("<img ", "<img style=\"max-width:100%;height:auto\" ").replace("<pre>", "<pre style=\"white-space:pre-wrap;overflow-wrap:anywhere\">"));
   }
}
