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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.customizers.AbstractPostQueryCustomizer;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.health.HealthStatus;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Exercise the producer and real H2 reads without contacting any transport.
 *******************************************************************************/
class EmailHealthChecksTest
{
   private static final String SENTINEL = "S3NT1NEL-secret";
   private QInstance instance;
   private QRecord provider;



   /*******************************************************************************
    ** All credential values are synthetic; transport calls must never occur.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      instance = H2Support.setup(config -> config.withSecretResolver(name -> SENTINEL));
      provider = H2Support.provider(25);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id"))
         .withValue("usernameEnvName", "EMAIL_SMTP_USERNAME").withValue("secretEnvName", "EMAIL_SMTP_PASSWORD")
         .withValue("security", "STARTTLS").withValue("lastFailureMessage", "IllegalStateException: " + SENTINEL + " recipient@example.test"));
      EmailRuntime.config().withTransportRegistryFactory(() ->
      {
         throw (new AssertionError("Health checks must not create transports"));
      });
   }



   /*******************************************************************************
    ** Clear the thread-local instance after each isolated database.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** Production registers a static check and a dynamic supplier with stable ids.
    *******************************************************************************/
   @Test
   void registrationAndDynamicMembership() throws Exception
   {
      HealthChecksMetaData registry = HealthChecksMetaData.ofOrWithNew(instance);
      EmailHealthChecks.register(instance);
      assertThat(registry.getChecks()).extracting(HealthCheckMetaData::getName).containsExactly("email.readiness");
      assertThat(registry.getSuppliers()).singleElement().satisfies(supplier ->
      {
         assertThat(supplier.getNamePrefix()).isEqualTo("email.provider");
         assertThat(supplier.getComponent()).isEqualTo("email");
      });
      assertThat(registry.resolveAll(instance)).hasSize(2).allSatisfy(check ->
      {
         assertThat(check.getName()).matches("[a-z0-9]+([._-][a-z0-9]+)*");
         assertThat(check.getKind()).isEqualTo(HealthCheckKind.DEPENDENCY);
         assertThat(check.getCategory()).isEqualTo(HealthCategory.EMAIL);
         assertThat(check.getComponent()).isEqualTo("email");
         assertThat(check.getIntervalSeconds()).isEqualTo(300);
         assertThat(check.getTimeoutMs()).isEqualTo(5000);
      });
      HealthCheck cached = providerCheck();
      update("enabled", false);
      assertThat(registry.resolveAll(instance)).hasSize(1);
      assertThat(run(cached).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      update("enabled", true);
      assertThat(registry.resolveAll(instance)).hasSize(2);
      SystemWrites.delete(EmailTables.PROVIDER, EmailStore.equal("id", provider.getValueInteger("id")));
      assertThat(run(cached).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      assertThat(registry.resolveAll(instance)).hasSize(1);
   }



   /*******************************************************************************
    ** A resolved check observes later delivery transitions, with safe details only.
    *******************************************************************************/
   @Test
   void passiveStatesAndNoSecrets() throws Exception
   {
      HealthCheck check = providerCheck();
      update("healthStatus", "HEALTHY");
      Instant success = Instant.parse("2026-10-14T12:00:00Z");
      update("lastSuccessDate", success);
      var outcome = run(check);
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.UP);
      assertThat(outcome.getDetails().getValues()).containsEntry("passive", true).containsEntry("lastSuccessDate", success);
      update("healthStatus", "UNHEALTHY");
      outcome = run(check);
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertThat(outcome.getMessage()).contains("see provider record");
      for(String state : new String[] {null, SENTINEL})
      {
         update("healthStatus", state);
         assertThat(run(check).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
      assertThat(H2Support.all(EmailTables.ATTEMPT)).isEmpty();
   }



   /*******************************************************************************
    ** Marketing gaps degrade the aggregate even while other categories are ready.
    *******************************************************************************/
   @Test
   void readinessAcrossCategories() throws Exception
   {
      var outcome = run(readinessCheck());
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DEGRADED);
      assertThat(outcome.getDetails().getValues().get("failedChecks").toString().split(","))
         .containsExactly("PUBLIC_BASE_URL", "BRAND_POSTAL_ADDRESS");
      assertThat(outcome.getMessage()).contains("Brand postal address is not set").contains("Brand > open the default brand > Postal address").contains("transactional email is unaffected").hasSizeLessThanOrEqualTo(500);
      assertThat(outcome.getDetails().getValues().get("howToFix").toString()).contains("Public base URL is not set").contains("Brand postal address is not set");
      configureMarketing();
      assertThat(run(readinessCheck()).getStatus()).isEqualTo(HealthStatus.UP);
      update("enabled", false);
      outcome = run(readinessCheck());
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DEGRADED);
      assertThat(outcome.getDetails().getValues()).containsEntry("failedChecks", "PROVIDER_MISSING");
      update("enabled", true);
      update("host", null);
      assertThat(run(readinessCheck()).getDetails().getValues()).containsEntry("failedChecks", "PROVIDER_INVALID");
   }



   /*******************************************************************************
    ** Secret resolver exceptions are expected readiness gaps, never diagnostics.
    *******************************************************************************/
   @Test
   void readinessResolverFailureIsSecretSafe() throws Exception
   {
      configureMarketing();
      EmailRuntime.config().withSecretResolver(name ->
      {
         throw (new IllegalStateException(SENTINEL + " recipient@example.test"));
      });
      var outcome = run(readinessCheck());
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DEGRADED);
      assertThat(outcome.getDetails().getValues()).containsEntry("failedChecks", "UNSUBSCRIBE_KEY");
      assertThat(run(providerCheck()).getStatus()).isEqualTo(HealthStatus.UP);
   }



   /*******************************************************************************
    ** Unexpected database/customizer errors cannot leak their exception text.
    *******************************************************************************/
   @Test
   void queryFailuresAreSecretSafe() throws Exception
   {
      List<HealthCheckMetaData> checks = HealthChecksMetaData.ofOrWithNew(instance).resolveAll(instance);
      instance.getTable(EmailTables.PROVIDER).getCustomizers().put(TableCustomizers.POST_QUERY_RECORD.getRole(), new QCodeReference(PoisonedQuery.class));
      for(HealthCheckMetaData check : checks)
      {
         assertThat(run(check.getCheckInstance()).getStatus()).isEqualTo(HealthStatus.DOWN);
      }
   }



   /*******************************************************************************
    ** Expired deadlines prevent reads; expiration during readiness prevents UP.
    *******************************************************************************/
   @Test
   void respectsDeadlinesAndCancellation() throws Exception
   {
      List<HealthCheckMetaData> checks = HealthChecksMetaData.ofOrWithNew(instance).resolveAll(instance);
      AtomicBoolean queried = new AtomicBoolean();
      EmailRuntime.config().withSecretResolver(name ->
      {
         queried.set(true);
         return (SENTINEL);
      });
      for(HealthCheckMetaData check : checks)
      {
         var outcome = check.getCheckInstance().run(input().withDeadline(Instant.EPOCH));
         assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DOWN);
         clean(outcome);
      }
      assertThat(queried).isFalse();
      EmailRuntime.config().withSecretResolver(name ->
      {
         Thread.currentThread().interrupt();
         return (SENTINEL);
      });
      try
      {
         assertThat(run(readinessCheck()).getStatus()).isEqualTo(HealthStatus.DOWN);
         assertThat(run(providerCheckFrom(checks)).getStatus()).isEqualTo(HealthStatus.DOWN);
      }
      finally
      {
         Thread.interrupted();
      }
   }



   /*******************************************************************************
    ** The health runner has health permissions only; qbit reads use system scope.
    *******************************************************************************/
   @Test
   void runsWithHealthOnlyPermissions() throws Exception
   {
      configureMarketing();
      instance.setEnforcePermissionsInCoreActions(true);
      QContext.getQSession().setPermissions(Set.of("health.read"));
      assertThat(run(readinessCheck()).getStatus()).isEqualTo(HealthStatus.UP);
      assertThat(run(providerCheck()).getStatus()).isEqualTo(HealthStatus.UP);
   }



   /*******************************************************************************
    ** Work finishing after its deadline must not report a successful result.
    *******************************************************************************/
   @Test
   void deadlineExpiresDuringReadiness() throws Exception
   {
      configureMarketing();
      HealthCheckInput input = input();
      EmailRuntime.config().withSecretResolver(name ->
      {
         input.setDeadline(Instant.EPOCH);
         return (SENTINEL);
      });
      var outcome = readinessCheck().run(input);
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DOWN);
      clean(outcome);
   }



   /*******************************************************************************
    ** Minimal valid marketing prerequisites use a synthetic signing secret.
    *******************************************************************************/
   private void configureMarketing() throws Exception
   {
      EmailRuntime.config().withPublicBaseUrl("https://app.example.test");
      SystemWrites.insertOne(EmailTables.BRAND, new QRecord().withValue("name", "default").withValue("label", "Default")
         .withValue("isDefault", true).withValue("postalAddress", "1 Main St"));
   }



   /*******************************************************************************
    ** Reuse system mutation semantics for passive fields.
    *******************************************************************************/
   private void update(String field, java.io.Serializable value) throws Exception
   {
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue(field, value));
   }



   /*******************************************************************************
    ** Resolve dynamic definitions through the real registry.
    *******************************************************************************/
   private HealthCheck providerCheck() throws Exception
   {
      return (providerCheckFrom(HealthChecksMetaData.ofOrWithNew(instance).resolveAll(instance)));
   }



   /*******************************************************************************
    ** Obtain the check for this fixture's integer primary key.
    *******************************************************************************/
   private HealthCheck providerCheckFrom(List<HealthCheckMetaData> checks)
   {
      return (checks.stream().filter(check -> check.getName().equals("email.provider." + provider.getValueInteger("id"))).findFirst().orElseThrow().getCheckInstance());
   }



   /*******************************************************************************
    ** Static readiness definition installed by the producer.
    *******************************************************************************/
   private HealthCheck readinessCheck()
   {
      return (HealthChecksMetaData.ofOrWithNew(instance).getChecks().getFirst().getCheckInstance());
   }



   /*******************************************************************************
    ** Use a real deadline rather than the fixture's delivery clock.
    *******************************************************************************/
   private HealthCheckInput input()
   {
      return (new HealthCheckInput().withQInstance(instance).withDeadline(Instant.now().plusSeconds(5)));
   }



   /*******************************************************************************
    ** The runner has package-private sanitize; assert the raw boundary is clean.
    *******************************************************************************/
   private HealthCheckOutcome run(HealthCheck check) throws Exception
   {
      var outcome = check.run(input());
      clean(outcome);
      return (outcome);
   }



   /*******************************************************************************
    ** Check both message and serialized details on every success/failure path.
    *******************************************************************************/
   private void clean(HealthCheckOutcome outcome)
   {
      assertThat(outcome.getMessage() + outcome.getDetails().toJson()).doesNotContain(SENTINEL, "recipient@example.test", "sender@example.com", "127.0.0.1");
   }



   /*******************************************************************************
    ** Inject a failure after a real database read, like a failing table customizer.
    *******************************************************************************/
   public static class PoisonedQuery extends AbstractPostQueryCustomizer
   {
      /*******************************************************************************
       ** The adapter must discard this untrusted exception message.
       *******************************************************************************/
      @Override
      public List<QRecord> apply(List<QRecord> records)
      {
         throw (new IllegalStateException(SENTINEL + " recipient@example.test"));
      }
   }
}
