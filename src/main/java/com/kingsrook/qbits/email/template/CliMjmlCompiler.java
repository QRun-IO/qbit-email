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

package com.kingsrook.qbits.email.template;


import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/*******************************************************************************
 ** MJML stdin/stdout compiler with a total 20-second deadline and no files.
 *******************************************************************************/
public class CliMjmlCompiler implements MjmlCompilerInterface
{
   private final String binary;

   /*******************************************************************************
    ** Explicit executable path; no shell interpolation or PATH guessing.
    *******************************************************************************/
   public CliMjmlCompiler(String binary)
   {
      this.binary = binary;
   }

   /*******************************************************************************
    ** Drain stdout concurrently with stdin, discarding potentially private stderr.
    *******************************************************************************/
   @Override
   public String compile(String source)
   {
      Process process = null;
      try(var executor = Executors.newVirtualThreadPerTaskExecutor())
      {
         process = new ProcessBuilder(binary, "-i", "-s").redirectError(ProcessBuilder.Redirect.DISCARD).start();
         Process running = process;
         var task = executor.submit(() ->
         {
            var writer = executor.submit(() ->
            {
               try(var stdin = running.getOutputStream())
               {
                  stdin.write(source.getBytes(StandardCharsets.UTF_8));
               }
               return (null);
            });
            byte[] output = running.getInputStream().readNBytes(10_000_001);
            if(output.length > 10_000_000)
            {
               throw (new IllegalArgumentException("MJML output too large"));
            }
            writer.get();
            if(running.waitFor() != 0)
            {
               throw (new IllegalArgumentException("MJML compilation failed"));
            }
            return (new String(output, StandardCharsets.UTF_8));
         });
         try
         {
            return (task.get(20, TimeUnit.SECONDS));
         }
         finally
         {
            running.descendants().forEach(ProcessHandle::destroyForcibly);
            running.destroyForcibly();
            task.cancel(true);
         }
      }
      catch(Exception e)
      {
         if(e instanceof InterruptedException)
         {
            Thread.currentThread().interrupt();
         }
         throw (new IllegalArgumentException("MJML compilation failed or exceeded 20 seconds"));
      }
      finally
      {
         if(process != null)
         {
            process.destroyForcibly();
         }
      }
   }
}
