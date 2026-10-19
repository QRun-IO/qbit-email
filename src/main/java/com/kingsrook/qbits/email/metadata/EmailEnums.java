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

package com.kingsrook.qbits.email.metadata;


import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueEnum;


/*******************************************************************************
 ** Enumerated database values and their display labels.
 *******************************************************************************/
public final class EmailEnums
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailEnums()
   {
   }


   /*******************************************************************************
    ** ProviderKind values.
    *******************************************************************************/
   public enum ProviderKind implements PossibleValueEnum<String>
   {
      SMTP,
      SES,
      POSTMARK,
      SENDGRID,
      MAILGUN,
      GMAIL_SMTP,
      GMAIL_API;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** Security values.
    *******************************************************************************/
   public enum Security implements PossibleValueEnum<String>
   {
      NONE,
      STARTTLS,
      TLS;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** MessageStatus values.
    *******************************************************************************/
   public enum MessageStatus implements PossibleValueEnum<String>
   {
      QUEUED,
      SENDING,
      SENT,
      DELIVERED,
      RETRY_WAIT,
      DEAD_LETTER,
      BOUNCED,
      COMPLAINED,
      SUPPRESSED,
      CANCELLED;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (this == RETRY_WAIT ? "Waiting to retry" : name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** AttemptOutcome values.
    *******************************************************************************/
   public enum AttemptOutcome implements PossibleValueEnum<String>
   {
      IN_FLIGHT,
      SUCCEEDED,
      TRANSIENT_FAILURE,
      PROVIDER_FAILURE,
      PERMANENT_FAILURE,
      LEASE_EXPIRED;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** HealthStatus values.
    *******************************************************************************/
   public enum HealthStatus implements PossibleValueEnum<String>
   {
      HEALTHY,
      UNHEALTHY;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** TemplateKind values.
    *******************************************************************************/
   public enum TemplateKind implements PossibleValueEnum<String>
   {
      MARKDOWN,
      MJML,
      HTML,
      CRM_LEGACY;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** VersionStatus values.
    *******************************************************************************/
   public enum VersionStatus implements PossibleValueEnum<String>
   {
      DRAFT,
      ACTIVE,
      RETIRED;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** SuppressionScope values.
    *******************************************************************************/
   public enum SuppressionScope implements PossibleValueEnum<String>
   {
      ALL,
      TRANSACTIONAL,
      NOTIFICATION,
      MARKETING,
      SECURITY;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }


   /*******************************************************************************
    ** SuppressionReason values.
    *******************************************************************************/
   public enum SuppressionReason implements PossibleValueEnum<String>
   {
      HARD_BOUNCE,
      COMPLAINT,
      UNSUBSCRIBE,
      MANUAL;

      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }



   /*******************************************************************************
    ** InboxStatus values.
    *******************************************************************************/
   public enum InboxStatus implements PossibleValueEnum<String>
   {
      PENDING, PROCESSED, FAILED, IGNORED;

      /*******************************************************************************
       ** Stable stored identifier.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }



   /*******************************************************************************
    ** EventType values.
    *******************************************************************************/
   public enum EventType implements PossibleValueEnum<String>
   {
      DELIVERED, DEFERRED, BOUNCED, COMPLAINED, OPENED, CLICKED, UNSUBSCRIBED, REJECTED;

      /*******************************************************************************
       ** Stable stored identifier.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }



   /*******************************************************************************
    ** BounceType values.
    *******************************************************************************/
   public enum BounceType implements PossibleValueEnum<String>
   {
      HARD, SOFT, UNDETERMINED;

      /*******************************************************************************
       ** Stable stored identifier.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (name());
      }

      /*******************************************************************************
       ** Display label.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
      }
   }
}
