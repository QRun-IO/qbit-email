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

package com.kingsrook.qbits.email.app;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.api.EmailApi;
import com.kingsrook.qbits.email.api.EmailApiCategory;
import com.kingsrook.qbits.email.api.EmailApiReadiness;
import com.kingsrook.qbits.email.api.EmailApiReadinessCheck;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The Setup card on the Email home: each configuration table, whether it is
 ** set up, and the next step. It uses the same resolution the sender uses (the
 ** default provider, the brand lookup, EmailApi.readiness), so it never reports
 ** a problem the sender would not have.
 *******************************************************************************/
public final class EmailSetupStatus
{
   /*******************************************************************************
    ** OK: working. TODO: needs attention. DEFAULT: working on built-in defaults.
    ** OPTIONAL: a feature that is off until set up.
    *******************************************************************************/
   public enum State
   {
      OK, TODO, DEFAULT, OPTIONAL
   }



   /*******************************************************************************
    ** One line of the card.
    *******************************************************************************/
   public record Item(String label, String tableName, State state, String detail, String nextStep)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailSetupStatus()
   {
   }



   /*******************************************************************************
    ** Build the items (as the qbit, for an email admin).
    *******************************************************************************/
   public static List<Item> load() throws QException
   {
      EmailQBitConfig config = EmailRuntime.config();
      List<Item>      items  = new ArrayList<>();

      ///////////////////////////////////////////////////////////
      // the provider: the same check a send makes (readiness) //
      ///////////////////////////////////////////////////////////
      EmailApiReadiness readiness = EmailApi.readiness(QContext.getQInstance(), EmailApiCategory.TRANSACTIONAL);
      List<QRecord>     providers = SystemWrites.query(EmailTables.PROVIDER, new QQueryFilter());
      QRecord           active    = providers.stream().filter(p -> Boolean.TRUE.equals(p.getValueBoolean("enabled")) && Boolean.TRUE.equals(p.getValueBoolean("isDefault")))
         .findFirst().orElse(null);
      if(readiness.failed(EmailApiReadinessCheck.PROVIDER_MISSING))
      {
         items.add(new Item("Provider", EmailTables.PROVIDER, State.TODO, providers.isEmpty() ? "No provider yet, so nothing can be sent." : "No enabled default provider, so nothing can be sent.",
            "Add one with New (kind, host or region, from address, and the NAMES of the secret variables), turn on Default, then Send Test Email."));
      }
      else if(readiness.failed(EmailApiReadinessCheck.PROVIDER_INVALID))
      {
         items.add(new Item("Provider", EmailTables.PROVIDER, State.TODO, "The default provider's configuration is invalid.", "Open it and fix the highlighted settings, then Send Test Email."));
      }
      else if(active != null && "UNHEALTHY".equals(active.getValueString("healthStatus")))
      {
         items.add(new Item("Provider", EmailTables.PROVIDER, State.TODO, "The default provider is failing. See System Health for its status.",
            "Check its settings and secret variables, then Send Test Email."));
      }
      else
      {
         items.add(new Item("Provider", EmailTables.PROVIDER, State.OK, valueOr(active == null ? null : active.getValueString("label"), "The default provider") + " sends mail.", null));
      }

      ///////////////////////////////////////////////////////////////
      // sender addresses: the provider's own from address works //
      ///////////////////////////////////////////////////////////////
      int senders = SystemWrites.count(EmailTables.SENDER, new QQueryFilter());
      items.add(senders == 0
         ? new Item("Sender addresses", EmailTables.SENDER, State.OPTIONAL, "None: the provider's own from address is used.", "Add some if one provider sends as several mailboxes.")
         : new Item("Sender addresses", EmailTables.SENDER, State.OK, senders + (senders == 1 ? " address." : " addresses."), null));

      //////////////////////////////////////////////////////////////////
      // brand: the lookup the renderer does (app brand, then default) //
      //////////////////////////////////////////////////////////////////
      List<QRecord> brands = SystemWrites.query(EmailTables.BRAND, EmailStore.equal("appName", config.getAppName()));
      if(brands.isEmpty())
      {
         brands = SystemWrites.query(EmailTables.BRAND, EmailStore.equal("isDefault", true));
      }
      if(brands.size() > 1)
      {
         items.add(new Item("Brands", EmailTables.BRAND, State.TODO, "More than one default brand matches, so branded sends are refused.", "Leave Default Brand on only one."));
      }
      else if(brands.isEmpty())
      {
         items.add(new Item("Brands", EmailTables.BRAND, State.DEFAULT, "No default brand: emails use the plain neutral look.", "Copy the neutral brand, set your logo, colors and footer, and tick Default Brand."));
      }
      else
      {
         items.add(new Item("Brands", EmailTables.BRAND, State.OK, valueOr(brands.getFirst().getValueString("label"), "The default brand") + " is the default.", null));
      }

      ///////////////
      // templates //
      ///////////////
      int templates = SystemWrites.count(EmailTables.TEMPLATE, new QQueryFilter());
      items.add(templates == 0
         ? new Item("Templates", EmailTables.TEMPLATE, State.DEFAULT, "No template yet: plain messages can still be sent.", "Other qbits register theirs; add your own with New, then Activate Version.")
         : new Item("Templates", EmailTables.TEMPLATE, State.OK, templates + (templates == 1 ? " template." : " templates."), null));

      /////////////////////////////////////////////////////
      // marketing: the same checks as a MARKETING send //
      /////////////////////////////////////////////////////
      EmailApiReadiness marketing = EmailApi.readiness(QContext.getQInstance(), EmailApiCategory.MARKETING);
      List<String>      missing   = new ArrayList<>();
      if(marketing.failed(EmailApiReadinessCheck.PUBLIC_BASE_URL))
      {
         missing.add("a public base URL");
      }
      if(marketing.failed(EmailApiReadinessCheck.UNSUBSCRIBE_KEY))
      {
         missing.add("the QQQ_EMAIL_UNSUBSCRIBE_KEY variable");
      }
      if(marketing.failed(EmailApiReadinessCheck.BRAND_POSTAL_ADDRESS))
      {
         missing.add("a default brand with a postal address");
      }
      items.add(missing.isEmpty()
         ? new Item("Marketing email", EmailTables.BRAND, State.OK, "Ready: unsubscribe links, base URL and postal address are set.", null)
         : new Item("Marketing email", EmailTables.BRAND, State.OPTIONAL, "Off until set up: needs " + String.join(", ", missing) + ".", "Only needed if you send marketing email."));

      //////////////////////////////////////////////////////////
      // delivery tracking: the provider's webhook secret name //
      //////////////////////////////////////////////////////////
      boolean webhook = active != null && valueOr(active.getValueString("webhookSecretEnvName"), "").length() > 0;
      int     inbox   = SystemWrites.count(EmailTables.INBOX, new QQueryFilter());
      items.add(webhook
         ? new Item("Delivery tracking", EmailTables.INBOX, State.OK, inbox == 0 ? "A webhook secret is set; no callback has arrived yet." : inbox + " provider callbacks received.", null)
         : new Item("Delivery tracking", EmailTables.INBOX, State.OPTIONAL, "Off: the default provider has no webhook secret variable, so bounces and complaints are not tracked.",
            "Set the provider's webhook secret variable and point the provider at " + config.getWebhookBasePath() + "."));

      int suppressed = SystemWrites.count(EmailTables.SUPPRESSION, new QQueryFilter());
      items.add(new Item("Suppression list", EmailTables.SUPPRESSION, State.OPTIONAL, suppressed == 0 ? "No blocked addresses yet; bounces, complaints and unsubscribes add them." : suppressed + " blocked addresses.", null));

      items.add(config.getSchedulerName() == null
         ? new Item("Scheduler", null, State.TODO, "No scheduler is configured, so the outbox is only sent when someone runs Dispatch by hand.", "Set schedulerName in the Email config.")
         : new Item("Scheduler", null, State.OK, "Dispatch, webhooks, maintenance and retention run on the " + config.getSchedulerName() + " scheduler.", null));
      return (items);
   }



   /*******************************************************************************
    ** The value, or the fallback when blank.
    *******************************************************************************/
   private static String valueOr(String value, String fallback)
   {
      return (value == null || value.isBlank() ? fallback : value);
   }

}
