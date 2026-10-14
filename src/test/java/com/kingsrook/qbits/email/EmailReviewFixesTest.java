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


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.phi.EmailBodyEncryption;
import com.kingsrook.qbits.email.render.EmailBrands;
import com.kingsrook.qbits.email.render.EmailMarkdown;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailTemplateEngine;
import com.kingsrook.qbits.email.template.EmailTemplateRenderer;
import com.kingsrook.qbits.email.template.EmailVersions;
import com.kingsrook.qbits.email.template.VariableSchema;
import com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Regression checks for the PR review's trust and resource boundaries.
 *******************************************************************************/
class EmailReviewFixesTest
{
   /*******************************************************************************
    ** Isolated records and local test keys.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c -> c.withSecretResolver(name -> "test-key").withPublicBaseUrl("https://example.com"));
      H2Support.provider(25);
   }

   /*******************************************************************************
    ** Release request state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** Template categories cannot be downgraded, including notification suppression.
    *******************************************************************************/
   @Test
   void categoryOverrideRefused() throws Exception
   {
      for(String category : List.of("MARKETING", "NOTIFICATION"))
      {
         QRecord version = EmailTemplatesTest.draft(category, "HTML", "[]", "{}", "<p>Hello</p>");
         SystemWrites.updateOne(EmailTables.TEMPLATE, new QRecord().withValue("id", version.getValue("templateId")).withValue("category", category));
         if("MARKETING".equals(category))
         {
            H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "brand").withValue("postalAddress", "123 Main St").withValue("isDefault", true));
         }
         EmailVersions.activate(version.getValueInteger("id"));
         var refused = EmailService.send(new EmailRequest().withTemplateName(category).withTo("p@example.com").withCategory(EmailCategory.TRANSACTIONAL));
         assertThat(refused.status()).isEqualTo(EmailSendResult.Status.REFUSED);
         assertThat(refused.reasons()).contains("Template category cannot be overridden");
         assertThat(EmailService.send(new EmailRequest().withTemplateName(category).withTo("p@example.com")).status()).isEqualTo(EmailSendResult.Status.QUEUED);
      }
   }

   /*******************************************************************************
    ** Nested loops share work and byte limits, and nesting stops at eight.
    *******************************************************************************/
   @Test
   void sharedBudgetAndNesting()
   {
      var engine = new EmailTemplateEngine();
      var schema = new VariableSchema("[{\"name\":\"items\",\"type\":\"LIST\"},{\"name\":\"value\",\"type\":\"STRING\"}]");
      var loop = engine.parse("{{#each items}}{{#each items}}{{/each}}{{/each}}", schema);
      assertThatThrownBy(() -> loop.render(Map.of("items", Collections.nCopies(100, "")), EmailTemplateEngine.Context.TEXT)).hasMessage("Template output is too large");
      assertThatThrownBy(() -> engine.parse("{{value}}", schema).render(Map.of("value", "é".repeat(600_000)), EmailTemplateEngine.Context.TEXT))
         .hasMessage("Template output is too large");
      assertThat(engine.parse("{{#if value}}".repeat(8) + "ok" + "{{/if}}".repeat(8), schema).render(Map.of("value", true), EmailTemplateEngine.Context.TEXT)).isEqualTo("ok");
      assertThatThrownBy(() -> engine.parse("{{#if value}}".repeat(9) + "{{/if}}".repeat(9), schema)).hasMessageContaining("8 levels");
      var budget = new EmailTemplateEngine.Budget();
      var parsed = engine.parse("{{value}}", schema);
      parsed.render(Map.of("value", "x".repeat(600_000)), EmailTemplateEngine.Context.TEXT, budget, new ArrayList<>());
      assertThatThrownBy(() -> parsed.render(Map.of("value", "x".repeat(600_000)), EmailTemplateEngine.Context.TEXT, budget, new ArrayList<>())).hasMessage("Template output is too large");
   }

   /*******************************************************************************
    ** Activation reports budgets and sends refuse rather than storing excess output.
    *******************************************************************************/
   @Test
   void budgetAtLifecycleBoundaries() throws Exception
   {
      QRecord version = EmailTemplatesTest.draft("budget", "HTML", "[{\"name\":\"items\",\"type\":\"LIST\"}]", "{}", "{{#each items}}x{{/each}}");
      EmailVersions.activate(version.getValueInteger("id"));
      var result = EmailService.send(new EmailRequest().withTemplateName("budget").withTo("p@example.com").withVariable("items", new ArrayList<>(Collections.nCopies(10_001, "x"))));
      assertThat(result.status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(result.reasons()).contains("Template output is too large");
      QRecord oversized = EmailTemplatesTest.draft("oversized", "HTML", "[]", "{}", "x".repeat(1_048_577));
      assertThatThrownBy(() -> EmailVersions.activate(oversized.getValueInteger("id"))).hasMessage("Template output is too large");
   }

   /*******************************************************************************
    ** Unsafe URL values are omitted with warnings, while normal links still work.
    *******************************************************************************/
   @Test
   void urlContexts()
   {
      var engine = new EmailTemplateEngine();
      var schema = new VariableSchema("[{\"name\":\"value\",\"type\":\"STRING\"}]");
      for(String attribute : List.of("href", "src", "action", "background"))
      {
         for(String quote : List.of("\"", "'"))
         {
            var parsed = engine.parse("<a " + attribute + "=" + quote + "{{value}}" + quote + ">x</a>", schema);
            for(String value : List.of("javascript:alert(1)", "data:text/html,evil"))
            {
               List<String> warnings = new ArrayList<>();
               assertThat(parsed.render(Map.of("value", value), EmailTemplateEngine.Context.HTML, new EmailTemplateEngine.Budget(), warnings)).doesNotContain(value).contains(attribute + "=" + quote + quote);
               assertThat(warnings).containsExactly("Unsafe URL omitted for value");
            }
            assertThat(parsed.render(Map.of("value", "https://example.com"), EmailTemplateEngine.Context.HTML)).contains("https://example.com");
            assertThat(parsed.render(Map.of("value", "mailto:p@example.com"), EmailTemplateEngine.Context.HTML).contains("mailto:")).isEqualTo(attribute.equals("href"));
         }
      }
      for(String source : List.of("<a href=\" {{value}}\">x</a>", "<a href=\"{{#if value}}{{value}}{{/if}}\">x</a>", "<a href=\"java{{value}}\">x</a>"))
      {
         List<String> warnings = new ArrayList<>();
         String html = engine.parse(source, schema).render(Map.of("value", source.contains("java{{") ? "script:alert(1)" : "javascript:alert(1)"),
            EmailTemplateEngine.Context.HTML, new EmailTemplateEngine.Budget(), warnings);
         assertThat(html).contains("href=\"\"").doesNotContain("javascript:");
         assertThat(warnings).isNotEmpty();
      }
   }

   /*******************************************************************************
    ** Refuse unquoted values and includes before a configured compiler executes.
    *******************************************************************************/
   @Test
   void activationRejectsUnsafeSource() throws Exception
   {
      QRecord version = EmailTemplatesTest.draft("unquoted", "HTML", "[]", "{}", "<a href={{brand.site_url}}>Go</a>");
      assertThatThrownBy(() -> EmailVersions.activate(version.getValueInteger("id"))).hasMessageContaining("must be quoted");
      for(String source : List.of("<a {{brand.label}}>x</a>", "<{{brand.label}}>"))
      {
         assertThatThrownBy(() -> EmailTemplateEngine.validateHtml(source)).hasMessageContaining("must be quoted");
      }
      EmailRuntime.config().withMjmlCompiler(source ->
      {
         throw new AssertionError("Unsafe MJML reached compiler");
      });
      QRecord mjml = EmailTemplatesTest.draft("includes", "MJML", "[]", "{}", "<mjml><mj-include path='secret'/></mjml>");
      assertThatThrownBy(() -> EmailVersions.activate(mjml.getValueInteger("id"))).hasMessage("MJML includes are not allowed");
      assertThat(preview(mjml)).contains("MJML includes are not allowed");
   }

   /*******************************************************************************
    ** A viewer can inspect stored output but cannot start a compiler.
    *******************************************************************************/
   @Test
   void viewerNeverCompiles() throws Exception
   {
      QRecord version = EmailTemplatesTest.draft("viewer", "MJML", "[]", "{}", "<mjml/>");
      EmailRuntime.config().withMjmlCompiler(source ->
      {
         throw new AssertionError("Viewer compiled MJML");
      });
      QContext.getQSession().setPermissions(Set.of("email.view"));
      assertThat(preview(version)).contains("Not compiled yet. Activate the version or use Preview as a manager.");
      QContext.getQSession().setPermissions(Set.of("email.manage"));
      EmailRuntime.config().withMjmlCompiler(source -> "<p>Compiled sample</p>");
      assertThat(preview(version)).contains("Compiled sample");
      assertThat(EmailStore.get(EmailTables.VERSION, version.getValueInteger("id"), null).getValue("compiledHtml")).isNull();
   }

   /*******************************************************************************
    ** Approved sends retain their template brand and restrict final image hosts.
    *******************************************************************************/
   @Test
   void approvedBrandAndImages() throws Exception
   {
      QRecord brand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "trusted").withValue("label", "Trusted")
         .withValue("siteUrl", "https://brand.example.com").withValue("logoUrl", "https://other.example.com/logo.png"));
      H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "override").withValue("label", "Override"));
      QRecord version = EmailTemplatesTest.draft("approved", "HTML", "[]", "{}", "<p>{{brand.label}}</p><img src='https://brand.example.com/a.png'><img src='https://cdn.example.com/b.png'><img src='https://evil.example.com/c.png'>"
         + "<!--[if mso]><img src='https://evil.example.com/outlook.png'><![endif]--><style>@IMPORT 'https://evil.example.com/pixel.css';</style>");
      SystemWrites.updateOne(EmailTables.TEMPLATE, new QRecord().withValue("id", version.getValue("templateId")).withValue("brandId", brand.getValue("id")));
      EmailVersions.approve(version.getValueInteger("id"));
      EmailVersions.activate(version.getValueInteger("id"));
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("phiAllowed", true));
      EmailRuntime.config().withAllowedImageHosts(Set.of("cdn.example.com"));
      var renderer = EmailTemplateRenderer.prepare(new EmailRequest().withTemplateName("approved").withBrandName("override"));
      QRecord rendered = renderer.render("preview", null, false);
      assertThat(rendered.getValue("brandId")).isEqualTo(brand.getValue("id"));
      assertThat(rendered.getValueString("htmlBody")).contains("Trusted", "brand.example.com/a.png", "cdn.example.com/b.png").doesNotContain("Override", "evil.example.com", "other.example.com");
      assertThat(renderer.warnings()).contains("Brand logo omitted: image host is not allowed", "Image omitted: image host is not allowed");
      assertThat(EmailBrands.allowedImage("http://brand.example.com/a.png", brand)).isFalse();
      assertThat(EmailBrands.allowedImage("https://brand.example.com.evil.com/a.png", brand)).isFalse();
      assertThatThrownBy(() -> H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "badlogo").withValue("logoUrl", "http://example.com/logo"))).hasMessageContaining("https");
      assertThatThrownBy(() -> H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "badsocial").withValue("socialLinks", "[{\"label\":\"X\",\"url\":\"http://example.com\"}]"))).hasMessageContaining("https");
   }

   /*******************************************************************************
    ** Button conversion cannot consume an earlier ordinary link.
    *******************************************************************************/
   @Test
   void buttonMatchesOneLink()
   {
      String html = EmailMarkdown.render("[First](https://first.example) and [Second](https://second.example){.button}", new QRecord());
      var document = org.jsoup.Jsoup.parse(html);
      assertThat(document.select(".email-button a").eachAttr("href")).containsExactly("https://second.example");
      assertThat(document.select("a")).hasSize(2);
   }

   /*******************************************************************************
    ** Authentication binds both row and column without changing the storage prefix.
    *******************************************************************************/
   @Test
   void ciphertextSwapFails()
   {
      String first = EmailBodyEncryption.encrypt("first", "htmlBody", "1");
      String second = EmailBodyEncryption.encrypt("second", "htmlBody", "2");
      assertThat(first).startsWith("enc:v1:");
      assertThat(EmailBodyEncryption.decrypt(first, "htmlBody", "1")).isEqualTo("first");
      assertThatThrownBy(() -> EmailBodyEncryption.decrypt(first, "htmlBody", "2")).hasMessageContaining("could not be opened");
      assertThatThrownBy(() -> EmailBodyEncryption.decrypt(second, "htmlBody", "1")).hasMessageContaining("could not be opened");
      assertThatThrownBy(() -> EmailBodyEncryption.decrypt(first, "textBody", "1")).hasMessageContaining("could not be opened");
   }

   /*******************************************************************************
    ** Swapped stored ciphertext never reaches a transport during delivery.
    *******************************************************************************/
   @Test
   void swappedRowsCannotBeDispatched() throws Exception
   {
      QRecord version = EmailTemplatesTest.draft("protected", "HTML", "[]", "{}", "<p>Private message</p>");
      EmailVersions.approve(version.getValueInteger("id"));
      EmailVersions.activate(version.getValueInteger("id"));
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("phiAllowed", true));
      var first = EmailService.send(new EmailRequest().withTemplateName("protected").withTo("a@example.com"));
      var second = EmailService.send(new EmailRequest().withTemplateName("protected").withTo("b@example.com"));
      QRecord original = EmailStore.get(EmailTables.MESSAGE, first.messageId(), null);
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", second.messageId()).withValue("htmlBody", original.getValue("htmlBody")));
      var dispatcher = new com.kingsrook.qbits.email.delivery.EmailDispatcher(new com.kingsrook.qbits.email.transport.EmailTransportRegistry()
         .withTransport("SMTP", (envelope, settings) ->
         {
            throw new AssertionError("Swapped ciphertext reached transport");
         }));
      assertThat(dispatcher.sendNow(second.messageId())).isEqualTo(com.kingsrook.qbits.email.delivery.EmailDispatcher.SendResult.RETRY_WAIT);
   }

   /*******************************************************************************
    ** Exercise the actual inline widget with current user permissions.
    *******************************************************************************/
   private String preview(QRecord version) throws Exception
   {
      return (((RawHTML) new RenderWidgetAction().execute(new RenderWidgetInput().withWidgetMetaData(QContext.getQInstance().getWidget("emailTemplatePreview"))
         .withUrlParams(Map.of("id", version.getValueString("id")))).getWidgetData()).getHtml());
   }
}
