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


import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Provider payloads become small recipient-level events, never message bodies.
 *******************************************************************************/
public final class EmailEventParser
{
   /*******************************************************************************
    ** Provider identifiers remain scoped to the provider during database matching.
    *******************************************************************************/
   public record Event(String externalId, String messageId, String providerMessageId, String type, String bounceType,
      String recipient, Instant occurred, String detail, String url)
   {
   }

   /*******************************************************************************
    ** Parse one provider request, including all SendGrid events and SES recipients.
    *******************************************************************************/
   public List<Event> parse(String kind, String body, Instant received)
   {
      List<Event> events = new ArrayList<>();
      switch(kind)
      {
         case "SES" -> ses(new JSONObject(body), received, events);
         case "POSTMARK" -> postmark(new JSONObject(body), received, events);
         case "MAILGUN" -> mailgun(new JSONObject(body).getJSONObject("event-data"), received, events);
         case "SENDGRID" ->
         {
            JSONArray batch = new JSONArray(body);
            for(int i = 0; i < batch.length(); i++)
            {
               sendgrid(batch.getJSONObject(i), received, events);
            }
         }
         default -> throw new IllegalArgumentException("Unsupported email event kind");
      }
      return (events);
   }

   /*******************************************************************************
    ** SES recipients live under the specific event, with mail.destination fallback.
    *******************************************************************************/
   private static void ses(JSONObject envelope, Instant received, List<Event> events)
   {
      if(!"Notification".equals(envelope.getString("Type")))
      {
         return;
      }
      JSONObject payload = new JSONObject(envelope.getString("Message"));
      String rawType = payload.optString("eventType", payload.optString("notificationType"));
      String type = switch(rawType)
      {
         case "Delivery" -> "DELIVERED";
         case "Bounce" -> "BOUNCED";
         case "Complaint" -> "COMPLAINED";
         case "Reject" -> "REJECTED";
         case "DeliveryDelay" -> "DEFERRED";
         case "Open" -> "OPENED";
         case "Click" -> "CLICKED";
         default -> null;
      };
      if(type == null)
      {
         return;
      }
      JSONObject mail = payload.getJSONObject("mail");
      String key = "DeliveryDelay".equals(rawType) ? "deliveryDelay" : rawType.toLowerCase(Locale.ROOT);
      JSONObject data = object(payload, key);
      String bounce = "BOUNCED".equals(type) ? switch(data.optString("bounceType"))
      {
         case "Permanent" -> "HARD";
         case "Transient" -> "SOFT";
         default -> "UNDETERMINED";
      } : null;
      String messageId = null;
      JSONArray headers = mail.optJSONArray("headers");
      if(headers != null)
      {
         for(int i = 0; i < headers.length(); i++)
         {
            JSONObject entry = headers.getJSONObject(i);
            if("X-QQQ-Email-Id".equalsIgnoreCase(entry.optString("name")))
            {
               messageId = entry.optString("value", null);
            }
         }
      }
      JSONArray recipients = data.optJSONArray(switch(type)
      {
         case "BOUNCED" -> "bouncedRecipients";
         case "COMPLAINED" -> "complainedRecipients";
         case "DEFERRED" -> "delayedRecipients";
         default -> "recipients";
      });
      if(recipients == null)
      {
         recipients = mail.getJSONArray("destination");
      }
      for(int i = 0; i < recipients.length(); i++)
      {
         Object recipient = recipients.get(i);
         String address = recipient instanceof JSONObject row ? row.getString("emailAddress") : recipient.toString();
         String reason = recipient instanceof JSONObject row ? row.optString("diagnosticCode", data.optString("bounceSubType", null))
            : data.optString("reason", data.optString("smtpResponse", data.optString("delayType", data.optString("complaintFeedbackType", null))));
         events.add(new Event(envelope.getString("MessageId"), messageId, mail.getString("messageId"), type, bounce, address,
            time(data.opt("timestamp"), received), detail(reason), "CLICKED".equals(type) ? data.optString("link", null) : null));
      }
   }

   /*******************************************************************************
    ** Postmark subscription reactivation is informational, never an unsubscribe.
    *******************************************************************************/
   private static void postmark(JSONObject data, Instant received, List<Event> events)
   {
      String type = switch(data.getString("RecordType"))
      {
         case "Delivery" -> "DELIVERED";
         case "Bounce" -> "BOUNCED";
         case "SpamComplaint" -> "COMPLAINED";
         case "Open" -> "OPENED";
         case "Click" -> "CLICKED";
         case "SubscriptionChange" -> data.optBoolean("SuppressSending", false) ? "UNSUBSCRIBED" : null;
         default -> null;
      };
      if(type == null)
      {
         return;
      }
      String bounce = "BOUNCED".equals(type) ? switch(data.optString("Type"))
      {
         case "HardBounce", "SpamNotification", "SpamComplaint", "BadEmailAddress", "ManuallyDeactivated" -> "HARD";
         case "SoftBounce", "Transient", "DnsError", "MailboxFull" -> "SOFT";
         default -> "UNDETERMINED";
      } : null;
      Object date = data.opt("DeliveredAt");
      for(String field : List.of("BouncedAt", "ReceivedAt", "ChangedAt"))
      {
         if(data.has(field))
         {
            date = data.get(field);
         }
      }
      String id = data.has("ID") ? data.get("RecordType") + ":" + data.get("ID") : fingerprint(data);
      events.add(new Event(id, null, data.optString("MessageID", null), type, bounce,
         data.optString("Recipient", data.optString("Email", null)), time(date, received),
         detail(data.optString("Description", data.optString("Details", data.optString("SuppressionReason", null)))),
         "CLICKED".equals(type) ? data.optString("OriginalLink", null) : null));
   }

   /*******************************************************************************
    ** SendGrid retains the provider message id prefix before its delivery suffix.
    *******************************************************************************/
   private static void sendgrid(JSONObject data, Instant received, List<Event> events)
   {
      String type = switch(data.getString("event"))
      {
         case "delivered" -> "DELIVERED";
         case "bounce" -> "BOUNCED";
         case "dropped" -> "REJECTED";
         case "deferred" -> "DEFERRED";
         case "spamreport" -> "COMPLAINED";
         case "open" -> "OPENED";
         case "click" -> "CLICKED";
         case "unsubscribe", "group_unsubscribe" -> "UNSUBSCRIBED";
         default -> null;
      };
      if(type == null)
      {
         return;
      }
      String bounce = "BOUNCED".equals(type) ? ("blocked".equals(data.optString("type")) ? "SOFT" : "HARD") : null;
      String providerMessage = data.optString("sg_message_id", null);
      if(providerMessage != null && providerMessage.contains("."))
      {
         providerMessage = providerMessage.substring(0, providerMessage.indexOf('.'));
      }
      events.add(new Event(data.optString("sg_event_id", fingerprint(data)),
         data.optString("qqq_email_id", object(data, "custom_args").optString("qqq_email_id", null)), providerMessage, type, bounce,
         data.optString("email", null), time(data.opt("timestamp"), received), detail(data.optString("reason", data.optString("response", null))),
         "CLICKED".equals(type) ? data.optString("url", null) : null));
   }

   /*******************************************************************************
    ** Mailgun MIME ids are normalized identically to the outbound transport.
    *******************************************************************************/
   private static void mailgun(JSONObject data, Instant received, List<Event> events)
   {
      String type = switch(data.getString("event"))
      {
         case "delivered" -> "DELIVERED";
         case "failed" -> "BOUNCED";
         case "complained" -> "COMPLAINED";
         case "opened" -> "OPENED";
         case "clicked" -> "CLICKED";
         case "unsubscribed" -> "UNSUBSCRIBED";
         default -> null;
      };
      if(type == null)
      {
         return;
      }
      String bounce = "BOUNCED".equals(type) ? switch(data.optString("severity"))
      {
         case "permanent" -> "HARD";
         case "temporary" -> "SOFT";
         default -> "UNDETERMINED";
      } : null;
      JSONObject variables = object(data, "user-variables");
      String providerMessage = object(object(data, "message"), "headers").optString("message-id", null);
      if(providerMessage != null)
      {
         providerMessage = providerMessage.replaceAll("^<|>$", "");
      }
      events.add(new Event(data.optString("id", fingerprint(data)), variables.optString("qqq_email_id", variables.optString("v:qqq_email_id", null)),
         providerMessage, type, bounce, data.optString("recipient", null), time(data.opt("timestamp"), received),
         detail(data.optString("reason", object(data, "delivery-status").optString("message", null))), "CLICKED".equals(type) ? data.optString("url", null) : null));
   }

   /*******************************************************************************
    ** Optional nested objects contain no executable data.
    *******************************************************************************/
   private static JSONObject object(JSONObject parent, String key)
   {
      JSONObject value = parent.optJSONObject(key);
      return (value == null ? new JSONObject() : value);
   }

   /*******************************************************************************
    ** Fall back to receipt time only when the provider supplied no timestamp.
    *******************************************************************************/
   private static Instant time(Object value, Instant fallback)
   {
      if(value == null || JSONObject.NULL.equals(value))
      {
         return (fallback);
      }
      if(value instanceof Number number)
      {
         return (Instant.ofEpochMilli((long) (number.doubleValue() * 1000)));
      }
      return (OffsetDateTime.parse(value.toString()).toInstant());
   }

   /*******************************************************************************
    ** Fallback identity for providers without a distinct event identifier.
    *******************************************************************************/
   private static String fingerprint(JSONObject data)
   {
      return (WebhookVerification.hash(data.toString().getBytes(StandardCharsets.UTF_8)));
   }

   /*******************************************************************************
    ** Mask complete addresses before truncation, including quoted local parts.
    *******************************************************************************/
   public static String detail(String reason)
   {
      if(reason == null)
      {
         return (null);
      }
      String masked = reason.replaceAll("(?i)(?:\"[^\"]*\"|[^\\s<>(),;:]+)@[^\\s<>(),;:]+", "[address]");
      return (masked.substring(0, Math.min(500, masked.length())));
   }
}
