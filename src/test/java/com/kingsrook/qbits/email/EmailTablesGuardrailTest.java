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


import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.kingsrook.qbits.email.metadata.EmailHelp;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.QHelpContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Guardrail (owner requirement 2026-10-14: "all tables should be populated
 ** and the qbit should be fully using them"). For every email_* table:
 ** - runtime code (not just metadata, the app or the home) reads or writes it;
 ** - it is either seeded by the shipped defaults changelog, or listed here with
 **   why it starts empty;
 ** - it has bundle help that says what it is for and how its rows get created.
 ** A new table fails this test until it is wired, seeded or explained.
 *******************************************************************************/
class EmailTablesGuardrailTest
{
   private static final Path MAIN = Path.of("src", "main", "java", "com", "kingsrook", "qbits", "email");

   /*******************************************************************************
    ** Tables that legitimately start empty, and where their rows come from.
    *******************************************************************************/
   static final Map<String, String> STARTS_EMPTY = Map.ofEntries(
      Map.entry("email_provider", "host connection config: a server and the NAMES of its secret variables (kof22 seeds its provider)"),
      Map.entry("email_provider_sender_address", "optional: a provider's own from address is used without rows"),
      Map.entry("email_message", "runtime: every queued email"),
      Map.entry("email_message_attempt", "runtime: every delivery attempt"),
      Map.entry("email_template", "created at runtime: the secure-message built-in on first use (EmailBuiltIns), others by the qbits that register them (EmailApi.ensureTemplate)"),
      Map.entry("email_template_version", "created with a template, and by New Version"),
      Map.entry("email_suppression", "runtime: bounces, complaints, unsubscribes and manual blocks"),
      Map.entry("email_webhook_inbox", "runtime: verified provider callbacks"),
      Map.entry("email_event", "runtime: delivery tracking events"));



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Every table is used by runtime code.
    *******************************************************************************/
   @Test
   void everyTableIsUsedAtRuntime() throws Exception
   {
      List<String> runtimeSource;
      try(Stream<Path> files = Files.walk(MAIN))
      {
         runtimeSource = files.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.startsWith(MAIN.resolve("metadata")) && !p.startsWith(MAIN.resolve("app")))
            .map(EmailTablesGuardrailTest::read).toList();
      }
      for(String table : EmailTables.ALL)
      {
         String constant = constantFor(table);
         assertThat(runtimeSource.stream().anyMatch(source -> source.contains("EmailTables." + constant)))
            .as(table + " (EmailTables." + constant + ") must be read or written by runtime code, not only declared").isTrue();
      }
   }



   /*******************************************************************************
    ** Every table is seeded or explained.
    *******************************************************************************/
   @Test
   void everyTableIsSeededOrExplained() throws Exception
   {
      QInstance instance  = H2Support.setup(config -> config.withSecretResolver(name -> null));
      String    changelog = EmailDefaultsChangelogTest.changelogText();
      for(String table : EmailTables.ALL)
      {
         boolean seeded = EmailDefaultsChangelogTest.SEEDED_TABLES.contains(table);
         assertThat(seeded || STARTS_EMPTY.containsKey(table)).as(table + " must be seeded by the defaults changelog or listed in STARTS_EMPTY").isTrue();
         assertThat(seeded && STARTS_EMPTY.containsKey(table)).as(table + " cannot be both seeded and listed as starting empty").isFalse();
         assertThat(changelog.contains("INSERT INTO " + table + " ")).as(table + " seeded iff the changelog inserts it").isEqualTo(seeded);
      }
      assertThat(STARTS_EMPTY.keySet()).allMatch(table -> instance.getTable(table) != null, "STARTS_EMPTY names only real tables");
   }



   /*******************************************************************************
    ** Every table and the app have bundle help that says how rows get created.
    *******************************************************************************/
   @Test
   void everyTableHasEmptyStateHelp() throws Exception
   {
      QInstance instance = H2Support.setup(config -> config.withSecretResolver(name -> null));
      assertThat(EmailHelp.entries()).allSatisfy((key, entry) -> assertThat(entry.path("summary").asText().length()).as(key).isLessThanOrEqualTo(120));
      for(String table : EmailTables.ALL)
      {
         List<QHelpContent> help = instance.getTable(table).getHelpContent() == null ? null : instance.getTable(table).getHelpContent().get(EmailHelp.SLOT);
         assertThat(help).as(table + " table help").isNotEmpty();
         assertThat(help.get(0).getContent()).as(table + " help says how rows get created").contains("How rows get created");
      }
      assertThat(instance.getApp("email").getHelpContent()).containsKey(EmailHelp.SLOT);
      assertThat(EmailHelp.entries().keySet()).allMatch(key -> !key.startsWith("table:") || instance.getTable(key.substring(6, key.indexOf(';'))) != null,
         "help names only real tables");
   }



   /*******************************************************************************
    ** The EmailTables constant name for a table name.
    *******************************************************************************/
   private static String constantFor(String table) throws Exception
   {
      for(var field : EmailTables.class.getDeclaredFields())
      {
         if(field.getType() == String.class && Modifier.isStatic(field.getModifiers()) && table.equals(field.get(null)))
         {
            return (field.getName());
         }
      }
      throw (new IllegalStateException("No constant for " + table));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String read(Path path)
   {
      try
      {
         return (Files.readString(path, StandardCharsets.UTF_8));
      }
      catch(Exception e)
      {
         throw (new IllegalStateException(e));
      }
   }

}
