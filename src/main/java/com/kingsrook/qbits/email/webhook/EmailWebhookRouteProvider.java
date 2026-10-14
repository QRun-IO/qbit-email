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

package com.kingsrook.qbits.email.webhook;


import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.tracking.EmailTrackingStore;
import com.kingsrook.qbits.email.tracking.EmailUnsubscribe;
import com.kingsrook.qbits.email.tracking.MailgunVerifier;
import com.kingsrook.qbits.email.tracking.PostmarkVerifier;
import com.kingsrook.qbits.email.tracking.SendGridVerifier;
import com.kingsrook.qbits.email.tracking.SnsVerifier;
import com.kingsrook.qbits.email.tracking.WebhookVerification;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;
import com.kingsrook.qqq.middleware.javalin.QJavalinMetaData;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Authenticated provider ingress and signed public opt-out outside the UI API.
 ** No bodies, tokens, addresses or exception diagnostics are logged here.
 *******************************************************************************/
public class EmailWebhookRouteProvider implements QJavalinRouteProviderInterface
{
   private static final int MAX_BODY = 1024 * 1024;
   private QInstance instance;
   private final SnsVerifier sns;

   /*******************************************************************************
    ** Middleware constructs the default route provider.
    *******************************************************************************/
   public EmailWebhookRouteProvider()
   {
      this(new SnsVerifier());
   }

   /*******************************************************************************
    ** Inject the existing HTTP seam through the SNS verifier for local tests.
    *******************************************************************************/
   public EmailWebhookRouteProvider(SnsVerifier sns)
   {
      this.sns = sns;
   }

   /*******************************************************************************
    ** Idempotent registration even when a producer is run repeatedly.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      QJavalinMetaData metadata = QJavalinMetaData.ofOrWithNew(instance);
      if(CollectionUtils.nonNullList(metadata.getAdditionalRouteProviderReferences()).stream()
         .noneMatch(reference -> reference != null && EmailWebhookRouteProvider.class.getName().equals(reference.getName())))
      {
         metadata.withAdditionalRouteProviderReference(new QCodeReference(EmailWebhookRouteProvider.class));
      }
   }

   /*******************************************************************************
    ** Supplied by the host middleware.
    *******************************************************************************/
   @Override
   public void setQInstance(QInstance instance)
   {
      this.instance = instance;
   }

   /*******************************************************************************
    ** Specific unsubscribe routes precede the provider wildcard route.
    *******************************************************************************/
   @Override
   public void acceptJavalinConfig(JavalinConfig config)
   {
      String base = EmailRuntime.config(instance).getWebhookBasePath();
      config.routes.get(base + "/unsubscribe/{token}", this::unsubscribe);
      config.routes.post(base + "/unsubscribe/{token}", this::unsubscribe);
      config.routes.post(base + "/{kind}/{providerName}", this::receive);
   }

   /*******************************************************************************
    ** Bound streaming input even when Content-Length is absent or dishonest.
    *******************************************************************************/
   private static byte[] body(Context context) throws java.io.IOException
   {
      if(context.req().getContentLengthLong() > MAX_BODY)
      {
         respond(context, 413, "error", "Payload too large");
         return (null);
      }
      byte[] bytes = context.req().getInputStream().readNBytes(MAX_BODY + 1);
      if(bytes.length > MAX_BODY)
      {
         respond(context, 413, "error", "Payload too large");
         return (null);
      }
      return (bytes);
   }

   /*******************************************************************************
    ** Authenticate before storing. Missing configuration and DB outages are retryable.
    *******************************************************************************/
   private void receive(Context context)
   {
      CapturedContext previous = QContext.capture();
      try
      {
         QContext.init(instance, new com.kingsrook.qqq.backend.core.model.session.QSystemUserSession(instance));
         byte[] bytes = body(context);
         if(bytes == null)
         {
            return;
         }
         String kind = context.pathParam("kind").toUpperCase(Locale.ROOT);
         List<QRecord> providers = SystemWrites.query(EmailTables.PROVIDER, EmailStore.equal("name", context.pathParam("providerName")));
         if(providers.size() != 1 || !Boolean.TRUE.equals(providers.getFirst().getValueBoolean("enabled"))
            || !kind.equals(providers.getFirst().getValueString("kind")) || !List.of("SES", "POSTMARK", "SENDGRID", "MAILGUN").contains(kind))
         {
            respond(context, 404, "error", "Provider not found");
            return;
         }
         QRecord provider = providers.getFirst();
         String secret = "SES".equals(kind) ? provider.getValueString("snsTopicArn") : WebhookVerification.secret(provider);
         if(secret == null || secret.isBlank())
         {
            respond(context, 503, "error", "Webhook verification unavailable");
            return;
         }
         String payload = new String(bytes, StandardCharsets.UTF_8);
         JSONObject json = "SENDGRID".equals(kind) ? new JSONObject() : new JSONObject(payload);
         boolean verified = switch(kind)
         {
            case "SES" -> sns.verify(json, secret, EmailRuntime.clock().instant());
            case "POSTMARK" -> new PostmarkVerifier().verify(context.header("Authorization"), secret);
            case "SENDGRID" -> new SendGridVerifier().verify(bytes, context.header("X-Twilio-Email-Event-Webhook-Signature"),
               context.header("X-Twilio-Email-Event-Webhook-Timestamp"), secret, EmailRuntime.clock().instant());
            case "MAILGUN" -> new MailgunVerifier().verify(json, secret, EmailRuntime.clock().instant());
            default -> false;
         };
         if(!verified)
         {
            respond(context, 401, "error", "Webhook verification failed");
            return;
         }
         String id = WebhookVerification.hash(bytes);
         String nonce = null;
         String note = null;
         if("SES".equals(kind))
         {
            id = json.getString("MessageId");
            if(!"Notification".equals(json.getString("Type")))
            {
               if(!SnsVerifier.allowedUrl(json.getString("SubscribeURL"), false))
               {
                  respond(context, 401, "error", "Webhook verification failed");
                  return;
               }
               if("SubscriptionConfirmation".equals(json.getString("Type")))
               {
                  sns.confirm(json);
               }
               note = "SNS subscription control message verified";
            }
         }
         else if("MAILGUN".equals(kind))
         {
            id = json.getJSONObject("event-data").getString("id");
            nonce = json.getJSONObject("signature").getString("token");
         }
         else if("POSTMARK".equals(kind) && json.has("ID"))
         {
            id = json.getString("RecordType") + ":" + json.get("ID");
         }
         else if("SENDGRID".equals(kind))
         {
            new JSONArray(payload);
         }
         if(id.isBlank() || id.length() > 500)
         {
            respond(context, 401, "error", "Webhook verification failed");
            return;
         }
         EmailTrackingStore.Receipt outcome = EmailTrackingStore.receive(provider, id, nonce, bytes, note);
         respond(context, outcome == EmailTrackingStore.Receipt.REPLAY ? 401 : 200,
            outcome == EmailTrackingStore.Receipt.REPLAY ? "error" : outcome == EmailTrackingStore.Receipt.DUPLICATE ? "duplicate" : "received", true);
      }
      catch(org.json.JSONException e)
      {
         respond(context, 401, "error", "Webhook verification failed");
      }
      catch(Exception e)
      {
         if(e instanceof InterruptedException)
         {
            Thread.currentThread().interrupt();
         }
         respond(context, 503, "error", "Email inbox unavailable");
      }
      finally
      {
         QContext.init(previous);
      }
   }

   /*******************************************************************************
    ** GET is read-only. Only an explicit one-click or page form POST opts out.
    *******************************************************************************/
   private void unsubscribe(Context context)
   {
      CapturedContext previous = QContext.capture();
      try
      {
         QContext.init(instance, new com.kingsrook.qqq.backend.core.model.session.QSystemUserSession(instance));
         context.header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'");
         byte[] bytes = body(context);
         if(bytes == null)
         {
            return;
         }
         String token = context.pathParam("token");
         EmailUnsubscribe service = new EmailUnsubscribe();
         QRecord message = service.resolve(token);
         if(message == null)
         {
            context.status(404).contentType("text/html; charset=utf-8").result("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Link not found</title><p>This link is unavailable.</p></html>");
            return;
         }
         boolean post = "POST".equals(context.req().getMethod());
         boolean oneClick = false;
         if(post)
         {
            String form = new String(bytes, StandardCharsets.UTF_8);
            oneClick = "List-Unsubscribe=One-Click".equals(form);
            if(!oneClick && !"confirm=unsubscribe".equals(form))
            {
               respond(context, 400, "error", "Unsubscribe confirmation required");
               return;
            }
            service.unsubscribe(token);
         }
         context.status(200).contentType(oneClick ? "text/plain" : "text/html; charset=utf-8").result(oneClick ? "" : service.page(message, post));
      }
      catch(Exception e)
      {
         respond(context, 503, "error", "Unsubscribe unavailable");
      }
      finally
      {
         QContext.init(previous);
      }
   }

   /*******************************************************************************
    ** Fixed JSON responses contain no request or credential data.
    *******************************************************************************/
   private static void respond(Context context, int status, String key, Object value)
   {
      context.status(status).contentType("application/json").result(new JSONObject().put(key, value).toString());
   }
}
