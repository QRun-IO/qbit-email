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

package com.kingsrook.qbits.email.tracking;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/*******************************************************************************
 ** Provider-specific normalization contracts use realistic fixture envelopes.
 *******************************************************************************/
class EmailEventParserTest
{
   /*******************************************************************************
    ** All SES event types, with notificationType fallback and header correlation.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({"Delivery,DELIVERED", "Bounce,BOUNCED", "Complaint,COMPLAINED", "Reject,REJECTED", "DeliveryDelay,DEFERRED", "Open,OPENED", "Click,CLICKED"})
   void sesTypes(String raw, String expected) throws Exception
   {
      JSONObject envelope = new JSONObject(resource("/webhooks/ses-bounce.json"));
      JSONObject body = new JSONObject(envelope.getString("Message"));
      body.remove("eventType");
      body.put("notificationType", raw);
      String field = "DeliveryDelay".equals(raw) ? "deliveryDelay" : raw.toLowerCase();
      body.put(field, new JSONObject().put("timestamp", NOW.toString()).put("link", "https://example.com/page").put("bounceType", "Transient"));
      envelope.put("Message", body.toString());
      var events = new EmailEventParser().parse("SES", envelope.toString(), NOW);
      assertThat(events).hasSize(2).allSatisfy(event ->
      {
         assertThat(event.type()).isEqualTo(expected);
         assertThat(event.occurred()).isEqualTo(NOW);
         assertThat(event.messageId()).isEqualTo("1");
         assertThat(event.url()).isEqualTo("CLICKED".equals(expected) ? "https://example.com/page" : null);
      });
      if("BOUNCED".equals(expected))
      {
         assertThat(events.getFirst().bounceType()).isEqualTo("SOFT");
      }
   }

   /*******************************************************************************
    ** Postmark mappings keep subscriber reactivation out of suppression.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({"Delivery,DELIVERED", "Bounce,BOUNCED", "SpamComplaint,COMPLAINED", "Open,OPENED", "Click,CLICKED", "SubscriptionChange,UNSUBSCRIBED"})
   void postmarkTypes(String raw, String expected) throws Exception
   {
      JSONObject body = new JSONObject(resource("/webhooks/postmark-delivery.json")).put("RecordType", raw).put("SuppressSending", true)
         .put("OriginalLink", "https://example.com/page").put("Type", "Transient").put("ChangedAt", NOW.toString());
      var event = new EmailEventParser().parse("POSTMARK", body.toString(), NOW).getFirst();
      assertThat(event.type()).isEqualTo(expected);
      assertThat(event.occurred()).isEqualTo(NOW);
      assertThat(event.providerMessageId()).isEqualTo("provider-message-1");
      assertThat(event.url()).isEqualTo("CLICKED".equals(expected) ? "https://example.com/page" : null);
      if("BOUNCED".equals(expected))
      {
         assertThat(event.bounceType()).isEqualTo("SOFT");
      }
   }

   /*******************************************************************************
    ** SendGrid event mappings retain reason and provider-message prefix correlation.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({"delivered,DELIVERED", "bounce,BOUNCED", "dropped,REJECTED", "deferred,DEFERRED", "spamreport,COMPLAINED", "open,OPENED", "click,CLICKED", "unsubscribe,UNSUBSCRIBED"})
   void sendgridTypes(String raw, String expected) throws Exception
   {
      JSONObject body = new JSONArray(resource("/webhooks/sendgrid-batch.json")).getJSONObject(0).put("event", raw)
         .put("custom_args", new JSONObject().put("qqq_email_id", "25")).put("url", "https://example.com/page");
      var event = new EmailEventParser().parse("SENDGRID", new JSONArray().put(body).toString(), NOW).getFirst();
      assertThat(event.type()).isEqualTo(expected);
      assertThat(event.messageId()).isEqualTo("25");
      assertThat(event.providerMessageId()).isEqualTo("provider-message-1");
      assertThat(event.url()).isEqualTo("CLICKED".equals(expected) ? "https://example.com/page" : null);
   }

   /*******************************************************************************
    ** Mailgun's nested MIME id and both documented variable spellings correlate.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({"delivered,DELIVERED", "failed,BOUNCED", "complained,COMPLAINED", "opened,OPENED", "clicked,CLICKED", "unsubscribed,UNSUBSCRIBED"})
   void mailgunTypes(String raw, String expected) throws Exception
   {
      JSONObject body = new JSONObject(resource("/webhooks/mailgun-failed.json"));
      body.getJSONObject("event-data").put("event", raw).put("severity", "temporary").put("url", "https://example.com/page")
         .put("user-variables", new JSONObject().put("v:qqq_email_id", "25"));
      var event = new EmailEventParser().parse("MAILGUN", body.toString(), NOW).getFirst();
      assertThat(event.type()).isEqualTo(expected);
      assertThat(event.messageId()).isEqualTo("25");
      assertThat(event.providerMessageId()).isEqualTo("provider-message-1");
      assertThat(event.url()).isEqualTo("CLICKED".equals(expected) ? "https://example.com/page" : null);
      if("BOUNCED".equals(expected))
      {
         assertThat(event.bounceType()).isEqualTo("SOFT");
      }
   }

   /*******************************************************************************
    ** Unknown events are ignored; incomplete known events fail for bounded retry.
    *******************************************************************************/
   @Test
   void unknownEventsAndMissingFields() throws Exception
   {
      var parser = new EmailEventParser();
      assertThat(parser.parse("SES", "{\"Type\":\"SubscriptionConfirmation\"}", NOW)).isEmpty();
      assertThat(parser.parse("SES", new JSONObject().put("Type", "Notification").put("Message", "{\"eventType\":\"Send\"}").toString(), NOW)).isEmpty();
      assertThat(parser.parse("POSTMARK", "{\"RecordType\":\"Unknown\"}", NOW)).isEmpty();
      assertThat(parser.parse("SENDGRID", "[{\"event\":\"processed\"}]", NOW)).isEmpty();
      assertThat(parser.parse("MAILGUN", "{\"event-data\":{\"event\":\"accepted\"}}", NOW)).isEmpty();
      assertThatThrownBy(() -> parser.parse("POSTMARK", "invalid", NOW)).isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> parser.parse("OTHER", "{}", NOW)).isInstanceOf(IllegalArgumentException.class);
      for(String kind : List.of("SES", "POSTMARK", "SENDGRID", "MAILGUN"))
      {
         assertThatThrownBy(() -> parser.parse(kind, "{}", NOW)).isInstanceOf(RuntimeException.class);
      }
      assertThat(EmailEventParser.detail("550 \"name with spaces\"@example.com unavailable " + "x".repeat(600))).hasSize(500).doesNotContain("example.com", "name with spaces");
      assertThat(EmailEventParser.detail("bad person+tag@example.com and other@example.org")).isEqualTo("bad [address] and [address]");
   }

   /*******************************************************************************
    ** Explicit uncertain bounce types remain uncertain, not hard suppression.
    *******************************************************************************/
   @Test
   void undeterminedBounceTypes() throws Exception
   {
      var parser = new EmailEventParser();
      JSONObject ses = new JSONObject(resource("/webhooks/ses-bounce.json"));
      JSONObject inner = new JSONObject(ses.getString("Message"));
      inner.getJSONObject("bounce").put("bounceType", "Undetermined");
      assertThat(parser.parse("SES", ses.put("Message", inner.toString()).toString(), NOW).getFirst().bounceType()).isEqualTo("UNDETERMINED");
      JSONObject postmark = new JSONObject(resource("/webhooks/postmark-bounce.json")).put("Type", "Unknown");
      assertThat(parser.parse("POSTMARK", postmark.toString(), NOW).getFirst().bounceType()).isEqualTo("UNDETERMINED");
      JSONObject mailgun = new JSONObject(resource("/webhooks/mailgun-failed.json"));
      mailgun.getJSONObject("event-data").put("severity", "unknown");
      assertThat(parser.parse("MAILGUN", mailgun.toString(), NOW).getFirst().bounceType()).isEqualTo("UNDETERMINED");
   }
}
