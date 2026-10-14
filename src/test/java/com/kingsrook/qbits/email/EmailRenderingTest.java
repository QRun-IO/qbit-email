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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.render.ContrastCheck;
import com.kingsrook.qbits.email.render.EmailBrands;
import com.kingsrook.qbits.email.render.EmailLayout;
import com.kingsrook.qbits.email.render.EmailMarkdown;
import com.kingsrook.qbits.email.render.HtmlToText;
import com.kingsrook.qbits.email.template.CliMjmlCompiler;
import com.kingsrook.qbits.email.template.EmailTemplateEngine;
import com.kingsrook.qbits.email.template.VariableSchema;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Template grammar, content boundaries, accessible layout and validation.
 *******************************************************************************/
class EmailRenderingTest
{
   private final EmailTemplateEngine engine = new EmailTemplateEngine();
   private final VariableSchema schema = new VariableSchema("[{\"name\":\"name\",\"type\":\"STRING\"},{\"name\":\"items\",\"type\":\"LIST\"},{\"name\":\"record\",\"type\":\"RECORD\"}]");

   /*******************************************************************************
    ** Escaping belongs to the output context, and fallbacks are escaped too.
    *******************************************************************************/
   @Test
   void escapingAndFallbacks()
   {
      String attack = "<img src=x onerror='bad()'> & \"test\"";
      var parsed = engine.parse("{{name}} {{record.name|<fallback>}}", schema);
      assertThat(parsed.render(Map.of("name", attack), EmailTemplateEngine.Context.HTML)).contains("&lt;img", "&#39;", "&quot;", "&lt;fallback&gt;").doesNotContain("<img");
      assertThat(parsed.render(Map.of("name", attack), EmailTemplateEngine.Context.TEXT)).contains(attack);
      String markdown = engine.parse("{{name}}", schema).render(Map.of("name", "[x](javascript:alert(1))\n# heading <script>x</script>"), EmailTemplateEngine.Context.MARKDOWN);
      String html = EmailMarkdown.render(markdown, new QRecord());
      assertThat(html).doesNotContain("<a", "<h1", "<script").contains("javascript:alert(1)");
   }

   /*******************************************************************************
    ** Nested conditionals, loops, dotted values, indices and restored scope.
    *******************************************************************************/
   @Test
   void blocksAndTruthiness()
   {
      var parsed = engine.parse("{{#if name}}Hello {{name}}{{/if}}{{#each items}}[{{@index}}:{{this.field|empty}}{{#if this.ok}}!{{/if}}]{{/each}} {{record.name}}", schema);
      assertThat(parsed.render(Map.of("name", "Pat", "items", List.of(Map.of("field", "A", "ok", true), Map.of()), "record", Map.of("name", "R")), EmailTemplateEngine.Context.TEXT))
         .isEqualTo("Hello Pat[0:A!][1:empty] R");
      assertThat(engine.parse("{{#each items}}{{this}}{{/each}}", schema).render(Map.of("items", List.of("a", "b")), EmailTemplateEngine.Context.HTML)).isEqualTo("ab");
      for(Object falsy : List.of(false, "", List.of(), Map.of(), 0, new BigDecimal("0.00")))
      {
         assertThat(EmailTemplateEngine.truthy(falsy)).isFalse();
      }
      assertThat(EmailTemplateEngine.truthy(null)).isFalse();
      assertThat(EmailTemplateEngine.truthy(-1)).isTrue();
      assertThat(EmailTemplateEngine.truthy("false")).isTrue();
      assertThat(engine.parse("literal", schema).render(Map.of(), EmailTemplateEngine.Context.HTML)).isEqualTo("literal");
   }

   /*******************************************************************************
    ** Authors get precise source-line diagnostics for unsupported syntax.
    *******************************************************************************/
   @Test
   void parseErrors()
   {
      assertThatThrownBy(() -> engine.parse("line\n{{missing}}", schema)).hasMessageContaining("Line 2").hasMessageContaining("missing");
      assertThatThrownBy(() -> engine.parse("\n{{#if name}}", schema)).hasMessageContaining("Line 2: Unclosed");
      for(String source : List.of("{{#if name}}{{/each}}", "{{/if}}", "{{this}}", "{{@index}}", "{{{name}}}", "{{> partial}}", "{{name", "{{#if name|fallback}}"))
      {
         assertThatThrownBy(() -> engine.parse(source, schema)).isInstanceOf(IllegalArgumentException.class);
      }
   }

   /*******************************************************************************
    ** Schema coercion is explicit, including strict URLs and required values.
    *******************************************************************************/
   @Test
   void schemaValidation()
   {
      String json = "[{\"name\":\"n\",\"type\":\"NUMBER\",\"required\":true},{\"name\":\"d\",\"type\":\"DATE\"},{\"name\":\"t\",\"type\":\"DATE_TIME\"},{\"name\":\"b\",\"type\":\"BOOLEAN\"},{\"name\":\"url\",\"type\":\"URL\"}]";
      VariableSchema schema = new VariableSchema(json);
      var values = schema.validate(VariableSchema.values("{\"n\":\"1.20\",\"d\":\"2026-10-13\",\"t\":\"2026-10-13T12:00:00Z\",\"b\":\"true\",\"url\":\"https://example.com/path\"}"));
      assertThat(values.get("n")).isEqualTo(new BigDecimal("1.20"));
      assertThat(values.get("b")).isEqualTo(true);
      assertThatThrownBy(() -> schema.validate(Map.of())).hasMessageContaining("Required variable n");
      assertThatThrownBy(() -> schema.validate(Map.of("n", 1, "b", "yes"))).hasMessageContaining("BOOLEAN");
      for(String url : List.of("javascript:alert(1)", "data:text/html,x", "/relative", "https://user:pass@example.com", "https://"))
      {
         assertThatThrownBy(() -> VariableSchema.url(url)).isInstanceOf(IllegalArgumentException.class);
      }
      for(String invalid : List.of("{}", "[{}]", "[{\"name\":\"x\",\"type\":\"CODE\"}]", "[{\"name\":\"x\",\"type\":\"STRING\",\"table\":\"t\"}]"))
      {
         assertThatThrownBy(() -> new VariableSchema(invalid)).hasMessage("Invalid variables schema");
      }
      assertThatThrownBy(() -> VariableSchema.values("[]")).hasMessage("Variables must be a JSON object");
      assertThat(schema.knows("brand.site_url")).isTrue();
      assertThat(schema.knows("brand.missing")).isFalse();
   }

   /*******************************************************************************
    ** CommonMark extensions and table buttons cannot enable raw HTML.
    *******************************************************************************/
   @Test
   void markdownAndText()
   {
      String html = EmailMarkdown.render("# Heading\n\n<script>alert(1)</script>\n\n| A | B |\n|---|---|\n| One | Two |\n\n[Open](https://example.com){.button}\n\nwww.example.com\n\n- First\n- Second", new QRecord());
      assertThat(html).contains("<h1>", "&lt;script&gt;", "table-layout:fixed", "email-button", "href=\"https://example.com\"", "http://www.example.com").doesNotContain("<script>", "{.button}");
      String text = HtmlToText.convert(html);
      assertThat(text).contains("Heading", "One", "Two", "Open (https://example.com)", "- First", "- Second");
      assertThat(HtmlToText.convert("<html><head><style>bad-css</style></head><body>A &amp; B<br>C<script>bad-code</script></body></html>"))
         .contains("A & B", "C").doesNotContain("bad-css", "bad-code");
   }

   /*******************************************************************************
    ** Both schemes, logo variants and category-specific footers are present.
    *******************************************************************************/
   @Test
   void layoutAndContrast()
   {
      QRecord brand = new QRecord().withValue("label", "Brand").withValue("logoUrl", "https://example.com/logo.png")
         .withValue("logoDarkUrl", "https://example.com/dark.png").withValue("logoAlt", "Brand logo")
         .withValue("footerMarkdown", "Thanks **friend**").withValue("postalAddress", "123 Main Street")
         .withValue("socialLinks", "[{\"label\":\"Social\",\"url\":\"https://example.com/social\"}]");
      ContrastCheck.validate(brand);
      assertThat(ContrastCheck.ratio("#000000", "#ffffff")).isEqualTo(21);
      for(EmailCategory category : EmailCategory.values())
      {
         String html = EmailLayout.render("<p>Content</p>", "Preheader", brand, category, "https://example.com/unsubscribe");
         assertThat(html).contains("prefers-color-scheme:dark", "color-scheme", "supported-color-schemes", "[data-ogsc]", "[data-ogsb]", "max-width:600px", "email-logo-dark", "Preheader", "Social").doesNotContain("background-image");
         assertThat(html.contains("Unsubscribe")).isEqualTo(category == EmailCategory.NOTIFICATION || category == EmailCategory.MARKETING);
         assertThat(html.contains("123 Main Street")).isEqualTo(category == EmailCategory.MARKETING);
      }
      String appended = EmailLayout.appendFooter("<html><body>content</body></html>", brand, EmailCategory.NOTIFICATION, "https://example.com/u");
      assertThat(appended.indexOf("Unsubscribe")).isLessThan(appended.indexOf("</body>"));
      assertThat(EmailLayout.appendFooter("fragment", brand, EmailCategory.TRANSACTIONAL, "")).isEqualTo("fragment");
      assertThatThrownBy(() -> ContrastCheck.validate(new QRecord().withValue("darkTextColor", "#555555"))).hasMessageContaining("dark mode; it needs 4.5:1");
      assertThatThrownBy(() -> ContrastCheck.validate(new QRecord().withValue("primaryColor", "#ffffff"))).hasMessageContaining("Button text");
      assertThatThrownBy(() -> EmailBrands.validate(new QRecord().withValue("logoUrl", "javascript:x"))).hasMessageContaining("http or https");
      assertThatThrownBy(() -> EmailBrands.validate(new QRecord().withValue("textColor", "red;display:none"))).hasMessageContaining("six-digit");
      assertThatThrownBy(() -> EmailLayout.footer(new QRecord(), EmailCategory.MARKETING, "")).hasMessageContaining("postal address");
   }

   /*******************************************************************************
    ** Missing or failing CLI tools return only a safe diagnostic.
    *******************************************************************************/
   @Test
   void cliFailure()
   {
      assertThatThrownBy(() -> new CliMjmlCompiler("missing-mjml-binary-for-test").compile("private-source"))
         .hasMessage("MJML compilation failed or exceeded 20 seconds");
   }
}
