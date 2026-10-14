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

package com.kingsrook.qbits.email.support;


import java.io.Serializable;
import java.util.List;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Small database helpers shared by dispatcher and actions.
 *******************************************************************************/
public final class EmailStore
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailStore()
   {
   }

   /*******************************************************************************
    ** Equality filter.
    *******************************************************************************/
   public static QQueryFilter equal(String field, Serializable value)
   {
      return (new QQueryFilter(new QFilterCriteria(field, QCriteriaOperator.EQUALS, value)));
   }

   /*******************************************************************************
    ** Read a row in a transaction.
    *******************************************************************************/
   public static QRecord get(String table, Integer id, QBackendTransaction transaction) throws QException
   {
      List<QRecord> rows = SystemWrites.query(table, equal("id", id), transaction);
      return (rows.isEmpty() ? null : rows.get(0));
   }

   /*******************************************************************************
    ** Check write errors without exposing record contents.
    *******************************************************************************/
   public static void update(String table, QRecord row, QBackendTransaction transaction) throws QException
   {
      List<QRecord> rows = SystemWrites.update(table, List.of(row), transaction);
      if(rows.isEmpty() || (rows.get(0).getErrors() != null && !rows.get(0).getErrors().isEmpty()))
      {
         throw (new QException("Email database update failed"));
      }
   }

   /*******************************************************************************
    ** Serialize provider rate checks and claims using the database row lock.
    *******************************************************************************/
   public static QRecord lockProvider(Integer id, QBackendTransaction transaction) throws QException
   {
      if(get(EmailTables.PROVIDER, id, transaction) == null)
      {
         return (null);
      }
      update(EmailTables.PROVIDER, new QRecord().withValue("id", id).withValue("dispatchLock", 0), transaction);
      return (get(EmailTables.PROVIDER, id, transaction));
   }
}
