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


import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider;
import com.kingsrook.qqq.backend.core.health.HealthStatus;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.StatisticsData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;


/*******************************************************************************
 ** One standard status line linking to central checks; no provider diagnostics.
 *******************************************************************************/
public class EmailHealthWidget extends AbstractWidgetRenderer
{
   /*******************************************************************************
    ** Read the persisted rollup as the viewer, preserving health permissions.
    *******************************************************************************/
   @Override
   public RenderWidgetOutput render(RenderWidgetInput input) throws QException
   {
      String status = HealthStatus.UNKNOWN.name();
      boolean installed = QContext.getQInstance().getTable(HealthMetaDataProvider.COMPONENT_TABLE) != null;
      boolean readable = installed && PermissionsHelper.hasTablePermission(input, HealthMetaDataProvider.COMPONENT_TABLE, TablePermissionSubType.READ);
      if(readable)
      {
         List<QRecord> rows = new QueryAction().execute(new QueryInput(HealthMetaDataProvider.COMPONENT_TABLE)
            .withFilter(EmailStore.equal("name", "email").withLimit(1)).withFieldNamesToInclude(Set.of("status"))).getRecords();
         if(!rows.isEmpty())
         {
            String stored = rows.getFirst().getValueString("status");
            for(HealthStatus candidate : HealthStatus.values())
            {
               if(candidate.name().equals(stored))
               {
                  status = candidate.name();
               }
            }
         }
      }
      StatisticsData data = new StatisticsData().withCount(status).withCountFontSize("1rem")
         .withCountContext(!installed ? "Email - health monitoring not configured" : readable ? "Email - System Health" : "Email - health access required");
      if(readable)
      {
         data.withCountURL("/app/health_check?filter=" + URLEncoder.encode(JsonUtils.toJson(EmailStore.equal("component", "email")), StandardCharsets.UTF_8));
      }
      return (new RenderWidgetOutput(data));
   }
}
