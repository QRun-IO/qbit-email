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


import java.util.Map;
import com.kingsrook.qbits.email.template.CrmLegacyTemplateRenderer;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Populated legacy fixtures preserve behavior even when production has no rows.
 *******************************************************************************/
class CrmLegacyTemplateRendererTest
{
   /*******************************************************************************
    ** Unknown syntax and substituted values stay literal; replacements run once.
    *******************************************************************************/
   @Test
   void literalSinglePassAndBodyPreference()
   {
      QRecord template = new QRecord().withValue("id", 7).withValue("ownerQbit", "crm");
      QRecord version = new QRecord().withValue("id", 9).withValue("subject", "Hi {{contact.firstName}}")
         .withValue("bodySource", "<p>html</p>")
         .withValue("textSource", "{{contact.lastName}} {{company.name}} {{owner.name}} {{unsubscribeUrl}} {{unknown}} {{#if x}}");
      QRecord rendered = CrmLegacyTemplateRenderer.render(template, version,
         Map.of("contact.firstName", "{{contact.lastName}}", "contact.lastName", "$1\\value", "company.name", "A&B", "owner.name", "42", "unsubscribeUrl", "https://example.test/u"), null, null);
      assertThat(rendered.getValueString("subject")).isEqualTo("Hi {{contact.lastName}}");
      assertThat(rendered.getValueString("markdownBody")).isEqualTo("$1\\value A&B 42 https://example.test/u {{unknown}} {{#if x}}");
      assertThat(rendered.getValueInteger("templateId")).isEqualTo(7);
      assertThat(rendered.getValueInteger("templateVersionId")).isEqualTo(9);
      version.setValue("textSource", null);
      assertThat(CrmLegacyTemplateRenderer.render(template, version, Map.of(), null, null).getValueString("markdownBody")).isEqualTo("<p>html</p>");
      assertThat(CrmLegacyTemplateRenderer.render(template, version, Map.of(), "override", "body").getValueString("subject")).isEqualTo("override");
      version.setValue("textSource", "");
      assertThatThrownBy(() -> CrmLegacyTemplateRenderer.render(template, version, Map.of(), null, null)).hasMessageContaining("required");
   }

   /*******************************************************************************
    ** Compatibility never grants PHI export or accepts header injection.
    *******************************************************************************/
   @Test
   void rejectsProtectedOrInvalidContent()
   {
      QRecord template = new QRecord().withValue("ownerQbit", "crm").withValue("phiApproved", true);
      QRecord version = new QRecord().withValue("subject", "subject").withValue("bodySource", "body");
      assertThatThrownBy(() -> CrmLegacyTemplateRenderer.render(template, version, Map.of(), null, null)).hasMessageContaining("cannot be PHI");
      template.setValue("phiApproved", false);
      template.setValue("ownerQbit", "other");
      assertThatThrownBy(() -> CrmLegacyTemplateRenderer.render(template, version, Map.of(), null, null)).hasMessageContaining("CRM ownership");
      template.setValue("ownerQbit", "crm");
      assertThatThrownBy(() -> CrmLegacyTemplateRenderer.render(template, version, Map.of(), "subject\nheader", null)).hasMessageContaining("Invalid");
   }
}
