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
import com.kingsrook.qbits.email.api.EmailApi;
import com.kingsrook.qbits.email.api.EmailApiCategory;
import com.kingsrook.qbits.email.api.EmailApiRequest;
import com.kingsrook.qbits.email.api.EmailApiTemplate;
import com.kingsrook.qbits.email.app.EmailHome;
import com.kingsrook.qbits.email.app.EmailSetupStatus;
import com.kingsrook.qbits.email.app.EmailSetupStatus.Item;
import com.kingsrook.qbits.email.app.EmailSetupStatus.State;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.template.EmailBuiltIns;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Setup card follows the real configuration (the same provider and brand
 ** resolution the sender uses), the home creates the built-in template, and the
 ** template's owner qbit now decides who a message names as its sender.
 *******************************************************************************/
class EmailSetupTest
{

   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(config -> config.withSecretResolver(name -> null));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private Item item(String label) throws Exception
   {
      return (EmailSetupStatus.load().stream().filter(i -> i.label().equals(label)).findFirst().orElseThrow());
   }



   /*******************************************************************************
    ** Nothing configured: the provider is the one thing that must be added.
    *******************************************************************************/
   @Test
   void freshInstallNeedsAProvider() throws Exception
   {
      assertThat(item("Provider").state()).isEqualTo(State.TODO);
      assertThat(item("Brands").state()).isEqualTo(State.DEFAULT);
      assertThat(item("Sender addresses").state()).isEqualTo(State.OPTIONAL);
      assertThat(item("Marketing email").state()).isEqualTo(State.OPTIONAL);
      assertThat(item("Scheduler").state()).isEqualTo(State.TODO);
      assertThat(EmailSetupStatus.load()).allMatch(i -> i.tableName() == null || i.tableName().startsWith("email_"));
   }



   /*******************************************************************************
    ** Configuring a part moves its line, using the sender's own resolution.
    *******************************************************************************/
   @Test
   void configuringMovesTheState() throws Exception
   {
      H2Support.provider(25);
      assertThat(item("Provider").state()).isEqualTo(State.OK);
      assertThat(item("Delivery tracking").state()).isEqualTo(State.OPTIONAL);
      assertThat(EmailApi.readiness(QContext.getQInstance(), EmailApiCategory.TRANSACTIONAL).ready()).isTrue();

      H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "acme").withValue("label", "Acme").withValue("isDefault", true));
      assertThat(item("Brands").state()).isEqualTo(State.OK);
   }



   /*******************************************************************************
    ** The home shows the card to admins and creates the built-in template.
    *******************************************************************************/
   @Test
   void homeShowsTheCardAndCreatesTheBuiltInTemplate() throws Exception
   {
      assertThat(H2Support.all(EmailTables.TEMPLATE)).isEmpty();
      String html = EmailHome.overview().getHtml();
      assertThat(html).contains("Setup").contains("Needs setup").contains("href=\"/app/email_provider\"").contains("Next step");
      assertThat(H2Support.all(EmailTables.TEMPLATE)).singleElement().satisfies(row ->
      {
         assertThat(row.getValueString("name")).isEqualTo("email_secure_message");
         assertThat(row.getValueString("ownerQbit")).isEqualTo(EmailBuiltIns.OWNER);
      });
   }



   /*******************************************************************************
    ** The registering qbit becomes the message's sender unless the request names one.
    *******************************************************************************/
   @Test
   void theTemplatesOwnerQbitIsTheDefaultSender() throws Exception
   {
      H2Support.provider(25);
      EmailApi.ensureTemplate(new EmailApiTemplate().withName("notice").withLabel("Notice").withCategory(EmailApiCategory.TRANSACTIONAL)
         .withSubject("Ready").withBody("Hello").withOwnerQbit("orders"));
      var plain = EmailApi.send(new EmailApiRequest().withTo("pat@example.com").withTemplateName("notice").withIdempotencyKey("owner-1"));
      var named = EmailApi.send(new EmailApiRequest().withTo("pat@example.com").withTemplateName("notice").withIdempotencyKey("owner-2").withSender("billing", "invoices"));
      List<QRecord> rows = H2Support.all(EmailTables.MESSAGE);
      assertThat(rows).filteredOn(r -> r.getValueInteger("id").equals(plain.messageId())).singleElement().satisfies(r -> assertThat(r.getValueString("senderQbit")).isEqualTo("orders"));
      assertThat(rows).filteredOn(r -> r.getValueInteger("id").equals(named.messageId())).singleElement().satisfies(r -> assertThat(r.getValueString("senderQbit")).isEqualTo("billing"));
   }
}
