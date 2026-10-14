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

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;

/*******************************************************************************
 ** H2Support.
 *******************************************************************************/
public final class H2Support
{
   /*******************************************************************************
    ** Backend.
    *******************************************************************************/
   public static RDBMSBackendMetaData backend()
   {
      var backend =
            new RDBMSBackendMetaData()
                  .withName("rdbms")
                  .withVendor("h2")
                  .withHostName("mem")
                  .withDatabaseName("email_" + UUID.randomUUID())
                  .withUsername("sa");
      backend.setJdbcUrl(
            ConnectionManager.getJdbcUrl(backend)
                  + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
      return (backend);
   }

   /*******************************************************************************
    ** Prime.
    *******************************************************************************/
   public static void prime(RDBMSBackendMetaData backend) throws Exception
   {
      try(Connection connection = new ConnectionManager().getConnection(backend))
      {
         for(String sql : new String(H2Support.class.getResourceAsStream("/email-h2.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";"))
         {
            if(!sql.isBlank())
            {
               connection.createStatement().execute(sql);
            }
         }
      }
   }

   /*******************************************************************************
    ** Instance.
    *******************************************************************************/
   public static QInstance instance(RDBMSBackendMetaData backend, Consumer<EmailQBitConfig> tweak)
         throws Exception
   {
      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(
            new QAuthenticationMetaData()
                  .withName("anonymous")
                  .withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(backend);
      EmailQBitConfig config = new EmailQBitConfig().withBackendName("rdbms");
      tweak.accept(config);
      new EmailQBitProducer().withQBitConfig(config).produce(instance);
      new QInstanceValidator().validate(instance);
      return (instance);
   }

   /*******************************************************************************
    ** Setup.
    *******************************************************************************/
   public static QInstance setup(Consumer<EmailQBitConfig> tweak) throws Exception
   {
      ConnectionManager.resetConnectionProviders();
      var backend = backend();
      prime(backend);
      var instance = instance(backend, tweak);
      QContext.init(instance, admin());
      return (instance);
   }

   /*******************************************************************************
    ** Admin.
    *******************************************************************************/
   public static QSession admin()
   {
      return (new QSession().withPermissions(EmailPermissions.ALL));
   }

   /*******************************************************************************
    ** Provider.
    *******************************************************************************/
   public static QRecord provider(int port) throws Exception
   {
      return (insert(
            EmailTables.PROVIDER,
            new QRecord()
                  .withValue("name", "relay")
                  .withValue("label", "Relay")
                  .withValue("kind", "SMTP")
                  .withValue("host", "127.0.0.1")
                  .withValue("port", port)
                  .withValue("security", "NONE")
                  .withValue("fromAddress", "sender@example.com")
                  .withValue("enabled", true)
                  .withValue("isDefault", true)));
   }

   /*******************************************************************************
    ** Insert.
    *******************************************************************************/
   public static QRecord insert(String table, QRecord record) throws Exception
   {
      QRecord result =
            new InsertAction()
                  .execute(new InsertInput(table).withRecords(List.of(record)))
                  .getRecords()
                  .get(0);
      if(result.getErrors() != null && !result.getErrors().isEmpty())
      {
         throw new IllegalStateException("Insert failed: " + result.getErrors());
      }
      return (result);
   }

   /*******************************************************************************
    ** All.
    *******************************************************************************/
   public static List<QRecord> all(String table) throws Exception
   {
      return (SystemWrites.query(table, new QQueryFilter()));
   }
}
