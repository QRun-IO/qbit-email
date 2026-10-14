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


import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Compiler isolation without spawning a child or writing temporary files.
 *******************************************************************************/
class CliMjmlCompilerTest
{
   /*******************************************************************************
    ** Only PATH survives; includes are disabled even for an include-free input.
    *******************************************************************************/
   @Test
   void isolatedProcessBuilder()
   {
      var builder = new CliMjmlCompiler("mjml").processBuilder();
      assertThat(builder.environment().keySet()).allMatch("PATH"::equals);
      assertThat(builder.command()).containsExactly("mjml", "-i", "-s", "--config.allowIncludes=false");
      assertThat(builder.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
   }

   /*******************************************************************************
    ** Refuse before attempting to launch even a nonexistent executable.
    *******************************************************************************/
   @Test
   void includesRefusedBeforeLaunch()
   {
      assertThatThrownBy(() -> new CliMjmlCompiler("nonexistent").compile("<MJ-INCLUDE path='private'/>"))
         .hasMessage("MJML includes are not allowed");
   }
}
