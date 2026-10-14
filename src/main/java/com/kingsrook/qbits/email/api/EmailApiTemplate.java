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

package com.kingsrook.qbits.email.api;


import java.util.ArrayList;
import java.util.List;


/*******************************************************************************
 ** A create-once template definition. Existing templates and versions are never overwritten.
 ** New templates are not PHI approved; approval remains a separately authorized operation.
 *******************************************************************************/
public final class EmailApiTemplate
{
   private String name;
   private String label;
   private EmailApiCategory category;
   private String kind = "MARKDOWN";
   private String subject;
   private String body;
   private String textBody;
   private String variablesSchema;
   private List<EmailApiVariable> variables = new ArrayList<>();
   private String sampleVariables;
   private String ownerQbit;
   private String editPermissionName;



   /*******************************************************************************
    ** Getter for name.
    *******************************************************************************/
   public String getName()
   {
      return (name);
   }



   /*******************************************************************************
    ** Fluent setter for name.
    *******************************************************************************/
   public EmailApiTemplate withName(String name)
   {
      this.name = name;
      return (this);
   }



   /*******************************************************************************
    ** Getter for label.
    *******************************************************************************/
   public String getLabel()
   {
      return (label);
   }



   /*******************************************************************************
    ** Fluent setter for label.
    *******************************************************************************/
   public EmailApiTemplate withLabel(String label)
   {
      this.label = label;
      return (this);
   }



   /*******************************************************************************
    ** Getter for category.
    *******************************************************************************/
   public EmailApiCategory getCategory()
   {
      return (category);
   }



   /*******************************************************************************
    ** Fluent setter for category.
    *******************************************************************************/
   public EmailApiTemplate withCategory(EmailApiCategory category)
   {
      this.category = category;
      return (this);
   }



   /*******************************************************************************
    ** Getter for kind.
    *******************************************************************************/
   public String getKind()
   {
      return (kind);
   }



   /*******************************************************************************
    ** Fluent setter for kind.
    *******************************************************************************/
   public EmailApiTemplate withKind(String kind)
   {
      this.kind = kind;
      return (this);
   }



   /*******************************************************************************
    ** Getter for subject.
    *******************************************************************************/
   public String getSubject()
   {
      return (subject);
   }



   /*******************************************************************************
    ** Fluent setter for subject.
    *******************************************************************************/
   public EmailApiTemplate withSubject(String subject)
   {
      this.subject = subject;
      return (this);
   }



   /*******************************************************************************
    ** Getter for body.
    *******************************************************************************/
   public String getBody()
   {
      return (body);
   }



   /*******************************************************************************
    ** Fluent setter for body.
    *******************************************************************************/
   public EmailApiTemplate withBody(String body)
   {
      this.body = body;
      return (this);
   }



   /*******************************************************************************
    ** Getter for textBody.
    *******************************************************************************/
   public String getTextBody()
   {
      return (textBody);
   }



   /*******************************************************************************
    ** Fluent setter for textBody.
    *******************************************************************************/
   public EmailApiTemplate withTextBody(String textBody)
   {
      this.textBody = textBody;
      return (this);
   }



   /*******************************************************************************
    ** Getter for variablesSchema.
    *******************************************************************************/
   public String getVariablesSchema()
   {
      return (variablesSchema);
   }



   /*******************************************************************************
    ** Fluent setter for variablesSchema.
    *******************************************************************************/
   public EmailApiTemplate withVariablesSchema(String variablesSchema)
   {
      this.variablesSchema = variablesSchema;
      return (this);
   }



   /*******************************************************************************
    ** Getter for variables.
    *******************************************************************************/
   public List<EmailApiVariable> getVariables()
   {
      return (variables);
   }



   /*******************************************************************************
    ** Fluent setter for variables.
    *******************************************************************************/
   public EmailApiTemplate withVariables(List<EmailApiVariable> variables)
   {
      this.variables = variables;
      return (this);
   }



   /*******************************************************************************
    ** Getter for sampleVariables.
    *******************************************************************************/
   public String getSampleVariables()
   {
      return (sampleVariables);
   }



   /*******************************************************************************
    ** Fluent setter for sampleVariables.
    *******************************************************************************/
   public EmailApiTemplate withSampleVariables(String sampleVariables)
   {
      this.sampleVariables = sampleVariables;
      return (this);
   }



   /*******************************************************************************
    ** Getter for ownerQbit.
    *******************************************************************************/
   public String getOwnerQbit()
   {
      return (ownerQbit);
   }



   /*******************************************************************************
    ** Fluent setter for ownerQbit.
    *******************************************************************************/
   public EmailApiTemplate withOwnerQbit(String ownerQbit)
   {
      this.ownerQbit = ownerQbit;
      return (this);
   }



   /*******************************************************************************
    ** Getter for editPermissionName.
    *******************************************************************************/
   public String getEditPermissionName()
   {
      return (editPermissionName);
   }



   /*******************************************************************************
    ** Fluent setter for editPermissionName.
    *******************************************************************************/
   public EmailApiTemplate withEditPermissionName(String editPermissionName)
   {
      this.editPermissionName = editPermissionName;
      return (this);
   }



   /*******************************************************************************
    ** Append a typed variable declaration. Use this or variablesSchema, not both.
    *******************************************************************************/
   public EmailApiTemplate withVariable(EmailApiVariable variable)
   {
      variables.add(variable);
      return (this);
   }



   /*******************************************************************************
    ** Append a variable declaration using the template schema vocabulary.
    *******************************************************************************/
   public EmailApiTemplate withVariable(String name, String label, String type, boolean required, String classification)
   {
      return (withVariable(new EmailApiVariable(name, label, type, required, classification)));
   }
}
