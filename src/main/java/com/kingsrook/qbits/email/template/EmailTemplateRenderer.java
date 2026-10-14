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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.phi.EmailBodyEncryption;
import com.kingsrook.qbits.email.phi.EmailClassifications;
import com.kingsrook.qbits.email.phi.EmailClassifications.Level;
import com.kingsrook.qbits.email.phi.EmailValueTokens;
import com.kingsrook.qbits.email.render.EmailBrands;
import com.kingsrook.qbits.email.render.EmailLayout;
import com.kingsrook.qbits.email.render.EmailMarkdown;
import com.kingsrook.qbits.email.render.HtmlToText;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Queue-time template resolution and rendering with sender-visible record data.
 *******************************************************************************/
public final class EmailTemplateRenderer
{
   private final QRecord template;
   private final QRecord version;
   private final QRecord brand;
   private final VariableSchema schema;
   private final Map<String, Serializable> values;
   private final Map<String, EmailRequest.RecordVariable> records;
   private final EmailCategory category;
   private final boolean approved;
   private final List<String> warnings = new ArrayList<>();

   /*******************************************************************************
    ** Resolve a template's active locale, falling back only to English.
    *******************************************************************************/
   public static EmailTemplateRenderer prepare(EmailRequest request) throws QException
   {
      if("email_secure_message".equals(request.getTemplateName()))
      {
         EmailBuiltIns.ensure();
      }
      List<QRecord> templates = SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", request.getTemplateName()));
      if(templates.size() != 1)
      {
         throw (new IllegalArgumentException("Unknown email template"));
      }
      QRecord template = templates.getFirst();
      List<QRecord> versions = SystemWrites.query(EmailTables.VERSION, EmailStore.equal("templateId", template.getValueInteger("id"))
         .withCriteria(new QFilterCriteria("status", QCriteriaOperator.EQUALS, "ACTIVE")));
      QRecord version = versions.stream().filter(v -> Objects.equals(request.getLocale(), v.getValueString("locale"))).findFirst()
         .orElseGet(() -> versions.stream().filter(v -> "en".equals(v.getValueString("locale"))).findFirst().orElse(null));
      if(version == null)
      {
         throw (new IllegalArgumentException("Template has no active version for this locale or en"));
      }
      return (new EmailTemplateRenderer(template, version, request));
   }

   /*******************************************************************************
    ** Validate schema, provenance and all template syntax before any outbox write.
    *******************************************************************************/
   public EmailTemplateRenderer(QRecord template, QRecord version, EmailRequest request) throws QException
   {
      this.template = template;
      this.version = version;
      approved = Boolean.TRUE.equals(template.getValueBoolean("phiApproved")) && version.getValue("id") != null
         && Objects.equals(template.getValue("approvedVersionId"), version.getValue("id"));
      brand = EmailBrands.resolve(approved ? null : request.getBrandName(), template);
      if(approved)
      {
         for(String field : List.of("logoUrl", "logoDarkUrl"))
         {
            if(brand.getValueString(field) != null && !EmailBrands.allowedImage(brand.getValueString(field), brand))
            {
               brand.setValue(field, null);
               warnings.add("Brand logo omitted: image host is not allowed");
            }
         }
      }
      EmailBrands.validate(brand);
      schema = new VariableSchema(version.getValueString("variablesSchema"));
      records = new LinkedHashMap<>(request.getRecordVariables());
      java.util.Set<String> references = new java.util.LinkedHashSet<>();
      for(String field : List.of("subject", "preheader", "bodySource", "textSource", "compiledHtml"))
      {
         references.addAll(new EmailTemplateEngine().parse(version.getValueString(field), schema).references());
      }
      if(!"MARKDOWN".equals(template.getValueString("kind")))
      {
         EmailTemplateEngine.validateHtml(version.getValueString("bodySource"));
         EmailTemplateEngine.validateHtml(version.getValueString("compiledHtml"));
      }
      Map<String, Serializable> supplied = new LinkedHashMap<>(request.getVariables());
      for(var binding : records.entrySet())
      {
         supplied.keySet().removeIf(key -> key.startsWith(binding.getKey() + "."));
         QTableMetaData table = QContext.getQInstance().getTable(binding.getValue().table());
         if(table == null)
         {
            throw (new IllegalArgumentException("Record variable table is unavailable"));
         }
         java.util.Set<String> fields = references.stream().filter(path -> path.startsWith(binding.getKey() + "."))
            .map(path -> path.substring(binding.getKey().length() + 1).split("\\.")[0]).collect(java.util.stream.Collectors.toSet());
         if(fields.isEmpty())
         {
            supplied.put(binding.getKey(), new LinkedHashMap<>());
            continue;
         }
         // Never use SystemWrites here: masking and record locks belong to the sender.
         List<QRecord> rows = new QueryAction().execute(new QueryInput(table.getName()).withShouldFetchHeavyFields(true).withFieldNamesToInclude(fields)
            .withFilter(EmailStore.equal(table.getPrimaryKeyField(), binding.getValue().id()).withLimit(1))).getRecords();
         if(rows.isEmpty())
         {
            throw (new IllegalArgumentException("Record variable is unavailable to the sender"));
         }
         Map<String, Serializable> visible = new LinkedHashMap<>();
         fields.forEach(field -> visible.put(field, rows.getFirst().getValue(field)));
         for(var field : table.getFields().values())
         {
            var lock = field.getFieldSecurityLock();
            if(lock != null && fields.contains(field.getName()))
            {
               var session = QContext.getQSession();
               List<Serializable> keys = session == null ? List.of() : session.getSecurityKeyValues(lock.getSecurityKeyType());
               boolean override = keys != null && lock.getOverrideValues() != null && keys.stream().anyMatch(lock.getOverrideValues()::contains);
               boolean allowed = lock.getDefaultBehavior() == com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock.Behavior.ALLOW;
               if(override ? allowed : !allowed)
               {
                  visible.put(field.getName(), "************");
               }
            }
         }
         supplied.put(binding.getKey(), new LinkedHashMap<>(visible));
      }
      for(String name : supplied.keySet())
      {
         if(!schema.knows(name) || name.startsWith("brand.") || name.equals("message.id") || name.equals("now") || name.equals("unsubscribe_url"))
         {
            throw (new IllegalArgumentException("Unknown or reserved input variable " + name));
         }
      }
      values = schema.validate(supplied);
      category = EmailCategory.valueOf(Objects.requireNonNullElse(template.getValueString("category"), "TRANSACTIONAL"));
      if(request.getCategory() != null && request.getCategory() != category)
      {
         throw (new IllegalArgumentException("Template category cannot be overridden"));
      }
      if(approved && category == EmailCategory.MARKETING)
      {
         throw (new IllegalArgumentException("PHI-approved templates cannot send MARKETING email"));
      }
      for(VariableSchema.Entry entry : schema.entries().values())
      {
         requireApproval(entry.name(), classification(entry.name()));
      }
      inspect(values, "");
      for(String field : List.of("subject", "preheader", "bodySource", "textSource"))
      {
         new EmailTemplateEngine().parse(version.getValueString(field), schema);
      }
      if(version.getValueString("subject") == null || version.getValueString("bodySource") == null)
      {
         throw (new IllegalArgumentException("A template subject and body are required"));
      }
   }

   /*******************************************************************************
    ** Check actual nested fields as well as declared but absent bindings.
    *******************************************************************************/
   private void inspect(Object object, String path)
   {
      if(!path.isEmpty())
      {
         requireApproval(path, classification(path));
      }
      if(object instanceof Map<?, ?> map)
      {
         map.forEach((key, value) -> inspect(value, path.isEmpty() ? key.toString() : path + "." + key));
      }
      else if(object instanceof List<?> list)
      {
         list.forEach(value -> inspect(value, path));
      }
   }

   /*******************************************************************************
    ** A classification failure names only the binding, never its value.
    *******************************************************************************/
   private void requireApproval(String name, Level level)
   {
      if(EmailClassifications.sensitive(level) && !approved)
      {
         throw (new IllegalArgumentException("The variable " + name + " is " + level + "; this template is not approved for PHI."));
      }
   }

   /*******************************************************************************
    ** Resolve record provenance before schema declarations; inherit parent levels.
    *******************************************************************************/
   private Level classification(String path)
   {
      VariableSchema.Entry declaration = schema.entries().get(path);
      if(declaration == null)
      {
         declaration = schema.entries().values().stream().filter(e -> path.startsWith(e.name() + "."))
            .max(java.util.Comparator.comparingInt(e -> e.name().length())).orElse(null);
      }
      String declared = declaration == null ? null : declaration.classification();
      for(var record : records.entrySet())
      {
         if(path.startsWith(record.getKey() + "."))
         {
            String field = path.substring(record.getKey().length() + 1).split("\\.")[0];
            return (EmailClassifications.ofField(QContext.getQInstance().getTable(record.getValue().table()), field, declared, EmailRuntime.config()));
         }
      }
      QTableMetaData table = declaration == null || declaration.table() == null ? null : QContext.getQInstance().getTable(declaration.table());
      if(declaration != null && declaration.table() != null && (table == null || table.getField(declaration.field()) == null))
      {
         throw (new IllegalArgumentException("Schema field binding is unavailable"));
      }
      return (EmailClassifications.ofField(table, declaration == null ? null : declaration.field(), declared, EmailRuntime.config()));
   }

   /*******************************************************************************
    ** Validate the actual provider at queue and delivery time.
    *******************************************************************************/
   public void validateProvider(QRecord provider)
   {
      if(approved)
      {
         checkProvider(provider);
      }
   }

   /*******************************************************************************
    ** Recheck mutable provider attestations before encrypted content leaves storage.
    *******************************************************************************/
   public static void checkProvider(QRecord provider)
   {
      if(!Boolean.TRUE.equals(provider.getValueBoolean("phiAllowed")) || Boolean.TRUE.equals(provider.getValueBoolean("trackOpens"))
         || Boolean.TRUE.equals(provider.getValueBoolean("trackClicks")))
      {
         throw (new IllegalArgumentException("PHI email needs a phi_allowed provider with open and click tracking disabled"));
      }
   }

   /*******************************************************************************
    ** Render with the real message identity; no compiled source is rebuilt at send.
    *******************************************************************************/
   public QRecord render(String messageId, String unsubscribe, boolean encrypt)
   {
      Map<String, Serializable> context = new LinkedHashMap<>(values);
      for(String field : List.of("label", "name", "siteUrl", "logoUrl", "logoDarkUrl", "logoAlt", "postalAddress", "primaryColor", "textColor", "backgroundColor", "darkBackgroundColor", "darkTextColor", "buttonTextColor", "darkPrimaryColor", "darkButtonTextColor", "footerMarkdown"))
      {
         context.put("brand." + field.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT), brand.getValueString(field));
      }
      context.put("message.id", messageId);
      context.put("now", EmailRuntime.clock().instant().toString());
      context.put("unsubscribe_url", category == EmailCategory.NOTIFICATION || category == EmailCategory.MARKETING ? unsubscribe : "");
      EmailTemplateEngine engine = new EmailTemplateEngine();
      EmailTemplateEngine.Budget budget = new EmailTemplateEngine.Budget();
      String subject = engine.parse(version.getValueString("subject"), schema).render(context, EmailTemplateEngine.Context.TEXT, budget, warnings);
      if(subject.contains("\r") || subject.contains("\n"))
      {
         throw (new IllegalArgumentException("Subject must be one line"));
      }
      String kind = template.getValueString("kind");
      String source = "MJML".equals(kind) ? version.getValueString("compiledHtml") : version.getValueString("bodySource");
      if(source == null)
      {
         throw (new IllegalArgumentException("MJML version must be compiled before sending"));
      }
      String body = engine.parse(source, schema).render(context, "MARKDOWN".equals(kind) ? EmailTemplateEngine.Context.MARKDOWN : EmailTemplateEngine.Context.HTML, budget, warnings);
      String preheader = engine.parse(version.getValueString("preheader"), schema).render(context, EmailTemplateEngine.Context.TEXT, budget, warnings);
      String html = "MARKDOWN".equals(kind) ? EmailLayout.render(EmailMarkdown.render(body, brand), preheader, brand, category, unsubscribe)
         : EmailLayout.appendFooter(body, brand, category, unsubscribe);
      if(approved)
      {
         html = restrictImages(html);
      }
      String text = version.getValueString("textSource") == null ? HtmlToText.convert(html)
         : engine.parse(version.getValueString("textSource"), schema).render(context, EmailTemplateEngine.Context.TEXT, budget, warnings);
      if((long) subject.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
         + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576)
      {
         throw (new IllegalArgumentException("Template output is too large"));
      }
      return (new QRecord().withValue("subject", approved && encrypt ? EmailBodyEncryption.encrypt(subject, "subject", messageId) : subject)
         .withValue("htmlBody", approved && encrypt ? EmailBodyEncryption.encrypt(html, "htmlBody", messageId) : html)
         .withValue("textBody", approved && encrypt ? EmailBodyEncryption.encrypt(text, "textBody", messageId) : text)
         .withValue("variablesSnapshot", VariableSchema.json(snapshot(values, "", messageId)))
         .withValue("templateId", template.getValue("id")).withValue("templateVersionId", version.getValue("id"))
         .withValue("brandId", brand.getValue("id")).withValue("locale", version.getValue("locale"))
         .withValue("category", category.name()).withValue("phiApproved", approved));
   }

   /*******************************************************************************
    ** Recursive redaction prevents nested records or lists bypassing tokenization.
    *******************************************************************************/
   private Object snapshot(Object value, String path, String messageId)
   {
      if(!path.isEmpty() && (EmailClassifications.sensitive(classification(path)) || unclassifiedRecordField(path)))
      {
         for(var record : records.entrySet())
         {
            if(path.startsWith(record.getKey() + ".") && !path.substring(record.getKey().length() + 1).contains("."))
            {
               QTableMetaData table = QContext.getQInstance().getTable(record.getValue().table());
               var field = table.getField(path.substring(record.getKey().length() + 1));
               if(field != null)
               {
                  return (EmailValueTokens.forRecordField(table, field, record.getValue().id(), (Serializable) value));
               }
            }
         }
         return (EmailValueTokens.forContext(List.of("email", template.getValueString("name"), path, messageId), (Serializable) value));
      }
      if(value instanceof Map<?, ?> map)
      {
         Map<String, Object> result = new LinkedHashMap<>();
         map.forEach((key, item) -> result.put(key.toString(), snapshot(item, path.isEmpty() ? key.toString() : path + "." + key, messageId)));
         return (result);
      }
      if(value instanceof List<?> list)
      {
         List<Object> result = new ArrayList<>();
         list.forEach(item -> result.add(snapshot(item, path, messageId)));
         return (result);
      }
      return (value == null || value instanceof Number || value instanceof Boolean ? value : value.toString());
   }

   /*******************************************************************************
    ** Missing record classification fails closed only in the retained snapshot.
    *******************************************************************************/
   private boolean unclassifiedRecordField(String path)
   {
      for(var record : records.entrySet())
      {
         if(path.startsWith(record.getKey() + "."))
         {
            String field = path.substring(record.getKey().length() + 1).split("\\.")[0];
            return (!EmailClassifications.hasSource(QContext.getQInstance().getTable(record.getValue().table()), field, EmailRuntime.config()));
         }
      }
      return (false);
   }

   /*******************************************************************************
    ** Warnings contain binding names and policy reasons, never input values.
    *******************************************************************************/
   public List<String> warnings()
   {
      return (List.copyOf(new java.util.LinkedHashSet<>(warnings)));
   }

   /*******************************************************************************
    ** Apply the image policy after Markdown, layout and brand footer expansion.
    *******************************************************************************/
   private String restrictImages(String html)
   {
      var document = org.jsoup.Jsoup.parse(html);
      document.outputSettings().prettyPrint(false);
      document.select("[src],[background],[srcset],image").forEach(element ->
      {
         element.removeAttr("srcset");
         if(element.hasAttr("src") && !EmailBrands.allowedImage(element.attr("src"), brand))
         {
            element.remove();
            warnings.add("Image omitted: image host is not allowed");
         }
         if(element.hasAttr("background") && !EmailBrands.allowedImage(element.attr("background"), brand))
         {
            element.removeAttr("background");
            warnings.add("Image omitted: image host is not allowed");
         }
         if("image".equals(element.normalName()))
         {
            element.remove();
            warnings.add("SVG image omitted from approved email");
         }
      });
      org.jsoup.select.NodeTraversor.filter((node, depth) ->
      {
         if(node instanceof org.jsoup.nodes.Comment comment && comment.getData().toLowerCase(java.util.Locale.ROOT).contains("[if"))
         {
            warnings.add("Conditional markup omitted from approved email");
            return (org.jsoup.select.NodeFilter.FilterResult.REMOVE);
         }
         return (org.jsoup.select.NodeFilter.FilterResult.CONTINUE);
      }, document);
      // CSS image expressions can use escapes and image-set, so approved content
      // uses img elements instead of CSS images. Layout colors remain intact.
      document.select("[style]").forEach(element ->
      {
         String style = element.attr("style");
         if(style.toLowerCase(java.util.Locale.ROOT).contains("url") || style.contains("\\") || style.toLowerCase(java.util.Locale.ROOT).contains("image-set"))
         {
            element.removeAttr("style");
            warnings.add("CSS image omitted from approved email");
         }
      });
      document.select("style").forEach(element ->
      {
         String style = element.data();
         if(style.toLowerCase(java.util.Locale.ROOT).contains("url") || style.contains("\\") || style.toLowerCase(java.util.Locale.ROOT).contains("@import") || style.toLowerCase(java.util.Locale.ROOT).contains("image-set"))
         {
            element.remove();
            warnings.add("CSS image omitted from approved email");
         }
      });
      return (document.outerHtml());
   }

}
