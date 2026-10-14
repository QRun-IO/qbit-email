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


import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;


/*******************************************************************************
 ** Shared HTTP seam; no provider SDK, credential cache or filesystem.
 *******************************************************************************/
@FunctionalInterface
public interface EmailHttp
{
   /*******************************************************************************
    ** Exchange one request without retries.
    *******************************************************************************/
   HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;



   /*******************************************************************************
    ** Production requests use HTTPS and never forward credentials on redirects.
    *******************************************************************************/
   static HttpResponse<String> sendJdk(HttpRequest request) throws IOException, InterruptedException
   {
      if(!"https".equalsIgnoreCase(request.uri().getScheme()))
      {
         throw (new IOException("Email HTTP requires HTTPS"));
      }
      try(HttpClient client = newClient())
      {
         return (client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
      }
   }



   /*******************************************************************************
    ** A bounded, stateless JDK client; the caller closes it after each exchange.
    *******************************************************************************/
   static HttpClient newClient()
   {
      return (HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
   }
}

