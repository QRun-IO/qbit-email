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

package com.kingsrook.qbits.email.app;


import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import com.kingsrook.qbits.email.metadata.EmailEnums;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.template.EmailTemplateEngine;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.ChartData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Inline styles survive the UI sanitizer. Grids collapse into labelled cards;
 ** bars shrink with their container and all ordinary text inherits the theme.
 *******************************************************************************/
public final class EmailHomeView
{
   private static final String WRAP = "min-width:0;max-width:100%;overflow-wrap:anywhere;word-break:break-word;box-sizing:border-box;";
   private static final String CARD = WRAP + "padding:16px;border:1px solid rgba(127,127,127,.3);border-radius:8px;";
   private static final String ROW = WRAP + "display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,12rem),1fr));gap:6px 16px;padding:10px 0;border-top:1px solid rgba(127,127,127,.2);";
   private static final String OK = "#2e9e5b";
   private static final String FAIL = "#d64545";
   private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailHomeView()
   {
   }



   /*******************************************************************************
    ** One ordered home with real, viewer-scoped data supplied by EmailHome.
    *******************************************************************************/
   public static String render(List<Integer> totals, ChartData hourly, List<QRecord> messages, ChartData suppressions)
   {
      return (render(totals, hourly, messages, suppressions, List.of()));
   }



   /*******************************************************************************
    ** The same home with the Setup card first (email admins), when there are items.
    *******************************************************************************/
   public static String render(List<Integer> totals, ChartData hourly, List<QRecord> messages, ChartData suppressions, List<EmailSetupStatus.Item> setup)
   {
      StringBuilder html = new StringBuilder("<div style=\"" + WRAP + "display:flex;flex-direction:column;gap:16px;color:inherit;line-height:1.4\">");
      setup(html, setup);
      html.append("<div style=\"" + WRAP + "display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,9rem),1fr));gap:12px\">");
      List<String> labels = List.of("Sent (24 h)", "Delivered (24 h)", "Dead letters (24 h)", "Bounced (7 d)", "Complaints (7 d)", "Suppressed addresses");
      for(int i = 0; i < labels.size(); i++)
      {
         html.append("<div style=\"").append(CARD).append("padding:12px\"><div style=\"font-size:1.6rem;font-weight:600;line-height:1.2;")
            .append(i >= 2 && i <= 4 && totals.get(i) > 0 ? "color:" + FAIL : "color:inherit").append("\">")
            .append(String.format(Locale.ENGLISH, "%,d", totals.get(i))).append("</div><div style=\"font-size:.85rem;opacity:.75\">")
            .append(labels.get(i)).append("</div></div>");
      }
      html.append("</div>");
      bars(html, "Sends per hour, last 24 hours", hourly);
      messages(html, messages);
      bars(html, "Suppressions added, last 30 days", suppressions);
      return (html.append("</div>").toString());
   }



   /*******************************************************************************
    ** The Setup card: each part of Email, set up or not, and the next step, linked
    ** to its table.
    *******************************************************************************/
   private static void setup(StringBuilder html, List<EmailSetupStatus.Item> items)
   {
      if(items.isEmpty())
      {
         return;
      }
      section(html, "Setup");
      long todo = items.stream().filter(item -> item.state() == EmailSetupStatus.State.TODO).count();
      html.append("<p style=\"margin:0 0 8px;opacity:.75\">").append(todo == 0 ? "Everything Email needs is set up." : todo + (todo == 1 ? " item needs" : " items need") + " attention.").append("</p>");
      for(EmailSetupStatus.Item item : items)
      {
         String color = switch(item.state())
         {
            case OK -> OK;
            case TODO -> FAIL;
            case DEFAULT -> "#d97706";
            case OPTIONAL -> "#808080";
         };
         String label = switch(item.state())
         {
            case OK -> "Set up";
            case TODO -> "Needs setup";
            case DEFAULT -> "Using defaults";
            case OPTIONAL -> "Optional";
         };
         String name = item.tableName() == null ? esc(item.label()) : "<a style=\"color:inherit\" href=\"/app/" + esc(item.tableName()) + "\">" + esc(item.label()) + "</a>";
         html.append("<div style=\"").append(ROW).append("\">")
            .append(cell("Part", "<strong>" + name + "</strong><br><span style=\"color:" + color + "\">" + label + "</span>"))
            .append(cell("Status", esc(item.detail())))
            .append(cell("Next step", esc(item.nextStep() == null ? "Nothing to do" : item.nextStep()))).append("</div>");
      }
      html.append("</section>");
   }



   /*******************************************************************************
    ** A bounded stacked bar chart, including exact values in each bar's title.
    *******************************************************************************/
   private static void bars(StringBuilder html, String title, ChartData chart)
   {
      section(html, title);
      var data = chart.getChartData();
      int max = 0;
      for(int i = 0; i < data.getLabels().size(); i++)
      {
         int total = 0;
         for(var dataset : data.getDatasets())
         {
            total += dataset.getData().get(i).intValue();
         }
         max = Math.max(max, total);
      }
      if(max == 0)
      {
         html.append("<p style=\"margin:0;opacity:.75\">").append(esc(chart.getSublabel())).append("</p></section>");
         return;
      }
      html.append("<div style=\"font-size:.75rem;opacity:.75\">Peak ").append(max).append("</div>")
         .append("<div role=\"img\" aria-label=\"").append(esc(title)).append("; stacked counts, UTC\" style=\"")
         .append(WRAP).append("display:flex;align-items:flex-end;gap:2px;height:140px;width:100%\">");
      for(int i = 0; i < data.getLabels().size(); i++)
      {
         StringBuilder description = new StringBuilder(data.getLabels().get(i));
         for(var dataset : data.getDatasets())
         {
            description.append("; ").append(dataset.getLabel()).append(": ").append(dataset.getData().get(i));
         }
         html.append("<div title=\"").append(esc(description.toString())).append("\" style=\"flex:1 1 0;min-width:0;height:100%;display:flex;flex-direction:column-reverse\">");
         for(var dataset : data.getDatasets())
         {
            html.append("<div style=\"background:").append(dataset.getColor()).append(";height:")
               .append(String.format(Locale.ROOT, "%.3f", 100.0 * dataset.getData().get(i).doubleValue() / max)).append("%\"></div>");
         }
         html.append("</div>");
      }
      html.append("</div><div style=\"display:flex;justify-content:space-between;gap:8px;min-width:0;font-size:.75rem;opacity:.75;margin-top:4px\">");
      for(int i : new int[] {0, data.getLabels().size() / 2, data.getLabels().size() - 1})
      {
         html.append("<span>").append(esc(data.getLabels().get(i))).append("</span>");
      }
      html.append("</div><div style=\"display:flex;flex-wrap:wrap;gap:8px 16px;min-width:0;font-size:.8rem;margin-top:8px\">");
      for(var dataset : data.getDatasets())
      {
         html.append("<span><span style=\"display:inline-block;width:.7rem;height:.7rem;margin-right:5px;background:")
            .append(dataset.getColor()).append("\"></span>").append(esc(dataset.getLabel())).append("</span>");
      }
      html.append("<span style=\"opacity:.75\">UTC</span></div></section>");
   }



   /*******************************************************************************
    ** The whole row is a link. Labelled columns stack as cards on narrow screens.
    *******************************************************************************/
   private static void messages(StringBuilder html, List<QRecord> messages)
   {
      section(html, "Recent messages");
      if(messages.isEmpty())
      {
         html.append("<p style=\"margin:0;opacity:.75\">No messages yet.</p>");
      }
      for(QRecord message : messages)
      {
         String subject = Boolean.TRUE.equals(message.getValueBoolean("phiApproved")) || "[encrypted]".equals(message.getValueString("subject"))
            ? "Secure message" : value(message, "subject", "Message " + message.getValueInteger("id"));
         String status = message.getValueString("status");
         html.append("<a href=\"/app/").append(EmailTables.MESSAGE).append('/').append(message.getValueInteger("id"))
            .append("\" style=\"").append(ROW).append("color:inherit;text-decoration:none\">")
            .append(cell("When", esc(when(message.getValueInstant("createDate")))))
            .append(cell("To", esc(EmailHome.mask(message.getValueString("toAddresses")))))
            .append(cell("Subject", esc(subject)))
            .append(cell("Status", esc(status == null ? "Unknown" : EmailEnums.MessageStatus.valueOf(status).getPossibleValueLabel())))
            .append("</a>");
      }
      html.append("</section>");
   }



   /*******************************************************************************
    ** One bordered section, using the surrounding theme's text colour.
    *******************************************************************************/
   private static void section(StringBuilder html, String title)
   {
      html.append("<section style=\"").append(CARD).append("\"><h3 style=\"margin:0 0 12px;font-size:1rem;font-weight:600\">").append(esc(title)).append("</h3>");
   }



   /*******************************************************************************
    ** A labelled, shrinkable cell that remains understandable when stacked.
    *******************************************************************************/
   private static String cell(String label, String content)
   {
      return ("<div style=\"" + WRAP + "\"><span style=\"display:block;font-size:.75rem;opacity:.65\">" + esc(label) + "</span> " + content + "</div>");
   }



   /*******************************************************************************
    ** Compact UTC dates, with an honest missing value.
    *******************************************************************************/
   private static String when(Instant instant)
   {
      return (instant == null ? "None" : WHEN.format(instant));
   }



   /*******************************************************************************
    ** Blank fields use a human-readable fallback.
    *******************************************************************************/
   private static String value(QRecord row, String field, String fallback)
   {
      String value = row.getValueString(field);
      return (value == null || value.isBlank() ? fallback : value);
   }



   /*******************************************************************************
    ** Escape every record-sourced value before inserting HTML.
    *******************************************************************************/
   private static String esc(String value)
   {
      return (EmailTemplateEngine.escape(value));
   }
}
