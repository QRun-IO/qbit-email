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


import java.util.List;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.render.ContrastCheck;
import com.kingsrook.qbits.email.render.EmailBrands;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;


/*******************************************************************************
 ** Inline preview fallback: Next strips iframes and style blocks. Samples are
 ** sanitized server-side too; no live record references are read for previews.
 *******************************************************************************/
public class EmailPreviewWidget extends AbstractWidgetRenderer
{
   /*******************************************************************************
    ** Fetch the version and parent as the viewer, then render sample data.
    *******************************************************************************/
   @Override
   public RenderWidgetOutput render(RenderWidgetInput input) throws QException
   {
      try
      {
         int id = Integer.parseInt(input.getQueryParams().get("id"));
         List<QRecord> versions = new QueryAction().execute(new QueryInput(EmailTables.VERSION).withShouldFetchHeavyFields(true)
            .withFilter(EmailStore.equal("id", id))).getRecords();
         if(versions.isEmpty())
         {
            return (output("<p>Version unavailable.</p>"));
         }
         QRecord version = versions.getFirst();
         List<QRecord> templates = new QueryAction().execute(new QueryInput(EmailTables.TEMPLATE)
            .withFilter(EmailStore.equal("id", version.getValueInteger("templateId")))).getRecords();
         if(templates.isEmpty())
         {
            return (output("<p>Template unavailable.</p>"));
         }
         QRecord template = templates.getFirst();
         if("MJML".equals(template.getValueString("kind")) && version.getValueString("compiledHtml") == null)
         {
            EmailVersions.validate(template, version);
         }
         QRecord rendered = new EmailTemplateRenderer(template, version,
            new EmailRequest().withVariables(VariableSchema.values(version.getValueString("sampleVariables"))))
            .render("preview", "https://example.invalid/unsubscribe/preview", false);
         QRecord brand = EmailBrands.resolve(null, template);
         String html = "<div style=\"min-width:0;overflow-wrap:anywhere\"><h3>" + EmailTemplateEngine.escape(rendered.getValueString("subject")) + "</h3>"
            + "<p>Sample preview. Inline rendering; mail clients may display it differently.</p>"
            + sample(rendered.getValueString("htmlBody"), brand, false, 390) + sample(rendered.getValueString("htmlBody"), brand, true, 600)
            + "<h4>Plain text</h4><pre style=\"white-space:pre-wrap;overflow-wrap:anywhere;max-width:100%\">"
            + EmailTemplateEngine.escape(rendered.getValueString("textBody")) + "</pre></div>";
         return (output(html));
      }
      catch(IllegalArgumentException e)
      {
         return (output("<p>Preview unavailable: " + EmailTemplateEngine.escape(e.getMessage()) + "</p>"));
      }
   }

   /*******************************************************************************
    ** Safe inline sample with bounded width and no executable source or CSS.
    *******************************************************************************/
   private static String sample(String html, QRecord brand, boolean dark, int width)
   {
      var document = Jsoup.parse(html);
      document.select("head,script,style,iframe,form").remove();
      document.select(".email-logo-" + (dark ? "light" : "dark")).remove();
      String clean = Jsoup.clean(document.body().html(), Safelist.relaxed().addAttributes("td", "class"));
      document = Jsoup.parseBodyFragment(clean);
      document.select("table").forEach(e -> e.attr("style", "width:100%;table-layout:fixed;border-collapse:collapse"));
      document.select("td,th").forEach(e -> e.attr("style", "overflow-wrap:anywhere;padding:6px"));
      document.select("img").forEach(e -> e.attr("style", "max-width:100%;height:auto"));
      document.select("pre").forEach(e -> e.attr("style", "white-space:pre-wrap;overflow-wrap:anywhere"));
      document.select("a").forEach(e -> e.attr("style", "color:inherit;overflow-wrap:anywhere"));
      document.select(".email-button").forEach(e -> e.attr("style", "padding:12px 20px;background-color:" + ContrastCheck.color(brand, dark ? "darkPrimaryColor" : "primaryColor")));
      document.select(".email-button a").forEach(e -> e.attr("style", "color:" + ContrastCheck.color(brand, dark ? "darkButtonTextColor" : "buttonTextColor")));
      document.select("[class]").forEach(e -> e.removeAttr("class"));
      return ("<h4>" + (dark ? "Dark" : "Light") + " · " + width + "px maximum</h4><div style=\"box-sizing:border-box;width:100%;max-width:"
         + width + "px;padding:12px;overflow-wrap:anywhere;background-color:" + ContrastCheck.color(brand, dark ? "darkBackgroundColor" : "backgroundColor")
         + ";color:" + ContrastCheck.color(brand, dark ? "darkTextColor" : "textColor") + "\">" + document.body().html() + "</div>");
   }

   /*******************************************************************************
    ** Standard widget response.
    *******************************************************************************/
   private static RenderWidgetOutput output(String html)
   {
      return (new RenderWidgetOutput(new RawHTML(null, html)));
   }
}
