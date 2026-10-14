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


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Referential integrity guardrail: docs/ddl/email-postgres.sql, applied to
 ** a real PostgreSQL, has a foreign key for every join and every table
 ** possible-value field between the qbit's tables (or the relation is in
 ** {@link #EXEMPT} with a reason), each with the intended ON DELETE. A new
 ** table or join without its key fails here. The upgrade script is checked
 ** too: it quarantines orphans, adds the same keys, and is idempotent.
 *******************************************************************************/
class ForeignKeyGuardrailTest
{
   private static final Path DDL     = Path.of("docs/ddl/email-postgres.sql");
   private static final Path UPGRADE = Path.of("docs/ddl/email-foreign-keys-upgrade.sql");

   /////////////////////////////////////////////////////////////////////////////
   // child_table.child_column -> parent_table : ON DELETE (pg confdeltype)    //
   // c = CASCADE, n = SET NULL, r = RESTRICT                                  //
   /////////////////////////////////////////////////////////////////////////////
   private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
      Map.entry("email_provider_sender_address.provider_id -> email_provider", "c"),
      Map.entry("email_message.provider_id -> email_provider", "r"),
      Map.entry("email_message.from_address_id -> email_provider_sender_address", "n"),
      Map.entry("email_message.template_id -> email_template", "n"),
      Map.entry("email_message.template_version_id -> email_template_version", "n"),
      Map.entry("email_message.brand_id -> email_brand", "n"),
      Map.entry("email_message_attempt.message_id -> email_message", "c"),
      Map.entry("email_message_attempt.provider_id -> email_provider", "r"),
      Map.entry("email_template.brand_id -> email_brand", "r"),
      Map.entry("email_template.active_version_id -> email_template_version", "n"),
      Map.entry("email_template.approved_version_id -> email_template_version", "n"),
      Map.entry("email_template_version.template_id -> email_template", "r"),
      Map.entry("email_suppression.source_message_id -> email_message", "n"),
      Map.entry("email_suppression.source_event_id -> email_event", "n"),
      Map.entry("email_webhook_inbox.provider_id -> email_provider", "r"),
      Map.entry("email_event.message_id -> email_message", "c"),
      Map.entry("email_event.provider_id -> email_provider", "r")));

   /////////////////////////////////////////////////////////////////////////////
   // keys for relations that exist only in code (no join or PVS in metadata) //
   /////////////////////////////////////////////////////////////////////////////
   private static final List<String> CODE_ONLY = List.of(
      "email_suppression.source_message_id -> email_message");

   /////////////////////////////////////////////////////////////////////////////
   // relations that are deliberately not foreign keys, with the reason       //
   /////////////////////////////////////////////////////////////////////////////
   private static final Map<String, String> EXEMPT = Map.of();

   private static PostgreSQLContainer postgres;



   /*******************************************************************************
    ** Start PostgreSQL once (a missing Docker fails in CI, skips locally).
    *******************************************************************************/
   @BeforeAll
   static void startPostgres()
   {
      boolean docker;
      try
      {
         docker = DockerClientFactory.instance().isDockerAvailable();
      }
      catch(RuntimeException e)
      {
         docker = false;
      }
      if(!docker && System.getenv("CI") != null)
      {
         Assertions.fail("Docker is required for the foreign key guardrail in CI");
      }
      Assumptions.assumeTrue(docker, "Docker is not available");
      postgres = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("email").withUsername("qqq").withPassword("qqq");
      postgres.start();
   }



   /*******************************************************************************
    ** Stop PostgreSQL.
    *******************************************************************************/
   @AfterAll
   static void stopPostgres()
   {
      if(postgres != null)
      {
         postgres.stop();
      }
   }



   /*******************************************************************************
    ** A clean schema from the reference DDL.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      try(Connection c = connect(); Statement st = c.createStatement())
      {
         st.execute("DROP SCHEMA public CASCADE");
         st.execute("CREATE SCHEMA public");
         st.execute(Files.readString(DDL, StandardCharsets.UTF_8));
      }
   }



   /*******************************************************************************
    ** Clear the QContext the metadata setup opened.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Every declared relation is a foreign key (or exempt), with the intended
    ** ON DELETE, and every foreign key column leads an index.
    *******************************************************************************/
   @Test
   void everyRelationHasAForeignKey() throws Exception
   {
      TreeSet<String> declared = declaredRelations(H2Support.setup(config ->
      {
      }));
      Map<String, String> keys = foreignKeys();

      List<String> missing = new ArrayList<>();
      for(String relation : declared)
      {
         if(!keys.containsKey(relation) && !EXEMPT.containsKey(relation))
         {
            missing.add(relation);
         }
      }
      assertThat(missing).as("relations declared in metadata without a foreign key in " + DDL + " (add one, or exempt it with a reason)").isEmpty();
      assertThat(declared).as("every expected relation is still declared").containsAll(EXPECTED.keySet().stream().filter(k -> !CODE_ONLY.contains(k)).toList());
      assertThat(keys).as("foreign keys and their ON DELETE").isEqualTo(EXPECTED);
      assertThat(unindexedForeignKeys()).as("foreign key columns that lead no index").isEmpty();
   }



   /*******************************************************************************
    ** The upgrade script adds the same keys to a schema without them, recording
    ** and removing orphans first, and is a no-op the second time.
    *******************************************************************************/
   @Test
   void upgradeQuarantinesOrphansAndIsIdempotent() throws Exception
   {
      try(Connection c = connect(); Statement st = c.createStatement())
      {
         dropForeignKeys(st);
         st.execute("""
            INSERT INTO email_provider (id, name) VALUES (1, 'relay');
            INSERT INTO email_provider_sender_address (id, provider_id, address) VALUES (1, 1, 'a@example.com'), (2, 99, 'b@example.com');
            INSERT INTO email_message (id, provider_id, from_address_id, template_id, brand_id) VALUES (1, 1, 1, NULL, NULL), (2, 1, 2, 98, 97);
            INSERT INTO email_message_attempt (id, message_id, attempt_number, provider_id) VALUES (1, 1, 1, 1), (2, 96, 1, 1);
            INSERT INTO email_event (id, message_id, provider_id, event_type, event_key) VALUES (1, 1, 1, 'DELIVERED', 'k1'), (2, 95, 1, 'BOUNCED', 'k2');
            INSERT INTO email_suppression (id, address, scope, source_message_id, source_event_id) VALUES (1, 'x@example.com', 'ALL', 95, 2);
            INSERT INTO email_webhook_inbox (id, provider_id, kind, external_event_id, status) VALUES (1, 94, 'SES', 'e1', 'PENDING');
            """);

         st.execute(Files.readString(UPGRADE, StandardCharsets.UTF_8));
         assertThat(foreignKeys()).isEqualTo(EXPECTED);
         assertThat(quarantine(st)).containsExactlyInAnyOrder(
            "email_provider_sender_address.provider_id row 2 parent 99 DELETED",
            "email_message.from_address_id row 2 parent 2 NULLED",
            "email_message.template_id row 2 parent 98 NULLED",
            "email_message.brand_id row 2 parent 97 NULLED",
            "email_message_attempt.message_id row 2 parent 96 DELETED",
            "email_event.message_id row 2 parent 95 DELETED",
            "email_webhook_inbox.provider_id row 1 parent 94 DELETED",
            "email_suppression.source_message_id row 1 parent 95 NULLED",
            "email_suppression.source_event_id row 1 parent 2 NULLED");
         assertThat(count(st, "SELECT COUNT(*) FROM email_suppression")).isEqualTo(1);

         st.execute(Files.readString(UPGRADE, StandardCharsets.UTF_8));
         assertThat(foreignKeys()).isEqualTo(EXPECTED);
         assertThat(quarantine(st)).hasSize(9);
      }
   }



   /*******************************************************************************
    ** Message retention (events, attempts, then the message) keeps the
    ** suppression; the template/version cycle inserts in the qbit's order; a
    ** provider with history, a brand in use and a template with versions
    ** cannot be deleted.
    *******************************************************************************/
   @Test
   void deletesFollowTheIntendedRules() throws Exception
   {
      try(Connection c = connect(); Statement st = c.createStatement())
      {
         st.execute("""
            INSERT INTO email_provider (id, name) VALUES (1, 'relay'), (2, 'spare');
            INSERT INTO email_provider_sender_address (provider_id, address) VALUES (2, 'b@example.com');
            INSERT INTO email_brand (id, name) VALUES (1, 'default');
            INSERT INTO email_template (id, name, brand_id) VALUES (1, 'welcome', 1);
            INSERT INTO email_template_version (id, template_id, version_number, locale) VALUES (1, 1, 1, 'en'), (2, 1, 2, 'en');
            UPDATE email_template SET active_version_id = 1, approved_version_id = 1 WHERE id = 1;
            INSERT INTO email_message (id, provider_id, template_id, template_version_id, brand_id) VALUES (1, 1, 1, 1, 1);
            INSERT INTO email_message_attempt (message_id, attempt_number, provider_id) VALUES (1, 1, 1);
            INSERT INTO email_event (id, message_id, provider_id, event_type, event_key) VALUES (1, 1, 1, 'BOUNCED', 'k1');
            INSERT INTO email_suppression (address, scope, source_message_id, source_event_id) VALUES ('x@example.com', 'ALL', 1, 1);
            """);

         assertThatThrownBy(() -> st.execute("DELETE FROM email_provider WHERE id = 1")).isInstanceOf(SQLException.class).hasMessageContaining("fk_email_");
         assertThatThrownBy(() -> st.execute("DELETE FROM email_template WHERE id = 1")).isInstanceOf(SQLException.class).hasMessageContaining("fk_email_template_version_template_id");
         assertThatThrownBy(() -> st.execute("DELETE FROM email_brand WHERE id = 1")).isInstanceOf(SQLException.class).hasMessageContaining("fk_email_template_brand_id");

         st.execute("DELETE FROM email_template_version WHERE id = 2");
         st.execute("DELETE FROM email_provider WHERE id = 2");
         assertThat(count(st, "SELECT COUNT(*) FROM email_provider_sender_address")).isZero();

         st.execute("DELETE FROM email_event WHERE message_id = 1");
         st.execute("DELETE FROM email_message_attempt WHERE message_id = 1");
         st.execute("DELETE FROM email_message WHERE id = 1");
         assertThat(count(st, "SELECT COUNT(*) FROM email_suppression WHERE source_message_id IS NULL AND source_event_id IS NULL")).isEqualTo(1);
      }
   }



   /*******************************************************************************
    ** child_table.child_column -> parent_table for every join and table PVS
    ** field between two of the qbit's tables.
    *******************************************************************************/
   static TreeSet<String> declaredRelations(QInstance instance)
   {
      TreeSet<String> relations = new TreeSet<>();
      for(QTableMetaData table : instance.getTables().values())
      {
         if(!isQbitTable(table))
         {
            continue;
         }
         for(QFieldMetaData field : table.getFields().values())
         {
            QPossibleValueSource pvs = field.getPossibleValueSourceName() == null ? null : instance.getPossibleValueSource(field.getPossibleValueSourceName());
            if(pvs != null && pvs.getType() == QPossibleValueSourceType.TABLE && isQbitTable(instance.getTable(pvs.getTableName())))
            {
               relations.add(relation(table, field, instance.getTable(pvs.getTableName())));
            }
         }
      }
      for(QJoinMetaData join : instance.getJoins().values())
      {
         QTableMetaData left  = instance.getTable(join.getLeftTable());
         QTableMetaData right = instance.getTable(join.getRightTable());
         if(!isQbitTable(left) || !isQbitTable(right))
         {
            continue;
         }
         for(JoinOn on : join.getJoinOns())
         {
            if(on.getLeftField().equals(left.getPrimaryKeyField()))
            {
               relations.add(relation(right, right.getField(on.getRightField()), left));
            }
            else if(on.getRightField().equals(right.getPrimaryKeyField()))
            {
               relations.add(relation(left, left.getField(on.getLeftField()), right));
            }
         }
      }
      return (relations);
   }



   /*******************************************************************************
    ** Whether a table is one of the qbit's.
    *******************************************************************************/
   private static boolean isQbitTable(QTableMetaData table)
   {
      return (table != null && table.getName().startsWith("email_"));
   }



   /*******************************************************************************
    ** A relation key.
    *******************************************************************************/
   private static String relation(QTableMetaData child, QFieldMetaData field, QTableMetaData parent)
   {
      String column = field.getBackendName() != null ? field.getBackendName() : field.getName();
      return (child.getName() + "." + column + " -> " + parent.getName());
   }



   /*******************************************************************************
    ** The single-column foreign keys in the database, with their ON DELETE.
    *******************************************************************************/
   private static Map<String, String> foreignKeys() throws SQLException
   {
      Map<String, String> keys = new TreeMap<>();
      try(Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
         SELECT c.conrelid::regclass::text AS child, a.attname AS col, c.confrelid::regclass::text AS parent, c.confdeltype::text AS rule
         FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
         WHERE c.contype = 'f' AND cardinality(c.conkey) = 1
         """))
      {
         while(rs.next())
         {
            keys.put(rs.getString("child") + "." + rs.getString("col") + " -> " + rs.getString("parent"), rs.getString("rule"));
         }
      }
      return (keys);
   }



   /*******************************************************************************
    ** Foreign key columns that are not the first column of any index.
    *******************************************************************************/
   private static List<String> unindexedForeignKeys() throws SQLException
   {
      List<String> list = new ArrayList<>();
      try(Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
         SELECT c.conrelid::regclass::text || '.' || a.attname AS col
         FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
         WHERE c.contype = 'f'
           AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])
         """))
      {
         while(rs.next())
         {
            list.add(rs.getString("col"));
         }
      }
      return (list);
   }



   /*******************************************************************************
    ** Drop every foreign key (a schema as it was before they were declared).
    *******************************************************************************/
   private static void dropForeignKeys(Statement st) throws SQLException
   {
      List<String> drops = new ArrayList<>();
      try(ResultSet rs = st.executeQuery("SELECT conrelid::regclass::text AS t, conname FROM pg_constraint WHERE contype = 'f'"))
      {
         while(rs.next())
         {
            drops.add("ALTER TABLE " + rs.getString("t") + " DROP CONSTRAINT " + rs.getString("conname"));
         }
      }
      for(String drop : drops)
      {
         st.execute(drop);
      }
   }



   /*******************************************************************************
    ** The quarantine rows, as text.
    *******************************************************************************/
   private static List<String> quarantine(Statement st) throws SQLException
   {
      List<String> rows = new ArrayList<>();
      try(ResultSet rs = st.executeQuery("SELECT table_name, column_name, row_id, missing_parent_id, action FROM qqq_fk_quarantine ORDER BY id"))
      {
         while(rs.next())
         {
            rows.add(rs.getString(1) + "." + rs.getString(2) + " row " + rs.getString(3) + " parent " + rs.getString(4) + " " + rs.getString(5));
         }
      }
      return (rows);
   }



   /*******************************************************************************
    ** A count.
    *******************************************************************************/
   private static int count(Statement st, String sql) throws SQLException
   {
      try(ResultSet rs = st.executeQuery(sql))
      {
         rs.next();
         return (rs.getInt(1));
      }
   }



   /*******************************************************************************
    ** A connection.
    *******************************************************************************/
   private static Connection connect() throws SQLException
   {
      return (DriverManager.getConnection(postgres.getJdbcUrl(), "qqq", "qqq"));
   }
}
