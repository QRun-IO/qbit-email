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

package com.kingsrook.qbits.email;


import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.email.app.EmailHome;
import com.kingsrook.qbits.email.metadata.EmailEnums;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.render.ContrastCheck;
import com.kingsrook.qbits.email.render.EmailLayout;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Home data and rendered output, real record labels, and branded preview contrast.
 *******************************************************************************/
class EmailHomePolishTest
{
   private static final Instant NOW = Instant.parse("2026-10-14T02:12:00Z");

   /*******************************************************************************
    ** Use a fixed UTC clock and isolated database for exact assertions.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c ->
      {
      });
      EmailRuntime.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
      H2Support.provider(25);
   }

   /*******************************************************************************
    ** Clear session and clock.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** Exact counts, plain labels, large number first, and failures alone are red.
    *******************************************************************************/
   @Test
   void tilesAndOrderedSections() throws Exception
   {
      SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("status", "DEAD_LETTER")
         .withValue("sentDate", NOW.minusSeconds(86400)).withValue("deliveredDate", NOW.plusSeconds(1))
         .withValue("deadLetterDate", NOW).withValue("bouncedDate", NOW.minusSeconds(7 * 86400))
         .withValue("complainedDate", NOW.minusSeconds(7 * 86400 + 1)));
      for(String type : List.of("DELIVERED", "BOUNCED", "COMPLAINED"))
      {
         SystemWrites.insertOne(EmailTables.EVENT, new QRecord().withValue("messageId", 1).withValue("eventType", type).withValue("eventKey", type)
            .withValue("occurredDate", "DELIVERED".equals(type) ? NOW.plusSeconds(1)
               : NOW.minusSeconds(7 * 86400 + ("COMPLAINED".equals(type) ? 1 : 0))));
      }
      H2Support.insert(EmailTables.SUPPRESSION, new QRecord().withValue("address", "a@example.test").withValue("scope", "ALL").withValue("reason", "MANUAL"));
      var document = Jsoup.parse(EmailHome.overview().getHtml());
      var tiles = document.select("body > div > div > div");
      assertThat(tiles).extracting(Element::text).containsExactly("1 Sent (24 h)", "0 Delivered (24 h)", "1 Dead letters (24 h)", "1 Bounced (7 d)", "0 Complaints (7 d)", "1 Suppressed addresses");
      for(int i = 0; i < tiles.size(); i++)
      {
         assertThat(tiles.get(i).child(0).attr("style")).contains("font-size:1.6rem");
         assertThat(tiles.get(i).child(0).attr("style").contains("#d64545")).isEqualTo(i == 2 || i == 3);
      }
      assertThat(document.select("h3")).extracting(Element::text).containsExactly("Sends per hour, last 24 hours", "Recent messages", "Suppressions added, last 30 days");
      assertThat(QContext.getQInstance().getApp("email").getWidgets()).containsExactly("emailHealth", "emailHome");
   }

   /*******************************************************************************
    ** Every attempt outcome and suppression reason feeds its actual UTC bucket.
    *******************************************************************************/
   @Test
   void stackedBarsAndCompactAxes() throws Exception
   {
      int number = 0;
      for(String outcome : List.of("SUCCEEDED", "TRANSIENT_FAILURE", "PROVIDER_FAILURE", "PERMANENT_FAILURE", "LEASE_EXPIRED", "IN_FLIGHT"))
      {
         SystemWrites.insertOne(EmailTables.ATTEMPT, new QRecord().withValue("messageId", 1).withValue("attemptNumber", ++number)
            .withValue("outcome", outcome).withValue("endDate", NOW));
      }
      for(var reason : EmailEnums.SuppressionReason.values())
      {
         H2Support.insert(EmailTables.SUPPRESSION, new QRecord().withValue("address", reason.name() + "@example.test").withValue("scope", "ALL").withValue("reason", reason.name()));
      }
      var hourly = EmailHome.hourly().getChartData();
      assertThat(hourly.getLabels()).hasSize(24).allMatch(s -> s.matches("[0-2][0-9]:00"));
      assertThat(hourly.getLabels().getLast()).isEqualTo("02:00");
      assertThat(hourly.getDatasets().get(0).getData().getLast()).isEqualTo(1);
      assertThat(hourly.getDatasets().get(1).getData().getLast()).isEqualTo(4);
      var suppressions = EmailHome.suppressions().getChartData();
      assertThat(suppressions.getLabels()).hasSize(30).endsWith("Oct 14");
      assertThat(suppressions.getDatasets()).extracting(d -> d.getLabel()).containsExactly("Hard bounce", "Complaint", "Unsubscribe", "Manual");
      assertThat(suppressions.getDatasets()).allSatisfy(d -> assertThat(d.getData().getLast()).isEqualTo(1));
      String html = EmailHome.overview().getHtml();
      assertThat(html).contains("flex-direction:column-reverse", "02:00; Sent: 1; Failed: 4", "Oct 14; Hard bounce: 1; Complaint: 1; Unsubscribe: 1; Manual: 1")
         .doesNotContain("<canvas", "<svg", "<style", "overflow:auto", "HARD_BOUNCE", "2026-10-");
      assertThat(Jsoup.parse(html).select("div[role=img] > div")).hasSize(54).allSatisfy(e -> assertThat(e.attr("style")).contains("flex:1 1 0;min-width:0"));
   }

   /*******************************************************************************
    ** Twenty newest linked rows wrap long text, mask addresses, and hide PHI.
    *******************************************************************************/
   @Test
   void recentRowsExcludeProviderDiagnostics() throws Exception
   {
      for(int i = 0; i < 21; i++)
      {
         SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("status", i == 20 ? "RETRY_WAIT" : "SENT")
            .withValue("subject", i == 19 ? "private-canary" : "Subject <" + i + ">" + "x".repeat(150))
            .withValue("phiApproved", i == 19).withValue("toAddresses", "Person <person@" + "x".repeat(100) + ".test>"));
      }
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("lastSuccessDate", NOW)
         .withValue("healthStatus", "UNHEALTHY").withValue("lastFailureMessage", "<broken>").withValue("maxPerMinute", 2));
      SystemWrites.insertOne(EmailTables.ATTEMPT, new QRecord().withValue("messageId", 1).withValue("attemptNumber", 1).withValue("providerId", 1).withValue("startDate", NOW));
      String html = EmailHome.overview().getHtml();
      var document = Jsoup.parse(html);
      var rows = document.select("a[href^=/app/email_message/]");
      assertThat(rows).hasSize(20);
      assertThat(rows.first().attr("href")).isEqualTo("/app/email_message/21");
      assertThat(rows.last().attr("href")).isEqualTo("/app/email_message/2");
      assertThat(rows.first().text()).contains("When", "To", "Subject", "Status", "Waiting to retry");
      assertThat(html).contains("Secure message", "p***@", "min-width:0", "overflow-wrap:anywhere")
         .doesNotContain("private-canary", "Person &lt;", "RETRY_WAIT", "2026-10-14T", "Provider health", "&lt;broken&gt;", "Per-minute headroom");
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("enabled", false).withValue("maxPerMinute", null));
      assertThat(EmailHome.overview().getHtml()).doesNotContain("Disabled", "No limit", "Provider health");
      QContext.getQSession().setPermissions(Set.of("email.view"));
      assertThat(EmailHome.overview().getHtml()).doesNotContain("Provider health", "&lt;broken&gt;");
   }

   /*******************************************************************************
    ** Query, get and possible-value labels use the same human-readable titles.
    *******************************************************************************/
   @Test
   void recordLabels() throws Exception
   {
      QRecord version = EmailTemplatesTest.draft("welcome", "MARKDOWN", "[]", "{}", "Hello");
      assertThat(get(EmailTables.VERSION, version.getValueInteger("id")).getRecordLabel()).isEqualTo("welcome v1");
      SystemWrites.updateOne(EmailTables.VERSION, new QRecord().withValue("id", version.getValue("id")).withValue("locale", "fr"));
      assertThat(get(EmailTables.VERSION, version.getValueInteger("id")).getRecordLabel()).isEqualTo("welcome v1 (fr)");
      assertThat(get(EmailTables.PROVIDER, 1).getRecordLabel()).isEqualTo("Relay");
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("label", ""));
      assertThat(get(EmailTables.PROVIDER, 1).getRecordLabel()).isEqualTo("relay");
      for(String subject : List.of("A useful subject", "", "enc:v1:private"))
      {
         SystemWrites.insertOne(EmailTables.MESSAGE, new QRecord().withValue("subject", subject));
      }
      assertThat(get(EmailTables.MESSAGE, 1).getRecordLabel()).isEqualTo("A useful subject");
      assertThat(get(EmailTables.MESSAGE, 2).getRecordLabel()).isEqualTo("Message 2");
      assertThat(get(EmailTables.MESSAGE, 3).getRecordLabel()).isEqualTo("Secure message");
      var queried = new QueryAction().execute(new QueryInput(EmailTables.MESSAGE).withShouldGenerateDisplayValues(true)).getRecords();
      assertThat(queried).extracting(QRecord::getRecordLabel).containsExactly("A useful subject", "Message 2", "Secure message");
      var possible = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput().withPossibleValueSourceName(EmailTables.VERSION).withSearchTerm("fr"));
      assertThat(possible.getResults()).extracting(v -> v.getLabel()).contains("welcome v1 (fr)");
      assertThat(EmailEnums.MessageStatus.RETRY_WAIT.getPossibleValueLabel()).isEqualTo("Waiting to retry");
      assertThat(EmailEnums.MessageStatus.DEAD_LETTER.getPossibleValueLabel()).isEqualTo("Dead letter");
   }

   /*******************************************************************************
    ** The demo brand's actual inline preview matches the email dark media query.
    *******************************************************************************/
   @Test
   void demoPreviewButtonContrast() throws Exception
   {
      QRecord brand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "Kof22").withValue("label", "Kof22")
         .withValue("primaryColor", "#2459a6").withValue("buttonTextColor", "#ffffff")
         .withValue("textColor", "#172033").withValue("backgroundColor", "#ffffff")
         .withValue("darkPrimaryColor", "#93c5fd").withValue("darkButtonTextColor", "#172033")
         .withValue("darkBackgroundColor", "#111827").withValue("darkTextColor", "#f9fafb").withValue("isDefault", true));
      QRecord version = EmailTemplatesTest.draft("preview", "MARKDOWN", "[]", "{}", "[Visit Kof22](https://example.test){.button}");
      String html = ((RawHTML) new RenderWidgetAction().execute(new RenderWidgetInput().withWidgetMetaData(QContext.getQInstance().getWidget("emailTemplatePreview"))
         .withUrlParams(Map.of("id", version.getValueString("id")))).getWidgetData()).getHtml();
      var document = Jsoup.parse(html);
      Element dark = document.select("h4").stream().filter(e -> e.text().startsWith("Dark")).findFirst().orElseThrow().nextElementSibling();
      Element button = dark.select("a[href=https://example.test]").first();
      assertThat(button).isNotNull();
      String foreground = css(button, "color");
      String background = css(button.parent(), "background-color");
      assertThat(foreground).isEqualTo("#172033");
      assertThat(background).isEqualTo("#93c5fd");
      assertThat(ContrastCheck.ratio(foreground, background)).isGreaterThanOrEqualTo(4.5);
      assertThat(dark.attr("style")).contains("background-color:#111827", "color:#f9fafb");
      assertThat(EmailLayout.render("Body", "", brand, EmailCategory.TRANSACTIONAL, null))
         .contains(".email-button{background-color:" + background + "!important}", ".email-button a{color:" + foreground + "!important}");
   }

   /*******************************************************************************
    ** Missing dark button text chooses an accessible colour for either primary.
    *******************************************************************************/
   @Test
   void accessibleDarkButtonFallback()
   {
      for(String primary : List.of("#93c5fd", "#172033", "#777777"))
      {
         QRecord brand = new QRecord().withValue("darkPrimaryColor", primary);
         assertThat(ContrastCheck.ratio(ContrastCheck.color(brand, "darkButtonTextColor"), primary)).isGreaterThanOrEqualTo(4.5);
         ContrastCheck.validate(brand);
      }
      assertThat(ContrastCheck.color(new QRecord().withValue("darkPrimaryColor", "#93c5fd").withValue("darkButtonTextColor", "#172033"), "darkButtonTextColor")).isEqualTo("#172033");
   }

   /*******************************************************************************
    ** Exercise real get/display-value actions, not just metadata declarations.
    *******************************************************************************/
   private static QRecord get(String table, int id) throws Exception
   {
      return (new GetAction().execute(new GetInput(table).withPrimaryKey(id).withShouldGenerateDisplayValues(true)).getRecord());
   }

   /*******************************************************************************
    ** Read the rendered inline pair; remove CSS priority before computing ratio.
    *******************************************************************************/
   private static String css(Element element, String property)
   {
      return (List.of(element.attr("style").split(";")).stream().map(String::trim).filter(s -> s.startsWith(property + ":"))
         .findFirst().orElseThrow().substring(property.length() + 1).replace("!important", "").trim());
   }
}
