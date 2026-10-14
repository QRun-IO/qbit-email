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


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.health.EmailHealthWidget;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Email app with viewer-scoped dashboard widgets and the five main tables in navigation.
 ** Versions and attempts are child lists, and processes are record actions.
 *******************************************************************************/
public final class EmailApp
{
   public static final String HOME_WIDGET         = "emailHome";




   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailApp()
   {
   }



   /*******************************************************************************
    ** Standard home and record preview widgets.
    *******************************************************************************/
   public static List<QWidgetMetaData> widgets()
   {
      return (List.of(
         widget(HOME_WIDGET, "Overview", WidgetType.HTML.getType(), HomeRenderer.class),
         widget("emailHealth", "Email health", WidgetType.STATISTICS.getType(), EmailHealthWidget.class),
         widget("emailTemplatePreview", "Preview", WidgetType.HTML.getType(), com.kingsrook.qbits.email.template.EmailPreviewWidget.class)));

   }



   /*******************************************************************************
    ** The app, with the main tables as children.
    *******************************************************************************/
   public static QAppMetaData app(EmailQBitConfig config, Map<String, QTableMetaData> tables)
   {
      QAppMetaData app = new QAppMetaData()
         .withName(config.getAppName())
         .withLabel(config.getAppLabel())
         .withIcon(new QIcon("mail"))
         .withPermissionRules(EmailTables.permissionRules())
         .withWidgets(List.of("emailHealth", HOME_WIDGET));
      for(String name : EmailTables.MAIN)
      {
         if(tables.containsKey(name))
         {
            app.withChild(tables.get(name));
         }
      }
      return (app);
   }



   /*******************************************************************************
    ** Home renderer.
    *******************************************************************************/
   public static class HomeRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(EmailHome.overview()));
      }
   }



   /*******************************************************************************
    ** Full-width widgets follow the Alerts home pattern.
    *******************************************************************************/
   private static QWidgetMetaData widget(String name, String label, String type, Class<?> renderer)
   {
      return (new QWidgetMetaData().withName(name).withLabel(label).withType(type).withGridColumns(12).withIsCard(false)
         .withShowReloadButton(true).withCodeReference(new QCodeReference(renderer)).withPermissionRules(EmailTables.permissionRules()));
   }
}
