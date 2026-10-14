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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.app.EmailApp;
import com.kingsrook.qbits.email.bridge.EmailOutboxMessagingProviderMetaData;
import com.kingsrook.qbits.email.delivery.EmailAlerts;
import com.kingsrook.qbits.email.metadata.EmailTableCustomizers;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSectionAlternativeType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Entry point of the email outbox qbit.
 *******************************************************************************/
public class EmailQBitProducer implements QBitMetaDataProducer<EmailQBitConfig>
{
   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-email";
   public static final String VERSION     = "0.1.0";

   private EmailQBitConfig qBitConfig;
   private String          namespace;



   /*******************************************************************************
    ** Fluent setter for the config.
    *******************************************************************************/
   public EmailQBitProducer withQBitConfig(EmailQBitConfig config)
   {
      this.qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the config.
    *******************************************************************************/
   @Override
   public EmailQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** Fluent setter for the namespace.
    *******************************************************************************/
   public EmailQBitProducer withNamespace(String namespace)
   {
      this.namespace = namespace;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the namespace.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (namespace);
   }



   /*******************************************************************************
    ** Identity.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData().withGroupId(GROUP_ID).withArtifactId(ARTIFACT_ID).withVersion(VERSION).withNamespace(namespace).withConfig(qBitConfig));
   }



   /*******************************************************************************
    ** Package-scanned producers have no config: skip them.
    *******************************************************************************/
   @Override
   public boolean isEnabled()
   {
      return (qBitConfig != null);
   }



   /*******************************************************************************
    ** Validate and register the tables, bridge, processes, alerts and app.
    *******************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance qInstance) throws QException
   {
      List<String> errors = new ArrayList<>();
      qBitConfig.validate(qInstance, errors);
      if(!errors.isEmpty())
      {
         throw (new QException("Email qbit configuration is invalid: " + String.join("; ", errors)));
      }
      qInstance.addQBit(getQBitMetaData());
      MetaDataProducerMultiOutput output = new MetaDataProducerMultiOutput();
      EmailTables.possibleValueSources().forEach(output::add);
      Map<String, QTableMetaData> tables = new LinkedHashMap<>();
      for(QTableMetaData table : EmailTables.tables(qBitConfig))
      {
         Class<?> customizer = EmailTables.PROVIDER.equals(table.getName()) ? EmailTableCustomizers.ProviderCustomizer.class
            : EmailTables.SUPPRESSION.equals(table.getName()) ? EmailTableCustomizers.SuppressionCustomizer.class : null;
         if(customizer != null)
         {
            table.withCustomizer(TableCustomizers.PRE_INSERT_RECORD, new QCodeReference(customizer))
               .withCustomizer(TableCustomizers.PRE_UPDATE_RECORD, new QCodeReference(customizer))
               .withCustomizer(TableCustomizers.PRE_DELETE_RECORD, new QCodeReference(customizer));
         }
         if(EmailTables.MESSAGE.equals(table.getName()))
         {
            table.withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(EmailTableCustomizers.MessageLinksCustomizer.class));
         }
         if(EmailTables.PROVIDER.equals(table.getName()))
         {
            table.withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(EmailTableCustomizers.ProviderCustomizer.class));
            for(String field : List.of("usernameEnvName", "secretEnvName", "webhookSecretEnvName"))
            {
               table.withVirtualField(new QVirtualFieldMetaData(field + "Status", QFieldType.STRING)
                  .withLabel(field.equals("usernameEnvName") ? "Username set" : field.equals("secretEnvName") ? "Secret set" : "Webhook secret set")
                  .withIsEditable(false).withIsQuerySelectable(true));
            }
            table.getSections().get(1).withAlternative(QFieldSectionAlternativeType.RECORD_VIEW, section ->
            {
               List<String> fields = new ArrayList<>(section.getFieldNames());
               fields.addAll(List.of("usernameEnvNameStatus", "secretEnvNameStatus", "webhookSecretEnvNameStatus"));
               section.setFieldNames(fields);
            });
         }
         if(qBitConfig.getTableMetaDataCustomizer() != null)
         {
            table = qBitConfig.getTableMetaDataCustomizer().customizeMetaData(qInstance, table);
         }
         table.setSourceQBitName(getQBitMetaData().getName());
         tables.put(table.getName(), table);
         output.add(table);
      }
      EmailTables.joins().forEach(output::add);
      EmailTables.childWidgets().forEach(output::add);
      EmailApp.widgets().forEach(output::add);
      EmailProcesses.processes(qBitConfig).forEach(process ->
      {
         process.setSourceQBitName(getQBitMetaData().getName());
         output.add(process);
      });
      EmailAlerts.types().forEach(output::add);
      output.add(EmailApp.app(qBitConfig, tables));
      output.add(new EmailOutboxMessagingProviderMetaData().withName(qBitConfig.getBridgeProviderName()));
      output.addSelfToInstance(qInstance);
      return (new MetaDataProducerMultiOutput());
   }
}
