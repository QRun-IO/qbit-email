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
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.VariableSchema;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Brand selection shared by sends, activation and previews.
 *******************************************************************************/
public final class EmailBrands
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailBrands()
   {
   }

   /*******************************************************************************
    ** Explicit request, template, app brand, default brand, then neutral.
    *******************************************************************************/
   public static QRecord resolve(String requested, QRecord template) throws QException
   {
      if(requested != null)
      {
         List<QRecord> rows = SystemWrites.query(EmailTables.BRAND, EmailStore.equal("name", requested));
         if(rows.isEmpty())
         {
            throw (new IllegalArgumentException("Unknown email brand"));
         }
         return (rows.getFirst());
      }
      if(template != null && template.getValueInteger("brandId") != null)
      {
         QRecord brand = EmailStore.get(EmailTables.BRAND, template.getValueInteger("brandId"), null);
         if(brand == null)
         {
            throw (new IllegalArgumentException("Template brand is missing"));
         }
         return (brand);
      }
      List<QRecord> brands = SystemWrites.query(EmailTables.BRAND, EmailStore.equal("appName", EmailRuntime.config().getAppName()));
      if(brands.isEmpty())
      {
         brands = SystemWrites.query(EmailTables.BRAND, EmailStore.equal("isDefault", true));
      }
      if(brands.size() > 1)
      {
         throw (new IllegalArgumentException("More than one default email brand matches"));
      }
      return (brands.isEmpty() ? new QRecord().withValue("label", EmailRuntime.config().getAppLabel()).withValue("name", "neutral") : brands.getFirst());
   }

   /*******************************************************************************
    ** Refuse unsafe URLs and inaccessible colors before a brand is saved.
    *******************************************************************************/
   public static void validate(QRecord brand)
   {
      ContrastCheck.validate(brand);
      for(String field : List.of("logoUrl", "logoDarkUrl", "siteUrl"))
      {
         String value = brand.getValueString(field);
         if(value != null && !value.isBlank())
         {
            VariableSchema.url(value);
         }
      }
      social(brand);
   }

   /*******************************************************************************
    ** Parse the small social-link list with strict web URLs.
    *******************************************************************************/
   public static List<java.util.Map<String, String>> social(QRecord brand)
   {
      try
      {
         String json = brand.getValueString("socialLinks");
         if(json == null || json.isBlank())
         {
            return (List.of());
         }
         var links = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
            new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, String>>>() {});
         links.forEach(link -> VariableSchema.url(link.get("url")));
         return (links);
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Social links need labels and http/https URLs"));
      }
   }
}
