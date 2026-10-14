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

package com.kingsrook.qbits.email.demo;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailQBitProducer;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.delivery.EmailDispatcher;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailBuiltIns;
import com.kingsrook.qbits.email.template.EmailVersions;
import com.kingsrook.qbits.email.transport.EmailTransportRegistry;
import com.kingsrook.qbits.email.transport.SesEmailTransport;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.FullyAnonymousAuthenticationModule;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;


/*******************************************************************************
 ** Local screenshot application: H2, GreenMail and fake SES, all in memory.
 ** Home links already use /app/table/id, equivalent to webhooks' table prefix.
 ** Nothing in this test package is shipped or included in coverage.
 **
 ** ./mvnw -o test-compile exec:java -Dexec.classpathScope=test
 **    -Dexec.mainClass=com.kingsrook.qbits.email.demo.EmailDemoServer -Dexec.args=8098
 *******************************************************************************/
public class EmailDemoServer extends AbstractQQQApplication
{
   private static final RDBMSBackendMetaData DATABASE = backend();

   /*******************************************************************************
    ** Anonymous sessions with all email permissions, exclusively for this demo.
    *******************************************************************************/
   public static class DemoAuthenticationModule extends FullyAnonymousAuthenticationModule
   {
      /*******************************************************************************
       ** Local demo session.
       *******************************************************************************/
      @Override
      public QSession createSession(QInstance instance, Map<String, String> context)
      {
         return (super.createSession(instance, context).withPermissions(EmailPermissions.ALL));
      }
   }

   /*******************************************************************************
    ** H2 survives connections for this JVM only, without filesystem storage.
    *******************************************************************************/
   private static RDBMSBackendMetaData backend()
   {
      var backend = new RDBMSBackendMetaData().withName("rdbms").withVendor("h2").withHostName("mem").withDatabaseName("email_demo").withUsername("sa");
      backend.setJdbcUrl(ConnectionManager.getJdbcUrl(backend) + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1");
      return (backend);
   }

   /*******************************************************************************
    ** Exercise SES MIME and request construction without making AWS calls.
    *******************************************************************************/
   private static SesV2Client fakeSes()
   {
      return ((SesV2Client) java.lang.reflect.Proxy.newProxyInstance(SesV2Client.class.getClassLoader(), new Class<?>[] {SesV2Client.class}, (proxy, method, args) ->
      {
         return (switch(method.getName())
         {
            case "sendEmail" -> SendEmailResponse.builder().messageId("demo-ses-" + UUID.randomUUID()).build();
            case "serviceName" -> "sesv2";
            case "close" -> null;
            default -> throw (new UnsupportedOperationException("Unsupported demo SES call"));
         });
      }));
   }

   /*******************************************************************************
    ** UI actions also use the fake SES client, after startup seeding completes.
    *******************************************************************************/
   @Override
   public QInstance defineQInstance() throws QException
   {
      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(DATABASE);
      new EmailQBitProducer().withQBitConfig(new EmailQBitConfig().withBackendName("rdbms")
         .withPublicBaseUrl("https://kof22.example").withMaxAttempts(3).withRetryMinutes(List.of(1, 5)).withSecretResolver(System::getProperty)
         .withTransportRegistryFactory(() -> new EmailTransportRegistry().withTransport("SES", new SesEmailTransport(builder -> fakeSes()))))
         .produce(instance);
      instance.setHomeAppName("email");
      return (instance);
   }

   /*******************************************************************************
    ** Provider rows pass through actual save validation.
    *******************************************************************************/
   private static QRecord provider(String name, String label, String kind, int port, boolean defaultProvider) throws Exception
   {
      return (H2Support.insert(EmailTables.PROVIDER, new QRecord().withValue("name", name).withValue("label", label).withValue("kind", kind)
         .withValue("host", "127.0.0.1").withValue("port", port).withValue("security", "NONE").withValue("region", "us-east-1")
         .withValue("fromAddress", "hello@kof22.example").withValue("fromName", "Kof22").withValue("enabled", true)
         .withValue("isDefault", defaultProvider).withValue("phiAllowed", true).withValue("trackOpens", false).withValue("trackClicks", false)));
   }

   /*******************************************************************************
    ** Real template/version lifecycle with sample data for the inline preview.
    *******************************************************************************/
   private static QRecord template(String name, String label, String kind, String category, Integer brand, String body, boolean active) throws Exception
   {
      QRecord template = H2Support.insert(EmailTables.TEMPLATE, new QRecord().withValue("name", name).withValue("label", label)
         .withValue("kind", kind).withValue("category", category).withValue("brandId", brand));
      QRecord version = EmailVersions.newVersion(template.getValueInteger("id"));
      SystemWrites.updateOne(EmailTables.VERSION, new QRecord().withValue("id", version.getValue("id")).withValue("subject", label + " - {{topic}}")
         .withValue("preheader", "An update from Kof22").withValue("bodySource", body)
         .withValue("variablesSchema", "[{\"name\":\"name\",\"type\":\"STRING\"},{\"name\":\"topic\",\"type\":\"STRING\"}]")
         .withValue("sampleVariables", "{\"name\":\"Alex Morgan\",\"topic\":\"Your community project\"}"));
      if(active)
      {
         EmailVersions.activate(version.getValueInteger("id"));
      }
      return (version);
   }

   /*******************************************************************************
    ** Messages and attempts are created only by the real service and dispatcher.
    *******************************************************************************/
   static Map<String, Integer> seed(QInstance instance, GreenMail mail, int closedPort) throws Exception
   {
      H2Support.prime(DATABASE);
      QContext.init(instance, H2Support.admin());
      QRecord relay = provider("galaxy", "Galaxy relay (PMG)", "SMTP", mail.getSmtp().getPort(), true);
      provider("ses", "Amazon SES", "SES", 25, false);
      provider("backup", "Backup relay", "SMTP", closedPort, false);
      QRecord brand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "Kof22").withValue("label", "Kof22")
         .withValue("isDefault", true).withValue("siteUrl", "https://kof22.com").withValue("postalAddress", "100 Market Street, Chester, IL 62233")
         .withValue("primaryColor", "#2459a6").withValue("textColor", "#172033").withValue("backgroundColor", "#ffffff")
         .withValue("buttonTextColor", "#ffffff").withValue("darkPrimaryColor", "#93c5fd").withValue("darkButtonTextColor", "#172033")
         .withValue("darkBackgroundColor", "#111827").withValue("darkTextColor", "#f9fafb").withValue("footerMarkdown", "**Kof22** - Ideas, projects, and community."));
      Integer brandId = brand.getValueInteger("id");
      QRecord contact = template("contact_form_received", "Contact form received", "MARKDOWN", "NOTIFICATION", brandId,
         "# Thanks for reaching out, {{name}}\n\nWe received your message about **{{topic}}**. We will follow up shortly.\n\n[Visit Kof22](https://kof22.com){.button}", true);
      QRecord v2 = EmailVersions.newVersion(contact.getValueInteger("templateId"));
      EmailVersions.activate(v2.getValueInteger("id"));
      template("alerts_notification", "Project alert", "MARKDOWN", "NOTIFICATION", brandId,
         "# A project needs your attention\n\nHello {{name}}, there is an update for **{{topic}}**.\n\n| Item | Status |\n|---|---|\n| Community project | Ready for review |\n\n[Review update](https://kof22.com){.button}", true);
      template("newsletter_october", "October at Kof22", "HTML", "MARKETING", brandId,
         "<html><body><h1>October at Kof22</h1><p>Hello {{name}},</p><p>This month: {{topic}}.</p><h2>Building something useful</h2><p>Stories from our projects and community.</p><a href='https://kof22.com'>Explore the latest</a></body></html>", false);
      EmailBuiltIns.ensure();
      QRecord secure = SystemWrites.query(EmailTables.TEMPLATE, EmailStore.equal("name", "email_secure_message")).getFirst();
      SystemWrites.updateOne(EmailTables.TEMPLATE, new QRecord().withValue("id", secure.getValue("id")).withValue("brandId", brandId));
      EmailVersions.approve(secure.getValueInteger("activeVersionId"));

      Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
      String[] reasons = {"HARD_BOUNCE", "COMPLAINT", "UNSUBSCRIBE", "MANUAL"};
      for(int i = 0; i < 20; i++)
      {
         EmailRuntime.setClock(Clock.fixed(now.minus(Duration.ofDays(i * 29L / 19)), ZoneOffset.UTC));
         H2Support.insert(EmailTables.SUPPRESSION, new QRecord().withValue("address", "suppressed" + i + "@example.test")
            .withValue("scope", "ALL").withValue("reason", reasons[i % reasons.length]).withValue("note", "Demo suppression history"));
      }
      Integer messageId = null;
      EmailDispatcher dispatcher = new EmailDispatcher();
      for(int hour = 23; hour >= 0; hour--)
      {
         Instant start = now.minus(Duration.ofHours(hour)).minus(Duration.ofMinutes(25));
         EmailRuntime.setClock(Clock.fixed(start, ZoneOffset.UTC));
         for(int i = 0; i < 5; i++)
         {
            int sequence = (23 - hour) * 5 + i;
            boolean phi = sequence == 37 || sequence == 88;
            EmailRequest request = new EmailRequest().withTemplateName(phi ? "email_secure_message" : sequence % 3 == 0 ? "alerts_notification" : "contact_form_received")
               .withProviderName(sequence % 11 == 0 ? "backup" : sequence % 5 == 0 ? "ses" : "galaxy")
               .withTo(sequence % 19 == 0 ? "suppressed" + (sequence % 20) + "@example.test" : "reader" + sequence + "@example.test");
            if(phi)
            {
               request.withVariable("link", "https://kof22.com/secure/demo-" + sequence);
            }
            else
            {
               request.withVariable("name", List.of("Alex Morgan", "Jordan Lee", "Sam Taylor", "Casey Rivera", "Riley Chen").get(i))
                  .withVariable("topic", List.of("Community center", "October newsletter", "Website feedback", "Volunteer program", "Project update").get(i));
            }
            EmailSendResult result = EmailService.send(request);
            if(result.status() == EmailSendResult.Status.REFUSED)
            {
               throw (new IllegalStateException("Demo send refused: " + result.reasons()));
            }
            if(messageId == null && result.status() == EmailSendResult.Status.QUEUED)
            {
               messageId = result.messageId();
            }
            SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", result.messageId()).withValue("createDate", start));
         }
         for(int pass = 0; pass < 3; pass++)
         {
            EmailRuntime.setClock(Clock.fixed(start.plus(Duration.ofMinutes(pass * 6L)), ZoneOffset.UTC));
            dispatcher.runOnce();
         }
      }
      EmailRuntime.setClock(null);
      System.out.println("Seeded " + H2Support.all(EmailTables.MESSAGE).size() + " messages, " + H2Support.all(EmailTables.ATTEMPT).size() + " real attempts; " + mail.getReceivedMessages().length + " captured SMTP deliveries.");
      QContext.clear();
      return (Map.of("message", messageId, "version", v2.getValueInteger("id"), "provider", relay.getValueInteger("id")));
   }

   /*******************************************************************************
    ** Start SMTP and Next UI; shutdown releases in-memory mail.
    *******************************************************************************/
   public static void main(String[] args) throws Exception
   {
      int port = args.length == 0 ? 8098 : Integer.parseInt(args[0]);
      System.setProperty("QQQ_EMAIL_BODY_KEY", "local-demo-only-body-key");
      System.setProperty("QQQ_EMAIL_UNSUBSCRIBE_KEY", "local-demo-only-unsubscribe-key");
      var field = QAuthenticationModuleDispatcher.class.getDeclaredField("authenticationTypeToModuleClassNameMap");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> modules = (Map<String, String>) field.get(null);
      modules.put(QAuthenticationType.FULLY_ANONYMOUS.getName(), DemoAuthenticationModule.class.getName());
      GreenMail mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
      mail.start();
      Runtime.getRuntime().addShutdownHook(new Thread(mail::stop));
      int closedPort;
      try(var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()))
      {
         closedPort = socket.getLocalPort();
      }
      try
      {
         EmailDemoServer application = new EmailDemoServer();
         QInstance instance = application.defineQInstance();
         new QInstanceValidator().validate(instance);
         Map<String, Integer> ids = seed(instance, mail, closedPort);
         new QApplicationJavalinServer(application).withPort(port).withServeFrontendNext(true).start();
         String base = "http://localhost:" + port + "/app/";
         System.out.println("Home: " + base + "email");
         System.out.println("Message: " + base + EmailTables.MESSAGE + "/" + ids.get("message"));
         System.out.println("Template preview: " + base + EmailTables.VERSION + "/" + ids.get("version"));
         System.out.println("Provider: " + base + EmailTables.PROVIDER + "/" + ids.get("provider"));
      }
      catch(Exception e)
      {
         mail.stop();
         EmailRuntime.setClock(null);
         QContext.clear();
         throw (e);
      }
   }
}
