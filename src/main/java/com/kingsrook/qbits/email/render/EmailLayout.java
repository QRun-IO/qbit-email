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


import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.template.EmailTemplateEngine;
import com.kingsrook.qbits.email.template.VariableSchema;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Single-column, 600px email shell with inline fallbacks and dark-mode hooks.
 *******************************************************************************/
public final class EmailLayout
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailLayout()
   {
   }

   /*******************************************************************************
    ** Render both schemes from the same validated brand.
    *******************************************************************************/
   public static String render(String body, String preheader, QRecord brand, EmailCategory category, String unsubscribe)
   {
      EmailBrands.validate(brand);
      String dark = ".email-shell,.email-content{background-color:" + ContrastCheck.color(brand, "darkBackgroundColor")
         + "!important;color:" + ContrastCheck.color(brand, "darkTextColor") + "!important}.email-button{background-color:"
         + ContrastCheck.color(brand, "darkPrimaryColor") + "!important}.email-button a{color:" + ContrastCheck.color(brand, "darkButtonTextColor")
         + "!important}.email-logo-light{display:none!important}.email-logo-dark{display:block!important}";
      String outlook = "[data-ogsc] .email-content,[data-ogsc] .email-shell,[data-ogsb] .email-content,[data-ogsb] .email-shell{background-color:" + ContrastCheck.color(brand, "darkBackgroundColor")
         + "!important;color:" + ContrastCheck.color(brand, "darkTextColor") + "!important}[data-ogsc] .email-button,[data-ogsb] .email-button{background-color:"
         + ContrastCheck.color(brand, "darkPrimaryColor") + "!important}[data-ogsc] .email-button a,[data-ogsb] .email-button a{color:" + ContrastCheck.color(brand, "darkButtonTextColor")
         + "!important}[data-ogsc] .email-logo-light,[data-ogsb] .email-logo-light{display:none!important}[data-ogsc] .email-logo-dark,[data-ogsb] .email-logo-dark{display:block!important}";
      return ("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         + "<meta name=\"color-scheme\" content=\"light dark\"><meta name=\"supported-color-schemes\" content=\"light dark\">"
         + "<style>:root{color-scheme:light dark;supported-color-schemes:light dark}@media(max-width:640px){.email-content{padding:16px!important;width:100%!important}}"
         + "@media(prefers-color-scheme:dark){" + dark + "}" + outlook + "</style></head><body class=\"email-shell\" style=\"margin:0;padding:0;background-color:"
         + ContrastCheck.color(brand, "backgroundColor") + ";color:" + ContrastCheck.color(brand, "textColor") + "\">"
         + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all\">" + EmailTemplateEngine.escape(preheader) + "</div>"
         + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td align=\"center\">"
         + "<!--[if mso]><table role=\"presentation\" width=\"600\"><tr><td><![endif]-->"
         + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:600px;table-layout:fixed\"><tr>"
         + "<td class=\"email-content\" style=\"padding:24px;font-family:Arial,sans-serif;line-height:1.6;overflow-wrap:anywhere;word-break:break-word;background-color:"
         + ContrastCheck.color(brand, "backgroundColor") + ";color:" + ContrastCheck.color(brand, "textColor") + "\">" + logos(brand)
         + body + footer(brand, category, unsubscribe) + "</td></tr></table><!--[if mso]></td></tr></table><![endif]--></td></tr></table></body></html>");
   }

   /*******************************************************************************
    ** Logo alternatives, with dimensions that never force horizontal overflow.
    *******************************************************************************/
   private static String logos(QRecord brand)
   {
      String light = brand.getValueString("logoUrl");
      if(light == null || light.isBlank())
      {
         return ("<p><strong>" + EmailTemplateEngine.escape(brand.getValueString("label")) + "</strong></p>");
      }
      String dark = brand.getValueString("logoDarkUrl");
      return (logo(light, brand.getValueString("logoAlt"), dark == null ? "" : "email-logo-light", "block")
         + (dark == null ? "" : logo(dark, brand.getValueString("logoAlt"), "email-logo-dark", "none")));
   }

   /*******************************************************************************
    ** Escaped logo element.
    *******************************************************************************/
   private static String logo(String url, String alt, String css, String display)
   {
      return ("<img class=\"" + css + "\" src=\"" + EmailTemplateEngine.escape(VariableSchema.url(url)) + "\" alt=\""
         + EmailTemplateEngine.escape(alt) + "\" width=\"180\" style=\"display:" + display + ";max-width:100%;height:auto\">");
   }

   /*******************************************************************************
    ** Category-specific footer, including postal address for marketing.
    *******************************************************************************/
   public static String footer(QRecord brand, EmailCategory category, String unsubscribe)
   {
      StringBuilder footer = new StringBuilder("<footer style=\"margin-top:24px;font-size:12px;overflow-wrap:anywhere\">");
      footer.append(EmailMarkdown.render(brand.getValueString("footerMarkdown"), brand));
      if(category == EmailCategory.MARKETING)
      {
         String address = brand.getValueString("postalAddress");
         if(address == null || address.isBlank())
         {
            throw (new IllegalArgumentException("Marketing email needs a brand postal address"));
         }
         footer.append("<p>").append(EmailTemplateEngine.escape(address)).append("</p>");
      }
      for(var link : EmailBrands.social(brand))
      {
         footer.append("<a style=\"color:inherit\" href=\"").append(EmailTemplateEngine.escape(link.get("url"))).append("\">")
            .append(EmailTemplateEngine.escape(link.get("label"))).append("</a> ");
      }
      if((category == EmailCategory.NOTIFICATION || category == EmailCategory.MARKETING) && unsubscribe != null && !unsubscribe.isBlank())
      {
         footer.append("<p><a style=\"color:inherit\" href=\"").append(EmailTemplateEngine.escape(unsubscribe)).append("\">Unsubscribe</a></p>");
      }
      return (footer.append("</footer>").toString());
   }

   /*******************************************************************************
    ** HTML and compiled MJML retain their shell; append opt-out before body end.
    *******************************************************************************/
   public static String appendFooter(String html, QRecord brand, EmailCategory category, String unsubscribe)
   {
      if(category != EmailCategory.NOTIFICATION && category != EmailCategory.MARKETING)
      {
         return (html);
      }
      String footer = footer(brand, category, unsubscribe);
      java.util.regex.Matcher end = java.util.regex.Pattern.compile("</body\\s*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
      return (end.find() ? html.substring(0, end.start()) + footer + html.substring(end.start()) : html + footer);
   }
}
