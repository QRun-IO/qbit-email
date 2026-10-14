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


import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.ChartData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Real viewer-scoped data. Counts preserve record security without sampling;
 ** inline bar charts and rows use the same theme-neutral styles as Webhooks.
 *******************************************************************************/
public final class EmailHome
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailHome()
   {
   }

   /*******************************************************************************
    ** Counts and recent messages, without loading bodies or variable snapshots.
    *******************************************************************************/
   public static RawHTML overview() throws QException
   {
      Instant now = EmailRuntime.clock().instant();
      List<Integer> totals = new ArrayList<>();
      String[] fields = {"sentDate", "deliveredDate", "deadLetterDate", "bouncedDate", "complainedDate"};
      for(int i = 0; i < fields.length; i++)
      {
         String eventType = switch(i)
         {
            case 1 -> "DELIVERED";
            case 3 -> "BOUNCED";
            case 4 -> "COMPLAINED";
            default -> null;
         };
         String field = eventType == null ? fields[i] : "occurredDate";
         QQueryFilter filter = since(field, now.minus(i < 3 ? 1 : 7, ChronoUnit.DAYS))
            .withCriteria(field, QCriteriaOperator.LESS_THAN_OR_EQUALS, now);
         if(eventType != null)
         {
            filter.withCriteria("eventType", QCriteriaOperator.EQUALS, eventType);
         }
         totals.add(count(eventType == null ? EmailTables.MESSAGE : EmailTables.EVENT, filter));
      }
      totals.add(count(EmailTables.SUPPRESSION, new QQueryFilter()));
      List<QRecord> messages = query(EmailTables.MESSAGE, new QQueryFilter().withOrderBy(new QFilterOrderBy("createDate", false))
         .withOrderBy(new QFilterOrderBy("id", false)).withLimit(20), List.of("id", "createDate", "toAddresses", "subject", "phiApproved", "status"));
      boolean admin = EmailPermissionChecker.has(EmailPermissions.ADMIN);
      List<QRecord> providers = admin ? query(EmailTables.PROVIDER, new QQueryFilter().withOrderBy(new QFilterOrderBy("id", true)),
         List.of("id", "label", "name", "enabled", "healthStatus", "lastSuccessDate", "lastFailureMessage", "maxPerMinute")) : List.of();
      for(QRecord provider : providers)
      {
         Integer limit = provider.getValueInteger("maxPerMinute");
         if(limit != null)
         {
            int used = count(EmailTables.ATTEMPT, since("startDate", now.minusSeconds(60))
               .withCriteria("startDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, now)
               .withCriteria("providerId", QCriteriaOperator.EQUALS, provider.getValue("id")));
            provider.setValue("headroom", Math.max(0, limit - used));
         }
      }
      return (new RawHTML(null, EmailHomeView.render(totals, hourly(), providers, admin, messages, suppressions())));
   }

   /*******************************************************************************
    ** Twenty-four UTC hour buckets, counting successful and failed attempts.
    *******************************************************************************/
   public static ChartData hourly() throws QException
   {
      Instant start = EmailRuntime.clock().instant().truncatedTo(ChronoUnit.HOURS).minus(23, ChronoUnit.HOURS);
      List<String> labels = new ArrayList<>();
      List<Number> sent = new ArrayList<>();
      List<Number> failed = new ArrayList<>();
      for(int i = 0; i < 24; i++)
      {
         Instant hour = start.plus(i, ChronoUnit.HOURS);
         labels.add(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(hour));
         QQueryFilter filter = since("endDate", hour).withCriteria("endDate", QCriteriaOperator.LESS_THAN, hour.plus(1, ChronoUnit.HOURS));
         sent.add(count(EmailTables.ATTEMPT, filter.clone().withCriteria("outcome", QCriteriaOperator.EQUALS, "SUCCEEDED")));
         failed.add(count(EmailTables.ATTEMPT, filter.clone().withCriteria("outcome", QCriteriaOperator.IN, List.of("TRANSIENT_FAILURE", "PROVIDER_FAILURE", "PERMANENT_FAILURE", "LEASE_EXPIRED"))));
      }
      return (chart(labels, List.of(dataset("Sent", sent, "#16a34a"), dataset("Failed", failed, "#dc2626")), "No completed attempts in the last 24 hours. Counts use UTC hours."));
   }

   /*******************************************************************************
    ** Suppression rows created per UTC day and reason, for thirty days.
    *******************************************************************************/
   public static ChartData suppressions() throws QException
   {
      Instant start = EmailRuntime.clock().instant().truncatedTo(ChronoUnit.DAYS).minus(29, ChronoUnit.DAYS);
      List<String> labels = new ArrayList<>();
      List<ChartData.Data.Dataset> datasets = new ArrayList<>();
      List<String> reasons = List.of("HARD_BOUNCE", "COMPLAINT", "UNSUBSCRIBE", "MANUAL");
      List<String> colors = List.of("#dc2626", "#d97706", "#2563eb", "#6b7280");
      for(int r = 0; r < reasons.size(); r++)
      {
         List<Number> counts = new ArrayList<>();
         for(int i = 0; i < 30; i++)
         {
            Instant day = start.plus(i, ChronoUnit.DAYS);
            if(r == 0)
            {
               labels.add(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(day));
            }
            counts.add(count(EmailTables.SUPPRESSION, since("createdDate", day).withCriteria("createdDate", QCriteriaOperator.LESS_THAN, day.plus(1, ChronoUnit.DAYS))
               .withCriteria("reason", QCriteriaOperator.EQUALS, reasons.get(r))));
         }
         datasets.add(dataset(com.kingsrook.qbits.email.metadata.EmailEnums.SuppressionReason.valueOf(reasons.get(r)).getPossibleValueLabel(), counts, colors.get(r)));
      }
      return (chart(labels, datasets, "No new suppressions in the last 30 days. Counts use UTC dates."));
   }

   /*******************************************************************************
    ** Standard chart with an honest empty-state sublabel.
    *******************************************************************************/
   private static ChartData chart(List<String> labels, List<ChartData.Data.Dataset> datasets, String empty)
   {
      ChartData chart = new ChartData().withChartData(new ChartData.Data().withLabels(labels).withDatasets(datasets));
      boolean any = datasets.stream().anyMatch(d -> d.getData().stream().anyMatch(n -> n.doubleValue() > 0));
      chart.setSublabel(any ? "Real data visible to your session; UTC buckets." : empty);
      return (chart);
   }

   /*******************************************************************************
    ** A standard chart series.
    *******************************************************************************/
   private static ChartData.Data.Dataset dataset(String label, List<Number> values, String color)
   {
      return (new ChartData.Data.Dataset().withLabel(label).withData(values).withColor(color));
   }

   /*******************************************************************************
    ** Mask display names and all but the first local-part character.
    *******************************************************************************/
   public static String mask(String recipients)
   {
      try
      {
         String address = jakarta.mail.internet.InternetAddress.parse(recipients)[0].getAddress();
         int at = address.lastIndexOf('@');
         return (at > 0 ? address.charAt(0) + "***" + address.substring(at) : "[masked]");
      }
      catch(Exception e)
      {
         return ("[masked]");
      }
   }

   /*******************************************************************************
    ** Date lower bound, always used with viewer-scoped actions.
    *******************************************************************************/
   private static QQueryFilter since(String field, Instant since)
   {
      return (new QQueryFilter().withCriteria(field, QCriteriaOperator.GREATER_THAN_OR_EQUALS, since));
   }

   /*******************************************************************************
    ** Exact count as the viewer, without authorized scopes.
    *******************************************************************************/
   private static int count(String table, QQueryFilter filter) throws QException
   {
      return (new CountAction().execute(new CountInput(table).withFilter(filter)).getCount());
   }

   /*******************************************************************************
    ** Select only home fields; row and field security remain active.
    *******************************************************************************/
   private static List<QRecord> query(String table, QQueryFilter filter, List<String> fields) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table).withFilter(filter).withFieldNamesToInclude(new java.util.HashSet<>(fields)).withShouldFetchHeavyFields(true)).getRecords());
   }
}
