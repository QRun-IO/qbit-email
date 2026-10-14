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


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.metadata.EmailTables;


/*******************************************************************************
 ** What a host wires into its ESB, Quick Search and API, without this qbit
 ** depending on those modules (the same opt-in hooks as qbit-sql-workbench).
 **
 ** <ul>
 **   <li>{@link #esbTableNames()}: publish these tables' inserts, updates and
 **       deletes to an ESB topic (the main tables).</li>
 **   <li>{@link #quickSearchTables()}: index these in Quick Search, with field
 **       weights.</li>
 **   <li>{@link #apiTableNames()}: expose these in the host's QQQ API. The API
 **       gets exactly the admin's rules: the same permissions, customizers and
 **       read-only tables.</li>
 ** </ul>
 *******************************************************************************/
public final class EmailIntegrations
{
   /*******************************************************************************
    ** A table for Quick Search: field weights, the timestamp for incremental
    ** indexing, and the record label.
    *******************************************************************************/
   public record SearchableTable(String tableName, Map<String, Integer> fieldWeights, String basepullTimestamp, String recordLabelFormat, List<String> recordLabelFields)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailIntegrations()
   {
   }



   /*******************************************************************************
    ** Tables to publish on the ESB.
    *******************************************************************************/
   public static List<String> esbTableNames()
   {
      return (List.of(EmailTables.MESSAGE, EmailTables.TEMPLATE, EmailTables.SUPPRESSION));
   }



   /*******************************************************************************
    ** Tables for the host's QQQ API.
    *******************************************************************************/
   public static List<String> apiTableNames()
   {
      return (List.of(EmailTables.MESSAGE, EmailTables.TEMPLATE, EmailTables.SUPPRESSION));
   }



   /*******************************************************************************
    ** Tables for Quick Search.
    *******************************************************************************/
   public static List<SearchableTable> quickSearchTables()
   {
      return (List.of(
         new SearchableTable(EmailTables.MESSAGE, Map.of("id", 10, "providerMessageId", 8, "status", 4, "linkedRecordId", 3), "modifyDate", "%s", List.of("id")),
         new SearchableTable(EmailTables.TEMPLATE, Map.of("name", 10, "label", 8, "description", 3), "modifyDate", "%s", List.of("name")),
         new SearchableTable(EmailTables.SUPPRESSION, Map.of("address", 10, "scope", 4, "reason", 3), "modifyDate", "%s", List.of("address"))));
   }
}
