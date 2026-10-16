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

package com.kingsrook.qbits.email.health;


import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.kingsrook.qbits.email.api.EmailApi;
import com.kingsrook.qbits.email.api.EmailApiCategory;
import com.kingsrook.qbits.email.api.EmailApiReadinessCheck;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthCheckSupplier;
import com.kingsrook.qqq.backend.core.health.HealthCheckSupplierMetaData;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.health.HealthDetails;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;


/*******************************************************************************
 ** Central health adapters reuse readiness and passive delivery state; never send.
 *******************************************************************************/
public class EmailHealthChecks implements HealthCheckSupplier
{
   /*******************************************************************************
    ** Register without querying data during metadata production.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      HealthChecksMetaData registry = HealthChecksMetaData.ofOrWithNew(instance);
      if(registry.getChecks().stream().noneMatch(check -> "email.readiness".equals(check.getName())))
      {
         registry.withCheck(definition("email.readiness", EmailHealthChecks::readiness));
      }
      if(registry.getSuppliers().stream().noneMatch(supplier -> "email.provider".equals(supplier.getNamePrefix())))
      {
         registry.withSupplier(new HealthCheckSupplierMetaData().withNamePrefix("email.provider").withComponent("email")
            .withSupplier(new QCodeReference(EmailHealthChecks.class)));
      }
   }



   /*******************************************************************************
    ** Provider primary keys are integers, so their names are already lowercase.
    ** Capture only identity; each execution rereads current state.
    *******************************************************************************/
   @Override
   public List<HealthCheckMetaData> supply(QInstance instance) throws QException
   {
      List<HealthCheckMetaData> checks = new ArrayList<>();
      for(QRecord provider : providers(EmailStore.equal("enabled", true), Set.of("id")))
      {
         Integer id = provider.getValueInteger("id");
         checks.add(definition("email.provider." + id, input -> provider(id, input)));
      }
      return (checks);
   }



   /*******************************************************************************
    ** Bound execution cooperatively around existing reads. Core also enforces
    ** the hard timeout. Exception text may contain PHI or secrets: omit it.
    *******************************************************************************/
   private static HealthCheckMetaData definition(String name, HealthCheck check)
   {
      return (new HealthCheckMetaData().withName(name).withLabel(name).withComponent("email")
         .withCategory(HealthCategory.EMAIL).withKind(HealthCheckKind.DEPENDENCY)
         .withIntervalSeconds(EmailHealthWidget.CHECK_INTERVAL_MINUTES * 60).withTimeoutMs(5000).withCheckInstance(input ->
         {
            try
            {
               requireTime(input);
               HealthCheckOutcome outcome = check.run(input);
               requireTime(input);
               return (outcome);
            }
            catch(Exception e)
            {
               return (HealthCheckOutcome.down("Email health check failed: " + e.getClass().getSimpleName()));
            }
         }));
   }



   /*******************************************************************************
    ** Aggregate all category preflights, exposing enum names instead of reasons.
    *******************************************************************************/
   private static HealthCheckOutcome readiness(HealthCheckInput input) throws QException
   {
      Set<EmailApiReadinessCheck> failed = EnumSet.noneOf(EmailApiReadinessCheck.class);
      boolean ready = true;
      for(EmailApiCategory category : EmailApiCategory.values())
      {
         requireTime(input);
         var readiness = EmailApi.readiness(input.getQInstance(), category);
         ready &= readiness.ready();
         failed.addAll(readiness.failedChecks());
      }
      return ((ready ? HealthCheckOutcome.up("Email configuration ready") : HealthCheckOutcome.degraded("Email configuration incomplete"))
         .withDetails(new HealthDetails().withValue("failedChecks", failed.stream().map(Enum::name).collect(Collectors.joining(",")))));
   }



   /*******************************************************************************
    ** The transport SPI has no non-sending connectivity method. Passive state
    ** is the only signal; never read or publish lastFailureMessage.
    *******************************************************************************/
   private static HealthCheckOutcome provider(Integer id, HealthCheckInput input) throws QException
   {
      List<QRecord> rows = providers(EmailStore.equal("id", id).withLimit(1), Set.of("id", "enabled", "healthStatus", "lastSuccessDate"));
      requireTime(input);
      if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().getValueBoolean("enabled")))
      {
         return (HealthCheckOutcome.unknown("Provider disabled or removed"));
      }
      QRecord row = rows.getFirst();
      String state = row.getValueString("healthStatus");
      HealthCheckOutcome outcome = "HEALTHY".equals(state) ? HealthCheckOutcome.up("Passive provider state healthy")
         : "UNHEALTHY".equals(state) ? HealthCheckOutcome.down("Passive provider state unhealthy; see provider record")
         : HealthCheckOutcome.unknown("Passive provider state unavailable");
      HealthDetails details = new HealthDetails().withValue("providerId", id).withValue("passive", true);
      Instant lastSuccess = row.getValueInstant("lastSuccessDate");
      if(lastSuccess != null)
      {
         details.withValue("lastSuccessDate", lastSuccess);
      }
      return (outcome.withDetails(details));
   }



   /*******************************************************************************
    ** Select only needed fields under the qbit's established system read scope.
    *******************************************************************************/
   private static List<QRecord> providers(QQueryFilter filter, Set<String> fields) throws QException
   {
      return (SystemWrites.call(() -> new QueryAction().execute(new QueryInput(EmailTables.PROVIDER).withFilter(filter)
         .withFieldNamesToInclude(fields).withShouldFetchHeavyFields(false).withShouldOmitHiddenFields(false)).getRecords()));
   }



   /*******************************************************************************
    ** Do not start further work after the runner's deadline or cancellation.
    *******************************************************************************/
   private static void requireTime(HealthCheckInput input) throws QException
   {
      if(Thread.currentThread().isInterrupted() || (input.getDeadline() != null && !Instant.now().isBefore(input.getDeadline())))
      {
         throw (new QException("Health check deadline exceeded"));
      }
   }
}
