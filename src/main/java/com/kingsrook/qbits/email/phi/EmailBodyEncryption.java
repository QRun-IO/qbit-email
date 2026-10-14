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

package com.kingsrook.qbits.email.phi;


import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import com.kingsrook.qbits.email.EmailRuntime;


/*******************************************************************************
 ** Stateless AES-256-GCM body protection. Only the dispatcher decrypts content.
 *******************************************************************************/
public final class EmailBodyEncryption
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailBodyEncryption()
   {
   }

   /*******************************************************************************
    ** Resolve the deployment key, never retaining it between operations.
    *******************************************************************************/
   private static SecretKeySpec key() throws Exception
   {
      String secret = EmailRuntime.config().getSecretResolver().apply("QQQ_EMAIL_BODY_KEY");
      if(secret == null || secret.isBlank())
      {
         throw (new IllegalArgumentException("PHI email needs QQQ_EMAIL_BODY_KEY"));
      }
      return (new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES"));
   }

   /*******************************************************************************
    ** Encrypt with a fresh 96-bit nonce and 128-bit authentication tag.
    *******************************************************************************/
   public static String encrypt(String value, String column, String messageId)
   {
      try
      {
         SecretKeySpec key = key();
         if(value == null)
         {
            return (null);
         }
         byte[] iv = new byte[12];
         new SecureRandom().nextBytes(iv);
         Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
         cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
         cipher.updateAAD(aad(column, messageId));
         byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
         return ("enc:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array()));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("PHI email needs QQQ_EMAIL_BODY_KEY"));
      }
   }

   /*******************************************************************************
    ** Authenticate before exposing plaintext. Failures never echo ciphertext.
    *******************************************************************************/
   public static String decrypt(String value, String column, String messageId)
   {
      if(value == null || !value.startsWith("enc:v1:"))
      {
         return (value);
      }
      try
      {
         ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(value.substring(7)));
         byte[] iv = new byte[12];
         bytes.get(iv);
         byte[] encrypted = new byte[bytes.remaining()];
         bytes.get(encrypted);
         Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
         cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
         cipher.updateAAD(aad(column, messageId));
         return (new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Encrypted email body could not be opened"));
      }
   }

   /*******************************************************************************
    ** Bind ciphertext to its storage table, column and message identity.
    *******************************************************************************/
   private static byte[] aad(String column, String messageId)
   {
      String storageColumn = switch(column)
      {
         case "subject" -> "subject";
         case "htmlBody" -> "html_body";
         case "textBody" -> "text_body";
         default -> throw (new IllegalArgumentException("Unknown protected email column"));
      };
      return (("email_message\n" + storageColumn + "\n" + java.util.Objects.requireNonNull(messageId)).getBytes(StandardCharsets.UTF_8));
   }

   /*******************************************************************************
    ** Authenticate and rebind retained ciphertext without exposing plaintext to
    ** the resend caller. Only delivery callers receive decrypted content.
    *******************************************************************************/
   public static String rebind(String value, String column, String oldMessageId, String newMessageId)
   {
      return (encrypt(decrypt(value, column, oldMessageId), column, newMessageId));
   }

}
