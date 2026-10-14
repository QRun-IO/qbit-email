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

package com.kingsrook.qbits.email.transport;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.RawMessage;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;


/*******************************************************************************
 ** SES v2 raw MIME transport; credentials are resolved only for an attempt.
 *******************************************************************************/
public class SesEmailTransport implements EmailTransport
{
   private final Function<SesV2ClientBuilder, SesV2Client> clientFactory;

   /*******************************************************************************
    ** Production client factory.
    *******************************************************************************/
   public SesEmailTransport()
   {
      this(SesV2ClientBuilder::build);
   }

   /*******************************************************************************
    ** Package-visible fake-client seam.
    *******************************************************************************/
   SesEmailTransport(Function<SesV2ClientBuilder, SesV2Client> clientFactory)
   {
      this.clientFactory = clientFactory;
   }

   /*******************************************************************************
    ** Send the same MIME parts and headers as SMTP.
    *******************************************************************************/
   @Override
   public EmailTransportResult send(EmailEnvelope envelope, EmailProviderSettings settings) throws EmailTransportException
   {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try
      {
         EmailMime.build(Session.getInstance(new Properties()), envelope).writeTo(bytes, new String[] {"Bcc"});
      }
      catch(MessagingException | IOException | RuntimeException e)
      {
         throw (new EmailTransportException("Invalid email message", true));
      }
      AtomicBoolean credentialsFailed = new AtomicBoolean();
      try(DefaultCredentialsProvider defaultCredentials = DefaultCredentialsProvider.builder().build())
      {
         SesV2ClientBuilder builder = SesV2Client.builder().region(Region.of(settings.value("region")))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(40)).apiCallAttemptTimeout(Duration.ofSeconds(35))
               .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none()));
         if(settings.value("usernameEnvName") != null)
         {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.resolve("usernameEnvName"), settings.resolve("secretEnvName"))));
         }
         else
         {
            builder.credentialsProvider(() ->
            {
               try
               {
                  return (defaultCredentials.resolveCredentials());
               }
               catch(RuntimeException e)
               {
                  credentialsFailed.set(true);
                  throw (e);
               }
            });
         }
         SendEmailRequest request = SendEmailRequest.builder()
            .fromEmailAddress(envelope.getFrom())
            .destination(Destination.builder().toAddresses(destinations(envelope.getTo())).ccAddresses(destinations(envelope.getCc())).bccAddresses(destinations(envelope.getBcc())).build())
            .configurationSetName(settings.value("configurationSet"))
            .content(EmailContent.builder().raw(RawMessage.builder().data(SdkBytes.fromByteArray(bytes.toByteArray())).build()).build()).build();
         try(SesV2Client client = clientFactory.apply(builder))
         {
            return (new EmailTransportResult(client.sendEmail(request).messageId()));
         }
      }
      catch(EmailTransportException e)
      {
         throw (e);
      }
      catch(SesV2Exception e)
      {
         String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
         boolean clientFailure = e.statusCode() >= 400 && e.statusCode() < 500 && e.statusCode() != 429 && !e.isThrottlingException();
         boolean permanent = clientFailure && e.statusCode() != 401 && e.statusCode() != 403
            && (e instanceof MessageRejectedException || "MessageRejected".equals(code) || "InvalidParameterValue".equals(code));
         throw (new EmailTransportException(permanent ? "SES rejected the message" : "SES temporarily unavailable", permanent)
            .withProviderLevel(clientFailure && !permanent));
      }
      catch(AddressException e)
      {
         throw (new EmailTransportException("Invalid email message", true));
      }
      catch(Exception e)
      {
         throw (new EmailTransportException("SES temporarily unavailable", false).withProviderLevel(credentialsFailed.get()));
      }
   }


   /*******************************************************************************
    ** SES envelope destinations are individual mailboxes, without display names.
    *******************************************************************************/
   private static List<String> destinations(List<String> values) throws AddressException
   {
      List<String> addresses = new ArrayList<>();
      for(String value : values)
      {
         for(InternetAddress address : InternetAddress.parse(value, true))
         {
            addresses.add(address.getAddress());
         }
      }
      return (addresses);
   }
}
