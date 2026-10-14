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


import java.util.List;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.permissions.CorePermissionEnforcer;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The qbit's own reads and writes (the delivery engine, the outbox, health
 ** and rotation). They run in core's authorized scope, so they work with core
 ** permission enforcement on, and they mark the thread so the tables'
 ** customizers can tell a qbit write from a person's or the API's.
 *******************************************************************************/
public final class SystemWrites
{
   private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);



   /*******************************************************************************
    ** Work that may throw.
    *******************************************************************************/
   @FunctionalInterface
   public interface Work<T>
   {
      /*******************************************************************************
       ** Do it.
       *******************************************************************************/
      T run() throws QException;
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SystemWrites()
   {
   }



   /*******************************************************************************
    ** Run work as the qbit.
    *******************************************************************************/
   public static <T> T call(Work<T> work) throws QException
   {
      DEPTH.set(DEPTH.get() + 1);
      try(CorePermissionEnforcer.AuthorizedScope ignored = CorePermissionEnforcer.openAuthorizedScope())
      {
         return (work.run());
      }
      finally
      {
         DEPTH.set(DEPTH.get() - 1);
      }
   }



   /*******************************************************************************
    ** True while the qbit itself is writing on this thread.
    *******************************************************************************/
   public static boolean active()
   {
      return (DEPTH.get() > 0);
   }



   /*******************************************************************************
    ** Insert, returning the output (records may carry errors).
    *******************************************************************************/
   public static InsertOutput insert(String table, List<QRecord> records, QBackendTransaction transaction) throws QException
   {
      return (call(() -> new InsertAction().execute(new InsertInput(table).withRecords(records).withTransaction(transaction))));
   }



   /*******************************************************************************
    ** Insert one record, returning it (with errors if it failed).
    *******************************************************************************/
   public static QRecord insertOne(String table, QRecord record) throws QException
   {
      return (insert(table, List.of(record), null).getRecords().get(0));
   }



   /*******************************************************************************
    ** Update.
    *******************************************************************************/
   public static List<QRecord> update(String table, List<QRecord> records, QBackendTransaction transaction) throws QException
   {
      if(records.isEmpty())
      {
         return (records);
      }
      return (call(() -> new UpdateAction().execute(new UpdateInput(table).withRecords(records).withTransaction(transaction)).getRecords()));
   }



   /*******************************************************************************
    ** Update one record.
    *******************************************************************************/
   public static void updateOne(String table, QRecord record) throws QException
   {
      update(table, List.of(record), null);
   }



   /*******************************************************************************
    ** Query.
    *******************************************************************************/
   public static List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      return (call(() -> new QueryAction().execute(new QueryInput(table).withShouldFetchHeavyFields(true).withShouldOmitHiddenFields(false).withFilter(filter)).getRecords()));
   }



   /*******************************************************************************
    ** Query in a transaction.
    *******************************************************************************/
   public static List<QRecord> query(String table, QQueryFilter filter, QBackendTransaction transaction) throws QException
   {
      return (call(() -> new QueryAction().execute(new QueryInput(table).withShouldFetchHeavyFields(true).withShouldOmitHiddenFields(false).withFilter(filter).withTransaction(transaction)).getRecords()));
   }



   /*******************************************************************************
    ** Count.
    *******************************************************************************/
   public static int count(String table, QQueryFilter filter) throws QException
   {
      return (count(table, filter, null));
   }


   /*******************************************************************************
    ** Count within the claim transaction so rate budgets remain shared.
    *******************************************************************************/
   public static int count(String table, QQueryFilter filter, QBackendTransaction transaction) throws QException
   {
      Integer count = call(() -> new CountAction().execute(new CountInput(table).withFilter(filter).withTransaction(transaction)).getCount());
      return (count == null ? 0 : count);
   }



   /*******************************************************************************
    ** Delete by filter, returning the number deleted.
    *******************************************************************************/
   public static int delete(String table, QQueryFilter filter) throws QException
   {
      return (delete(table, filter, null));
   }


   /*******************************************************************************
    ** Delete within a transaction, including retention's parent and child rows.
    *******************************************************************************/
   public static int delete(String table, QQueryFilter filter, QBackendTransaction transaction) throws QException
   {
      Integer deleted = call(() -> new DeleteAction().execute(new DeleteInput(table).withQueryFilter(filter).withTransaction(transaction)).getDeletedRecordCount());
      return (deleted == null ? 0 : deleted);
   }
}
