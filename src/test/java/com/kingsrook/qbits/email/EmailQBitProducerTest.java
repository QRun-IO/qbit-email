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

import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Scaffold checks for the producer and config.
 *******************************************************************************/
class EmailQBitProducerTest
{
   /*******************************************************************************
    ** A valid config registers the qbit.
    *******************************************************************************/
   @Test
   void producesWithValidConfig() throws Exception
   {
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName("memory"));
      EmailQBitProducer producer =
            new EmailQBitProducer()
                  .withNamespace(null)
                  .withQBitConfig(new EmailQBitConfig().withBackendName("memory"));
      assertThat(producer.isEnabled()).isTrue();
      assertThat(producer.getNamespace()).isNull();
      assertThat(producer.getQBitConfig().getBackendName()).isEqualTo("memory");
      assertThat(producer.produce(instance)).isNotNull();
      assertThat(instance.getQBits()).isNotEmpty();
      assertThat(instance.getTable("helpContent")).isNull();
      assertThat(HelpBundles.of(instance).getBundles()).singleElement().satisfies(bundle ->
      {
         assertThat(bundle.getId()).isEqualTo("qbit-email");
         assertThat(bundle.getVersion()).isEqualTo("0.1.0-SNAPSHOT");
      });
   }

   /*******************************************************************************
    ** The app help follows the configured app name even without a help table.
    *******************************************************************************/
   @Test
   void registersHelpForCustomAppName() throws Exception
   {
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName("memory"));
      new EmailQBitProducer().withQBitConfig(new EmailQBitConfig().withBackendName("memory").withAppName("mail"))
         .produce(instance);
      assertThat(HelpBundles.of(instance).resolveWinningEntries())
         .containsKey(new HelpBundles.EntryKey("app:mail;slot:help", "ALL_SCREENS"))
         .doesNotContainKey(new HelpBundles.EntryKey("app:email;slot:help", "ALL_SCREENS"));
   }

   /*******************************************************************************
    ** A missing or unknown backend is refused; no config means disabled.
    *******************************************************************************/
   @Test
   void refusesInvalidConfig()
   {
      assertThat(new EmailQBitProducer().isEnabled()).isFalse();
      assertThatThrownBy(
                  () ->
                        new EmailQBitProducer()
                              .withQBitConfig(new EmailQBitConfig())
                              .produce(new QInstance()))
            .isInstanceOf(QException.class)
            .hasMessageContaining("backendName is required");
      assertThatThrownBy(
                  () ->
                        new EmailQBitProducer()
                              .withQBitConfig(new EmailQBitConfig().withBackendName("nope"))
                              .produce(new QInstance()))
            .isInstanceOf(QException.class)
            .hasMessageContaining("backend not found");
   }
}
