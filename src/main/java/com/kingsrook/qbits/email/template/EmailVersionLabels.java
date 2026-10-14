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


import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Human-readable version labels, resolving parent names as the current viewer.
 *******************************************************************************/
public class EmailVersionLabels implements TableCustomizerInterface
{
   /*******************************************************************************
    ** Batch parents to avoid one query per version, without bypassing security.
    *******************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
   {
      var ids = records.stream().map(r -> r.getValueInteger("templateId")).filter(Objects::nonNull).distinct().toList();
      Map<Integer, String> names = new HashMap<>();
      if(!ids.isEmpty())
      {
         List<QRecord> templates = new QueryAction().execute(new QueryInput(EmailTables.TEMPLATE)
            .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.IN, ids))
            .withFieldNamesToInclude(new HashSet<>(List.of("id", "name")))).getRecords();
         templates.forEach(t -> names.put(t.getValueInteger("id"), t.getValueString("name")));
      }
      for(QRecord row : records)
      {
         String locale = row.getValueString("locale");
         row.setRecordLabel(names.getOrDefault(row.getValueInteger("templateId"), "Template") + " v" + row.getValueInteger("versionNumber")
            + (locale == null || locale.isBlank() || "en".equals(locale) ? "" : " (" + locale + ")"));
      }
      return (records);
   }
}
