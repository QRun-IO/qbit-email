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


import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.render.ContrastCheck;
import com.kingsrook.qbits.email.render.EmailBrands;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The shipped starter defaults (db/liquibase/qbit-email/email-defaults.
 ** changelog.sql), run through real Liquibase on H2 (PostgreSQL mode) against
 ** the qbit's test schema: the brand appears once, a second update runs nothing,
 ** an edit survives, a host with its own brand or without the table is left
 ** alone, and the seed never changes how mail is rendered.
 *******************************************************************************/
class EmailDefaultsChangelogTest
{
   static final String CHANGELOG = "db/liquibase/qbit-email/email-defaults.changelog.sql";

   /** the tables the changelog seeds. */
   static final List<String> SEEDED_TABLES = List.of("email_brand");



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Fresh schema, update twice: one brand; an edit survives.
    *******************************************************************************/
   @Test
   void seedsAFreshSchemaInsertOnly() throws Exception
   {
      try(Connection connection = freshDatabase(true))
      {
         update(connection);
         assertThat(scalar(connection, "SELECT COUNT(*) FROM email_brand")).isEqualTo("1");
         assertThat(scalar(connection, "SELECT name FROM email_brand")).isEqualTo("neutral");
         assertThat(scalar(connection, "SELECT is_default FROM email_brand")).isEqualTo("FALSE");

         try(Statement statement = connection.createStatement())
         {
            statement.execute("UPDATE email_brand SET label = 'Ours', is_default = TRUE");
         }
         connection.commit();
         update(connection);
         assertThat(scalar(connection, "SELECT COUNT(*) FROM email_brand")).isEqualTo("1");
         assertThat(scalar(connection, "SELECT label FROM email_brand")).isEqualTo("Ours");
         assertThat(scalar(connection, "SELECT COUNT(*) FROM databasechangelog WHERE exectype = 'EXECUTED'")).isEqualTo("1");
      }
   }



   /*******************************************************************************
    ** A host that already configured a brand (of any name) gets nothing added,
    ** so it can never end up with two default brands.
    *******************************************************************************/
   @Test
   void leavesAHostWithItsOwnBrandAlone() throws Exception
   {
      try(Connection connection = freshDatabase(true))
      {
         try(Statement statement = connection.createStatement())
         {
            statement.execute("INSERT INTO email_brand (name, label, is_default) VALUES ('acme', 'Acme', TRUE)");
         }
         connection.commit();
         update(connection);
         assertThat(scalar(connection, "SELECT COUNT(*) FROM email_brand")).isEqualTo("1");
         assertThat(scalar(connection, "SELECT name FROM email_brand")).isEqualTo("acme");
         assertThat(scalar(connection, "SELECT COUNT(*) FROM databasechangelog WHERE exectype = 'MARK_RAN'")).isEqualTo("1");
      }
   }



   /*******************************************************************************
    ** Rolling the seed back (Liquibase also rolls back changesets it only marked
    ** as ran) never deletes a brand the host already had.
    *******************************************************************************/
   @Test
   void rollbackKeepsHostOwnedRows() throws Exception
   {
      try(Connection connection = freshDatabase(true))
      {
         try(Statement statement = connection.createStatement())
         {
            statement.execute("INSERT INTO email_brand (name, label, is_default) VALUES ('neutral', 'Mine', TRUE)");
         }
         connection.commit();
         update(connection);
         Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
         new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database).rollback(1, "");
         connection.commit();
         assertThat(scalar(connection, "SELECT label FROM email_brand WHERE name = 'neutral'")).isEqualTo("Mine");
      }
   }



   /*******************************************************************************
    ** A host that has not created the table yet skips the changeset (marked
    ** ran), rather than failing its deploy.
    *******************************************************************************/
   @Test
   void aMissingTableMarksTheChangesetRan() throws Exception
   {
      try(Connection connection = freshDatabase(false))
      {
         update(connection);
         assertThat(scalar(connection, "SELECT COUNT(*) FROM databasechangelog WHERE exectype = 'MARK_RAN'")).isEqualTo("1");
      }
   }



   /*******************************************************************************
    ** Guarded, and only precondition forms that parse in Liquibase 4 and 5.
    *******************************************************************************/
   @Test
   void everyChangesetIsGuarded() throws Exception
   {
      String text = changelogText();
      assertThat(text).doesNotContain("precondition-table-exists");
      assertThat(text.split("(?m)^-- changeset ")).hasSize(2);
      assertThat(text).contains("-- preconditions onFail:MARK_RAN onError:MARK_RAN").contains("precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM email_brand")
         .contains("-- rollback ").contains("WHERE NOT EXISTS (SELECT 1 FROM email_brand)");
      for(String table : SEEDED_TABLES)
      {
         assertThat(text).contains("INSERT INTO " + table + " ");
      }
   }



   /*******************************************************************************
    ** The seeded brand passes the qbit's own contrast checks, and being a
    ** non-default brand it changes nothing about how mail renders until chosen.
    *******************************************************************************/
   @Test
   void theSeededBrandIsSafeAndChangesNothing() throws Exception
   {
      try(Connection connection = freshDatabase(true))
      {
         update(connection);
         QRecord brand = new QRecord();
         try(Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM email_brand"))
         {
            rs.next();
            for(String column : List.of("primary_color", "text_color", "background_color", "button_text_color", "dark_primary_color", "dark_text_color", "dark_background_color", "dark_button_text_color"))
            {
               brand.setValue(camel(column), rs.getString(column));
            }
         }
         ContrastCheck.validate(brand);
         EmailBrands.validate(brand);
      }

      H2Support.setup(config -> config.withSecretResolver(name -> null));
      H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "neutral").withValue("label", "Neutral").withValue("primaryColor", "#2459a6")
         .withValue("isDefault", false));
      QRecord resolved = EmailBrands.resolve(null, null);
      assertThat(resolved.getValue("id")).as("the seeded brand is not picked up unless selected").isNull();
      assertThat(resolved.getValueString("label")).isEqualTo("Email");
   }



   /*******************************************************************************
    ** snake_case to camelCase.
    *******************************************************************************/
   private static String camel(String column)
   {
      StringBuilder camel = new StringBuilder();
      boolean       up    = false;
      for(char c : column.toCharArray())
      {
         if(c == '_')
         {
            up = true;
         }
         else
         {
            camel.append(up ? Character.toUpperCase(c) : c);
            up = false;
         }
      }
      return (camel.toString());
   }



   /*******************************************************************************
    ** A fresh in-memory H2 (PostgreSQL mode), with or without the qbit's tables.
    *******************************************************************************/
   static Connection freshDatabase(boolean withTables) throws Exception
   {
      Connection connection = DriverManager.getConnection("jdbc:h2:mem:defaults" + System.nanoTime() + ";MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH");
      if(withTables)
      {
         String script;
         try(InputStream in = EmailDefaultsChangelogTest.class.getResourceAsStream("/email-h2.sql"))
         {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
         }
         try(Statement statement = connection.createStatement())
         {
            for(String sql : script.split(";"))
            {
               if(!sql.isBlank())
               {
                  statement.execute(sql);
               }
            }
         }
      }
      return (connection);
   }



   /*******************************************************************************
    ** Run the changelog with real Liquibase.
    *******************************************************************************/
   static void update(Connection connection) throws Exception
   {
      Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      //////////////////////////////////////////////////////////////////
      // not closed: closing Liquibase would close the test connection //
      //////////////////////////////////////////////////////////////////
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database).update("");
      connection.commit();
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String changelogText() throws Exception
   {
      try(InputStream stream = EmailDefaultsChangelogTest.class.getClassLoader().getResourceAsStream(CHANGELOG))
      {
         assertThat(stream).as(CHANGELOG + " is on the classpath").isNotNull();
         return (new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String scalar(Connection connection, String sql) throws Exception
   {
      try(Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql))
      {
         rs.next();
         return (rs.getString(1));
      }
   }

}
