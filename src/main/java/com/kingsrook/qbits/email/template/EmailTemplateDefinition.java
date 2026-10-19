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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.outbox.EmailCategory;


/*******************************************************************************
 ** A template another qbit ships with its own code: its name, content and
 ** variable contract. Hand it to {@link EmailTemplates#ensure} at startup or on
 ** first use; it is created once as an ACTIVE version 1 and never overwritten.
 *******************************************************************************/
public final class EmailTemplateDefinition
{
   private String name;
   private String label;
   private EmailCategory category;
   private String kind = "MARKDOWN";
   private String subject;
   private String body;
   private String textBody;
   private String variablesSchema;
   private final List<Map<String, Object>> variables = new ArrayList<>();
   private String sampleVariables;
   private String ownerQbit;
   private String editPermissionName;

   /*******************************************************************************
    ** Unique snake_case template name, as used by EmailRequest.withTemplateName.
    *******************************************************************************/
   public EmailTemplateDefinition withName(String name)
   {
      this.name = name;
      return (this);
   }

   /*******************************************************************************
    ** Human label shown in the template table.
    *******************************************************************************/
   public EmailTemplateDefinition withLabel(String label)
   {
      this.label = label;
      return (this);
   }

   /*******************************************************************************
    ** Template category; it decides opt-out and PHI rules.
    *******************************************************************************/
   public EmailTemplateDefinition withCategory(EmailCategory category)
   {
      this.category = category;
      return (this);
   }

   /*******************************************************************************
    ** MARKDOWN (the default), MJML or HTML.
    *******************************************************************************/
   public EmailTemplateDefinition withKind(String kind)
   {
      this.kind = kind;
      return (this);
   }

   /*******************************************************************************
    ** Subject source, which may use variables.
    *******************************************************************************/
   public EmailTemplateDefinition withSubject(String subject)
   {
      this.subject = subject;
      return (this);
   }

   /*******************************************************************************
    ** Body source in the template's kind.
    *******************************************************************************/
   public EmailTemplateDefinition withBody(String body)
   {
      this.body = body;
      return (this);
   }

   /*******************************************************************************
    ** Optional plain-text source; omitted, it is derived from the body.
    *******************************************************************************/
   public EmailTemplateDefinition withTextBody(String textBody)
   {
      this.textBody = textBody;
      return (this);
   }

   /*******************************************************************************
    ** The variables schema as the JSON list the template table stores. Use this or
    ** withVariable, not both.
    *******************************************************************************/
   public EmailTemplateDefinition withVariablesSchema(String variablesSchema)
   {
      this.variablesSchema = variablesSchema;
      return (this);
   }

   /*******************************************************************************
    ** Declare one variable (type: STRING, NUMBER, DATE, DATE_TIME, BOOLEAN, URL,
    ** LIST or RECORD; classification: PUBLIC, CONFIDENTIAL, PII, PHI or null).
    *******************************************************************************/
   public EmailTemplateDefinition withVariable(String name, String label, String type, boolean required, String classification)
   {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", name);
      entry.put("label", label);
      entry.put("type", type);
      entry.put("required", required);
      entry.put("classification", classification);
      variables.add(entry);
      return (this);
   }

   /*******************************************************************************
    ** Synthetic sample values as a JSON object, used to validate and preview.
    *******************************************************************************/
   public EmailTemplateDefinition withSampleVariables(String sampleVariables)
   {
      this.sampleVariables = sampleVariables;
      return (this);
   }

   /*******************************************************************************
    ** The qbit that owns this template.
    *******************************************************************************/
   public EmailTemplateDefinition withOwnerQbit(String ownerQbit)
   {
      this.ownerQbit = ownerQbit;
      return (this);
   }

   /*******************************************************************************
    ** Optional permission, besides the email permission, needed to edit the template.
    *******************************************************************************/
   public EmailTemplateDefinition withEditPermissionName(String editPermissionName)
   {
      this.editPermissionName = editPermissionName;
      return (this);
   }

   /*******************************************************************************
    ** The template name.
    *******************************************************************************/
   public String getName()
   {
      return (name);
   }

   /*******************************************************************************
    ** The template label.
    *******************************************************************************/
   public String getLabel()
   {
      return (label);
   }

   /*******************************************************************************
    ** The template category.
    *******************************************************************************/
   public EmailCategory getCategory()
   {
      return (category);
   }

   /*******************************************************************************
    ** The template kind.
    *******************************************************************************/
   public String getKind()
   {
      return (kind);
   }

   /*******************************************************************************
    ** The subject source.
    *******************************************************************************/
   public String getSubject()
   {
      return (subject);
   }

   /*******************************************************************************
    ** The body source.
    *******************************************************************************/
   public String getBody()
   {
      return (body);
   }

   /*******************************************************************************
    ** The optional plain-text source.
    *******************************************************************************/
   public String getTextBody()
   {
      return (textBody);
   }

   /*******************************************************************************
    ** The schema JSON: the explicit text, or the typed variables as a JSON list.
    *******************************************************************************/
   public String getVariablesSchema()
   {
      if(variablesSchema != null && !variablesSchema.isBlank())
      {
         return (variablesSchema);
      }
      return (VariableSchema.json(variables));
   }

   /*******************************************************************************
    ** The sample values JSON, defaulting to an empty object.
    *******************************************************************************/
   public String getSampleVariables()
   {
      return (sampleVariables == null || sampleVariables.isBlank() ? "{}" : sampleVariables);
   }

   /*******************************************************************************
    ** The owning qbit.
    *******************************************************************************/
   public String getOwnerQbit()
   {
      return (ownerQbit);
   }

   /*******************************************************************************
    ** The optional edit permission.
    *******************************************************************************/
   public String getEditPermissionName()
   {
      return (editPermissionName);
   }

   /*******************************************************************************
    ** Plain-language reasons the definition is incomplete or malformed, before any
    ** rendering check. Empty when it passes.
    *******************************************************************************/
   public List<String> problems()
   {
      List<String> reasons = new ArrayList<>();
      if(name == null || !name.matches("[a-z][a-z0-9_]{0,99}"))
      {
         reasons.add("Name must be snake_case: lowercase letters, digits and underscores, starting with a letter");
      }
      if(label == null || label.isBlank())
      {
         reasons.add("Label is required");
      }
      if(category == null)
      {
         reasons.add("Category is required");
      }
      if(kind == null || !List.of("MARKDOWN", "MJML", "HTML", "CRM_LEGACY").contains(kind))
      {
         reasons.add("Kind must be MARKDOWN, MJML, HTML or CRM_LEGACY");
      }
      if(subject == null || subject.isBlank())
      {
         reasons.add("Subject is required");
      }
      if(body == null || body.isBlank())
      {
         reasons.add("Body is required");
      }
      if(variablesSchema != null && !variablesSchema.isBlank() && !variables.isEmpty())
      {
         reasons.add("Declare variables with withVariablesSchema or withVariable, not both");
      }
      else
      {
         try
         {
            new VariableSchema(getVariablesSchema());
         }
         catch(IllegalArgumentException e)
         {
            reasons.add(e.getMessage());
         }
      }
      try
      {
         VariableSchema.values(sampleVariables);
      }
      catch(IllegalArgumentException e)
      {
         reasons.add(e.getMessage());
      }
      return (reasons);
   }
}
