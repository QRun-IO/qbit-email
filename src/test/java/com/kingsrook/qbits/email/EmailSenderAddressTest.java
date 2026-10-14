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

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueSearchFilterUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Provider sender addresses: validation, sending rules, the fallback to the
 ** provider's own from address and every contingent possible-value pair.
 *******************************************************************************/
class EmailSenderAddressTest
{
   private QRecord relay;
   private QRecord other;

   /*******************************************************************************
    ** Two providers on an isolated database.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      H2Support.setup(c ->
      {
      });
      relay = H2Support.provider(25);
      other = H2Support.insert(EmailTables.PROVIDER, new QRecord().withValue("name", "other").withValue("kind", "SMTP").withValue("host", "127.0.0.1")
         .withValue("port", 25).withValue("security", "NONE").withValue("fromAddress", "other@example.com").withValue("enabled", true).withValue("isDefault", false));
   }

   /*******************************************************************************
    ** Clear thread state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }

   /*******************************************************************************
    ** A sender address row for a provider.
    *******************************************************************************/
   private static QRecord row(QRecord provider, String address)
   {
      return (new QRecord().withValue("providerId", provider.getValue("id")).withValue("address", address).withValue("enabled", true).withValue("isDefault", false));
   }

   /*******************************************************************************
    ** Insert through the table action and return the (possibly errored) result.
    *******************************************************************************/
   private static QRecord tryInsert(QRecord row) throws Exception
   {
      return (new InsertAction().execute(new InsertInput(EmailTables.SENDER).withRecords(List.of(row))).getRecords().get(0));
   }

   /*******************************************************************************
    ** A one-recipient request.
    *******************************************************************************/
   private static EmailRequest request(String providerName)
   {
      return (new EmailRequest().withProviderName(providerName).withTo("person@example.net").withSubject("Hello").withTextBody("Body"));
   }

   /*******************************************************************************
    ** Addresses are canonical lowercase and line breaks, bad addresses are refused.
    *******************************************************************************/
   @Test
   void addressIsCanonicalAndSafe() throws Exception
   {
      QRecord saved = H2Support.insert(EmailTables.SENDER, row(relay, "Sales@Example.COM"));
      assertThat(saved.getValueString("address")).isEqualTo("sales@example.com");
      assertThat(tryInsert(row(relay, "a@example.com\nBcc: x@example.net")).getErrors()).isNotEmpty();
      assertThat(tryInsert(row(relay, "not an address")).getErrors()).isNotEmpty();
      assertThat(tryInsert(row(relay, "ok@example.com").withValue("displayName", "Evil\r\nBcc: x@example.net")).getErrors()).isNotEmpty();
      assertThat(tryInsert(new QRecord().withValue("providerId", 9999).withValue("address", "x@example.com")).getErrors()).isNotEmpty();
   }

   /*******************************************************************************
    ** The domain must equal the provider sending domain when one is set.
    *******************************************************************************/
   @Test
   void domainMustMatchSendingDomain() throws Exception
   {
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", relay.getValue("id")).withValue("sendingDomain", "example.com"));
      assertThat(tryInsert(row(relay, "x@other.org")).getErrors()).isNotEmpty();
      assertThat(tryInsert(row(relay, "x@EXAMPLE.com")).getErrors()).isNullOrEmpty();
      QRecord off = tryInsert(row(other, "x@other.org"));
      assertThat(off.getErrors()).isNullOrEmpty();
      var update = new UpdateAction().execute(new UpdateInput(EmailTables.PROVIDER).withRecords(List.of(new QRecord().withValue("id", other.getValue("id")).withValue("sendingDomain", "example.com"))));
      assertThat(update.getRecords().get(0).getErrors()).isNotEmpty();
   }

   /*******************************************************************************
    ** Unique per provider; the same address may belong to another provider.
    *******************************************************************************/
   @Test
   void addressIsUniquePerProvider() throws Exception
   {
      H2Support.insert(EmailTables.SENDER, row(relay, "sales@example.com"));
      assertThat(tryInsert(row(relay, "SALES@example.com")).getErrors()).isNotEmpty();
      assertThat(tryInsert(row(other, "sales@example.com")).getErrors()).isNullOrEmpty();
      var both = new InsertAction().execute(new InsertInput(EmailTables.SENDER).withRecords(List.of(row(relay, "twin@example.com"), row(relay, "twin@example.com")))).getRecords();
      assertThat(both.stream().filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())).isNotEmpty();
   }

   /*******************************************************************************
    ** At most one default per provider, and a default must be enabled.
    *******************************************************************************/
   @Test
   void singleEnabledDefault() throws Exception
   {
      H2Support.insert(EmailTables.SENDER, row(relay, "a@example.com").withValue("isDefault", true));
      assertThat(tryInsert(row(relay, "b@example.com").withValue("isDefault", true)).getErrors()).isNotEmpty();
      assertThat(tryInsert(row(other, "c@example.com").withValue("isDefault", true)).getErrors()).isNullOrEmpty();
      assertThat(tryInsert(row(relay, "d@example.com").withValue("isDefault", true).withValue("enabled", false)).getErrors()).isNotEmpty();
      QRecord plain = H2Support.insert(EmailTables.SENDER, row(relay, "e@example.com"));
      var promote = new UpdateAction().execute(new UpdateInput(EmailTables.SENDER).withRecords(List.of(new QRecord().withValue("id", plain.getValue("id")).withValue("isDefault", true))));
      assertThat(promote.getRecords().get(0).getErrors()).isNotEmpty();
   }

   /*******************************************************************************
    ** Deleting a provider removes its sender addresses.
    *******************************************************************************/
   @Test
   void deletingProviderRemovesSenderAddresses() throws Exception
   {
      H2Support.insert(EmailTables.SENDER, row(other, "gone@example.com"));
      new DeleteAction().execute(new DeleteInput(EmailTables.PROVIDER).withPrimaryKey(other.getValue("id")));
      assertThat(H2Support.all(EmailTables.SENDER)).isEmpty();
   }

   /*******************************************************************************
    ** Sender rows are admin-only, like providers.
    *******************************************************************************/
   @Test
   void senderRowsAreAdminOnly() throws Exception
   {
      var table = QContext.getQInstance().getTable(EmailTables.SENDER);
      assertThat(table.getPermissionRules().getPermissionBaseName()).isEqualTo("email");
      assertThat(EmailTables.MAIN).doesNotContain(EmailTables.SENDER);
      assertThat(QContext.getQInstance().getJoin(EmailTables.PROVIDER + "_join_" + EmailTables.SENDER)).isNotNull();
      assertThat(QContext.getQInstance().getWidget(EmailTables.SENDER + "_children")).isNotNull();
      QContext.getQSession().setPermissions(java.util.Set.of("email.manage"));
      var checker = new EmailPermissionChecker();
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new InsertInput(EmailTables.SENDER), table)).hasMessageContaining("email.admin");
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new QueryInput(EmailTables.SENDER), table)).hasMessageContaining("email.admin");
      QContext.getQSession().setPermissions(java.util.Set.of("email.admin"));
      checker.checkPermissionsThrowing(new InsertInput(EmailTables.SENDER), table);
   }

   /*******************************************************************************
    ** Sending with a chosen id: ok, another provider's row, and a disabled row.
    *******************************************************************************/
   @Test
   void sendWithFromAddressId() throws Exception
   {
      QRecord mine = H2Support.insert(EmailTables.SENDER, row(relay, "Sales@example.com").withValue("displayName", "Sales Desk"));
      QRecord theirs = H2Support.insert(EmailTables.SENDER, row(other, "help@example.com"));
      QRecord off = H2Support.insert(EmailTables.SENDER, row(relay, "old@example.com").withValue("enabled", false));
      EmailSendResult ok = EmailService.send(request("relay").withFromAddressId(mine.getValueInteger("id")));
      assertThat(ok.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord stored = EmailStore.get(EmailTables.MESSAGE, ok.messageId(), null);
      assertThat(stored.getValueString("fromAddress")).contains("sales@example.com").contains("Sales Desk");
      assertThat(stored.getValueInteger("fromAddressId")).isEqualTo(mine.getValueInteger("id"));
      EmailSendResult wrong = EmailService.send(request("relay").withFromAddressId(theirs.getValueInteger("id")));
      assertThat(wrong.status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(wrong.reasons()).containsExactly("That from address is not set up for provider relay");
      EmailSendResult disabled = EmailService.send(request("relay").withFromAddressId(off.getValueInteger("id")));
      assertThat(disabled.status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(disabled.reasons()).containsExactly("That from address is not set up for provider relay");
      assertThat(EmailService.send(request("relay").withFromAddressId(4242)).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(EmailService.send(request("relay").withFromAddressId(mine.getValueInteger("id")).withFrom("other@example.com")).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }

   /*******************************************************************************
    ** Once a provider has rows an explicit from text must match an enabled one.
    *******************************************************************************/
   @Test
   void fromTextMustMatchRowsWhenTheyExist() throws Exception
   {
      QRecord listed = H2Support.insert(EmailTables.SENDER, row(relay, "sales@example.com"));
      H2Support.insert(EmailTables.SENDER, row(relay, "old@example.com").withValue("enabled", false));
      EmailSendResult ok = EmailService.send(request("relay").withFrom("Sales Team <SALES@example.com>"));
      assertThat(ok.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      QRecord stored = EmailStore.get(EmailTables.MESSAGE, ok.messageId(), null);
      assertThat(stored.getValueString("fromAddress")).contains("Sales Team");
      assertThat(stored.getValueInteger("fromAddressId")).isEqualTo(listed.getValueInteger("id"));
      EmailSendResult unlisted = EmailService.send(request("relay").withFrom("random@example.com"));
      assertThat(unlisted.status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(unlisted.reasons()).containsExactly("That from address is not set up for provider relay");
      assertThat(EmailService.send(request("relay").withFrom("old@example.com")).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(EmailService.send(request("relay").withFrom("sender@example.com")).status()).isEqualTo(EmailSendResult.Status.REFUSED);
      assertThat(EmailService.send(request("relay").withFrom("not an address")).status()).isEqualTo(EmailSendResult.Status.REFUSED);
   }

   /*******************************************************************************
    ** Without rows, behavior is unchanged: any from text, and the provider's from address and name.
    *******************************************************************************/
   @Test
   void fallsBackToProviderFromAddress() throws Exception
   {
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", relay.getValue("id")).withValue("fromName", "Relay Team"));
      EmailSendResult plain = EmailService.send(request("relay"));
      QRecord stored = EmailStore.get(EmailTables.MESSAGE, plain.messageId(), null);
      assertThat(stored.getValueString("fromAddress")).contains("sender@example.com").contains("Relay Team");
      assertThat(stored.getValue("fromAddressId")).isNull();
      EmailSendResult explicit = EmailService.send(request("relay").withFrom("anyone@example.org"));
      assertThat(explicit.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      assertThat(EmailStore.get(EmailTables.MESSAGE, explicit.messageId(), null).getValueString("fromAddress")).isEqualTo("anyone@example.org");
      H2Support.insert(EmailTables.SENDER, row(other, "help@example.com"));
      EmailSendResult unaffected = EmailService.send(request("relay").withFrom("anyone@example.org"));
      assertThat(unaffected.status()).isEqualTo(EmailSendResult.Status.QUEUED);
   }

   /*******************************************************************************
    ** An enabled default row wins over the provider's own from address; a disabled one does not.
    *******************************************************************************/
   @Test
   void defaultRowWins() throws Exception
   {
      QRecord preferred = H2Support.insert(EmailTables.SENDER, row(relay, "team@example.com").withValue("displayName", "The Team").withValue("isDefault", true));
      EmailSendResult result = EmailService.send(request("relay"));
      QRecord stored = EmailStore.get(EmailTables.MESSAGE, result.messageId(), null);
      assertThat(stored.getValueString("fromAddress")).contains("team@example.com").contains("The Team");
      assertThat(stored.getValueInteger("fromAddressId")).isEqualTo(preferred.getValueInteger("id"));
      SystemWrites.updateOne(EmailTables.SENDER, new QRecord().withValue("id", preferred.getValue("id")).withValue("enabled", false));
      QRecord back = EmailStore.get(EmailTables.MESSAGE, EmailService.send(request("relay")).messageId(), null);
      assertThat(back.getValueString("fromAddress")).isEqualTo("sender@example.com");
      assertThat(back.getValue("fromAddressId")).isNull();
   }

   /*******************************************************************************
    ** A resend keeps the original sender row.
    *******************************************************************************/
   @Test
   void resendKeepsSenderRow() throws Exception
   {
      QRecord mine = H2Support.insert(EmailTables.SENDER, row(relay, "sales@example.com"));
      EmailSendResult sent = EmailService.send(request("relay").withFromAddressId(mine.getValueInteger("id")));
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", sent.messageId()).withValue("status", "DEAD_LETTER"));
      EmailSendResult again = EmailService.resend(sent.messageId());
      assertThat(again.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      assertThat(EmailStore.get(EmailTables.MESSAGE, again.messageId(), null).getValueInteger("fromAddressId")).isEqualTo(mine.getValueInteger("id"));
   }

   /*******************************************************************************
    ** The templated and ad hoc send processes accept providerId and fromAddressId.
    *******************************************************************************/
   @Test
   void sendProcessAcceptsProviderAndFromAddressIds() throws Exception
   {
      QRecord mine = H2Support.insert(EmailTables.SENDER, row(relay, "sales@example.com"));
      QRecord theirs = H2Support.insert(EmailTables.SENDER, row(other, "help@example.com"));
      Map<String, Serializable> values = Map.of("to", "person@example.net", "subject", "Hi", "textBody", "Body", "providerId", relay.getValueInteger("id"), "fromAddressId", mine.getValueInteger("id"));
      var input = new RunProcessInput();
      input.setProcessName(EmailProcesses.SEND_AD_HOC);
      values.forEach(input::addValue);
      assertThat(new RunProcessAction().execute(input).getValues().get("status")).isEqualTo("QUEUED");
      var wrong = new RunProcessInput();
      wrong.setProcessName(EmailProcesses.SEND_AD_HOC);
      values.forEach(wrong::addValue);
      wrong.addValue("fromAddressId", theirs.getValueInteger("id"));
      assertThat(new RunProcessAction().execute(wrong).getValues().get("status")).isEqualTo("REFUSED");
      var named = new RunProcessInput();
      named.setProcessName(EmailProcesses.SEND_AD_HOC);
      values.forEach(named::addValue);
      named.addValue("providerName", "relay");
      assertThat(new RunProcessAction().execute(named).getValues().get("status")).isEqualTo("QUEUED");
      var mismatch = new RunProcessInput();
      mismatch.setProcessName(EmailProcesses.SEND_AD_HOC);
      values.forEach(mismatch::addValue);
      mismatch.addValue("providerName", "other");
      assertThatThrownBy(() -> new RunProcessAction().execute(mismatch)).hasMessageContaining("providerId does not match");
      var missing = new RunProcessInput();
      missing.setProcessName(EmailProcesses.SEND_AD_HOC);
      values.forEach(missing::addValue);
      missing.addValue("providerId", 4242);
      assertThatThrownBy(() -> new RunProcessAction().execute(missing)).hasMessageContaining("providerId does not match");
   }

   /*******************************************************************************
    ** Send Test Email takes an optional from address that must belong to the provider.
    *******************************************************************************/
   @Test
   void sendTestUsesFromAddress() throws Exception
   {
      QRecord mine = H2Support.insert(EmailTables.SENDER, row(relay, "sales@example.com"));
      QRecord theirs = H2Support.insert(EmailTables.SENDER, row(other, "help@example.com"));
      var wrong = ProcessRunner.runWithValues(EmailProcesses.SEND_TEST, Map.of("toAddress", "test@example.com", "fromAddressId", theirs.getValueInteger("id")), relay.getValue("id"));
      assertThat(wrong.result()).anyMatch(s -> s.contains("REFUSED"));
      var ok = ProcessRunner.runWithValues(EmailProcesses.SEND_TEST, Map.of("toAddress", "test@example.com", "fromAddressId", mine.getValueInteger("id")), relay.getValue("id"));
      assertThat(ok.result()).anyMatch(s -> s.contains("FAILED") || s.contains("SENT") || s.contains("RETRY") || s.contains("QUEUED"));
      assertThat(H2Support.all(EmailTables.MESSAGE)).anyMatch(m -> mine.getValue("id").equals(m.getValue("fromAddressId")));
   }

   // ----- contingent possible-value pairs -----

   /*******************************************************************************
    ** Assert the filter on a field and that a possible-value search with the parent
    ** value returns only that parent's rows. The search runs through core's action.
    *******************************************************************************/
   private static void assertContingent(QFieldMetaData field, String inputName, Serializable parentValue, List<Serializable> expected, List<Serializable> excluded) throws Exception
   {
      assertThat(field.getPossibleValueSourceFilter()).isNotNull();
      var filter = field.getPossibleValueSourceFilter().clone();
      assertThat(filter.toString()).contains("${input." + inputName + "}");
      filter.interpretValues(Map.of("input", Map.of(inputName, parentValue), "processValues", Map.<String, Serializable>of()), PossibleValueSearchFilterUseCase.FORM);
      var found = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput().withPossibleValueSourceName(field.getPossibleValueSourceName()).withDefaultQueryFilter(filter));
      List<String> ids = found.getResults().stream().map(v -> v.getId().toString()).toList();
      assertThat(ids).containsAll(expected.stream().map(String::valueOf).toList());
      assertThat(ids).doesNotContainAnyElementsOf(excluded.stream().map(String::valueOf).toList());
   }

   /*******************************************************************************
    ** One row each for the two providers.
    *******************************************************************************/
   private QRecord[] senders() throws Exception
   {
      return (new QRecord[] { H2Support.insert(EmailTables.SENDER, row(relay, "a@example.com")), H2Support.insert(EmailTables.SENDER, row(other, "b@example.com")) });
   }

   /*******************************************************************************
    ** Templated send: fromAddressId follows providerId.
    *******************************************************************************/
   @Test
   void templatedSendFromAddressFollowsProvider() throws Exception
   {
      QRecord[] rows = senders();
      QFieldMetaData field = QContext.getQInstance().getProcess(EmailProcesses.SEND_TEMPLATED).getBackendStep("send").getInputMetaData().getFieldThrowing("fromAddressId");
      assertThat(field.getPossibleValueSourceName()).isEqualTo(EmailTables.SENDER);
      assertContingent(field, "providerId", relay.getValueInteger("id"), List.of(rows[0].getValueInteger("id")), List.of(rows[1].getValueInteger("id")));
      assertContingent(field, "providerId", other.getValueInteger("id"), List.of(rows[1].getValueInteger("id")), List.of(rows[0].getValueInteger("id")));
      assertThat(QContext.getQInstance().getProcess(EmailProcesses.SEND_TEMPLATED).getBackendStep("send").getInputMetaData().getFieldThrowing("providerId").getPossibleValueSourceName()).isEqualTo(EmailTables.PROVIDER);
   }

   /*******************************************************************************
    ** Ad hoc send: fromAddressId follows providerId.
    *******************************************************************************/
   @Test
   void adHocSendFromAddressFollowsProvider() throws Exception
   {
      QRecord[] rows = senders();
      QFieldMetaData field = QContext.getQInstance().getProcess(EmailProcesses.SEND_AD_HOC).getBackendStep("send").getInputMetaData().getFieldThrowing("fromAddressId");
      assertContingent(field, "providerId", relay.getValueInteger("id"), List.of(rows[0].getValueInteger("id")), List.of(rows[1].getValueInteger("id")));
   }

   /*******************************************************************************
    ** Send Test Email: fromAddressId follows the provider being acted on, which a
    ** record action's form only gets from the prepare step's process value.
    *******************************************************************************/
   @Test
   void sendTestFromAddressFollowsActedOnProvider() throws Exception
   {
      QRecord[] rows = senders();
      QFieldMetaData field = QContext.getQInstance().getProcess(EmailProcesses.SEND_TEST).getFrontendStep("input").getFormFields().stream()
         .filter(f -> "fromAddressId".equals(f.getName())).findFirst().orElseThrow();
      assertContingent(field, "id", relay.getValueInteger("id"), List.of(rows[0].getValueInteger("id")), List.of(rows[1].getValueInteger("id")));
      var input = new RunProcessInput();
      input.setProcessName(EmailProcesses.SEND_TEST);
      input.addValue("recordsParam", "recordIds");
      input.addValue("recordIds", String.valueOf(other.getValue("id")));
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      var seeded = new RunProcessAction().execute(input).getValues().get(EmailProcesses.PROVIDER_FILTER_VALUE);
      assertThat(seeded).isEqualTo(other.getValue("id"));
      var filter = field.getPossibleValueSourceFilter().clone();
      filter.interpretValues(Map.of("input", Map.<String, Serializable>of(), "processValues", Map.of(EmailProcesses.PROVIDER_FILTER_VALUE, (Serializable) seeded)), PossibleValueSearchFilterUseCase.FORM);
      var found = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput().withPossibleValueSourceName(EmailTables.SENDER).withDefaultQueryFilter(filter));
      assertThat(found.getResults().stream().map(v -> v.getId().toString()).toList()).containsExactly(rows[1].getValueString("id"));
      var several = new RunProcessInput();
      several.setProcessName(EmailProcesses.SEND_TEST);
      several.addValue("recordsParam", "recordIds");
      several.addValue("recordIds", "1,2");
      several.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      assertThat(new RunProcessAction().execute(several).getValues()).doesNotContainKey(EmailProcesses.PROVIDER_FILTER_VALUE);
      var none = new RunProcessInput();
      none.setProcessName(EmailProcesses.SEND_TEST);
      none.addValue("recordsParam", "recordIds");
      none.addValue("recordIds", "x");
      none.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      assertThat(new RunProcessAction().execute(none).getValues()).doesNotContainKey(EmailProcesses.PROVIDER_FILTER_VALUE);
   }

   /*******************************************************************************
    ** Message: fromAddressId follows providerId, and is read-only.
    *******************************************************************************/
   @Test
   void messageFromAddressFollowsProvider() throws Exception
   {
      QRecord[] rows = senders();
      QFieldMetaData field = QContext.getQInstance().getTable(EmailTables.MESSAGE).getField("fromAddressId");
      assertThat(field.getIsEditable()).isFalse();
      assertContingent(field, "providerId", relay.getValueInteger("id"), List.of(rows[0].getValueInteger("id")), List.of(rows[1].getValueInteger("id")));
   }

   /*******************************************************************************
    ** Attempt and event: the message follows the provider.
    *******************************************************************************/
   @Test
   void attemptAndEventMessageFollowProvider() throws Exception
   {
      QRecord fromRelay = EmailStore.get(EmailTables.MESSAGE, EmailService.send(request("relay")).messageId(), null);
      QRecord fromOther = EmailStore.get(EmailTables.MESSAGE, EmailService.send(request("other")).messageId(), null);
      for(String table : List.of(EmailTables.ATTEMPT, EmailTables.EVENT))
      {
         QFieldMetaData field = QContext.getQInstance().getTable(table).getField("messageId");
         assertContingent(field, "providerId", relay.getValueInteger("id"), List.of(fromRelay.getValueInteger("id")), List.of(fromOther.getValueInteger("id")));
         assertContingent(field, "providerId", other.getValueInteger("id"), List.of(fromOther.getValueInteger("id")), List.of(fromRelay.getValueInteger("id")));
      }
   }

   /*******************************************************************************
    ** Suppression: the source event follows the source message.
    *******************************************************************************/
   @Test
   void suppressionSourceEventFollowsSourceMessage() throws Exception
   {
      QRecord first = EmailStore.get(EmailTables.MESSAGE, EmailService.send(request("relay")).messageId(), null);
      QRecord second = EmailStore.get(EmailTables.MESSAGE, EmailService.send(request("other")).messageId(), null);
      QRecord e1 = SystemWrites.insertOne(EmailTables.EVENT, new QRecord().withValue("messageId", first.getValue("id")).withValue("eventType", "BOUNCED").withValue("eventKey", "k1"));
      QRecord e2 = SystemWrites.insertOne(EmailTables.EVENT, new QRecord().withValue("messageId", second.getValue("id")).withValue("eventType", "BOUNCED").withValue("eventKey", "k2"));
      QFieldMetaData field = QContext.getQInstance().getTable(EmailTables.SUPPRESSION).getField("sourceEventId");
      assertContingent(field, "sourceMessageId", first.getValueInteger("id"), List.of(e1.getValueInteger("id")), List.of(e2.getValueInteger("id")));
      assertContingent(field, "sourceMessageId", second.getValueInteger("id"), List.of(e2.getValueInteger("id")), List.of(e1.getValueInteger("id")));
   }

   /*******************************************************************************
    ** Fields without a parent scope stay unfiltered.
    *******************************************************************************/
   @Test
   void independentRelationshipsStayUnfiltered() throws Exception
   {
      var instance = QContext.getQInstance();
      assertThat(instance.getTable(EmailTables.TEMPLATE).getField("brandId").getPossibleValueSourceFilter()).isNull();
      assertThat(instance.getTable(EmailTables.MESSAGE).getField("brandId").getPossibleValueSourceFilter()).isNull();
      assertThat(instance.getTable(EmailTables.MESSAGE).getField("providerId").getPossibleValueSourceFilter()).isNull();
      assertThat(instance.getTable(EmailTables.MESSAGE).getField("templateId").getPossibleValueSourceFilter()).isNull();
      assertThat(instance.getTable(EmailTables.VERSION).getField("templateId").getPossibleValueSourceFilter()).isNull();
      assertThat(instance.getTable(EmailTables.INBOX).getField("providerId").getPossibleValueSourceFilter()).isNull();
      assertThat(instance.getTable(EmailTables.SENDER).getField("providerId").getPossibleValueSourceFilter()).isNull();
   }
}
