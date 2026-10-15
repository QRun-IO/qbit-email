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


import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.health.HealthMetaDataProvider;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.StatisticsData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The home reads only the central rollup and honors health read permissions.
 *******************************************************************************/
class EmailHealthWidgetTest
{
   /*******************************************************************************
    ** Release thread-local state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }



   /*******************************************************************************
    ** A host without central health still has an honest empty status line.
    *******************************************************************************/
   @Test
   void missingHealthMetadata() throws Exception
   {
      var instance = H2Support.setup(config -> config.withSecretResolver(name -> null));
      assertThat(instance.getWidget("emailHealth").getType()).isEqualTo(WidgetType.STATISTICS.getType());
      var data = render();
      assertThat(data.getCount()).isEqualTo("UNKNOWN");
      assertThat(data.getCountContext()).contains("not configured");
      assertThat(data.getCountURL()).isNull();
   }



   /*******************************************************************************
    ** States come from health_component, never provider rows or free-form errors.
    *******************************************************************************/
   @Test
   void centralRollupAndPermissions() throws Exception
   {
      var instance = H2Support.setup(config -> config.withSecretResolver(name -> null));
      new HealthMetaDataProvider().defineAll(instance, "rdbms", table ->
      {
         // H2 needs no additional field metadata.
      });
      try(var connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) instance.getBackend("rdbms")))
      {
         connection.createStatement().execute("CREATE TABLE health_component (id INT PRIMARY KEY, name VARCHAR(100), status VARCHAR(100))");
         QContext.getQSession().setPermissions(Set.of("email.view"));
         assertThat(render().getCountContext()).contains("access required");
         assertThat(render().getCountURL()).isNull();
         QContext.getQSession().setPermissions(Set.of("email.view", "health.read"));
         instance.setEnforcePermissionsInCoreActions(true);
         assertThat(render().getCount()).isEqualTo("UNKNOWN");
         connection.createStatement().execute("INSERT INTO health_component VALUES (1, 'email', 'DOWN'), (2, 'other', 'UP')");
         assertThat(render().getCount()).isEqualTo("DOWN");
         String link = URLDecoder.decode(render().getCountURL(), StandardCharsets.UTF_8);
         assertThat(link).startsWith("/app/health_check?filter=").contains("component", "email");
         connection.createStatement().execute("UPDATE health_component SET status = 'UP' WHERE id = 1");
         assertThat(render().getCount()).isEqualTo("UP");
         connection.createStatement().execute("UPDATE health_component SET status = 'S3NT1NEL-secret' WHERE id = 1");
         assertThat(render().getCount()).isEqualTo("UNKNOWN");
         assertThat(render().getCountContext()).doesNotContain("S3NT1NEL-secret");
      }
   }



   /*******************************************************************************
    ** Render through the same renderer installed in widget metadata.
    *******************************************************************************/
   private StatisticsData render() throws Exception
   {
      return ((StatisticsData) new EmailHealthWidget().render(new RenderWidgetInput()).getWidgetData());
   }
}
