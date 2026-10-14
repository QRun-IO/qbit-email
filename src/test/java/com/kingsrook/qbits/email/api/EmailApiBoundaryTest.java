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

package com.kingsrook.qbits.email.api;


import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** No ArchUnit dependency: inspect every compiled API class and nested type,
 ** including generic arguments, bounds, arrays, exceptions and inheritance.
 *******************************************************************************/
class EmailApiBoundaryTest
{
   /*******************************************************************************
    ** Discovery uses production output so new API classes cannot escape review.
    *******************************************************************************/
   @Test
   void publishedSignaturesUseOnlyApiJdkAndQqqCoreTypes() throws Exception
   {
      Path root = Path.of(EmailApi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path packagePath = root.resolve("com/kingsrook/qbits/email/api");
      int count = 0;
      try(var files = Files.walk(packagePath))
      {
         for(Path path : files.filter(p -> p.toString().endsWith(".class")).toList())
         {
            String name = root.relativize(path).toString().replace('\\', '.').replace('/', '.').replaceFirst("\\.class$", "");
            inspect(Class.forName(name));
            count++;
         }
      }
      assertThat(count).isGreaterThanOrEqualTo(11);
   }



   /*******************************************************************************
    ** Prove the guard catches indirect leaks, not just raw return classes.
    *******************************************************************************/
   @Test
   void rejectsNestedGenericAndProtectedLeaks() throws Exception
   {
      assertThatThrownBy(() -> inspect(LeakingGeneric.class)).isInstanceOf(AssertionError.class);
      assertThatThrownBy(() -> inspect(LeakingProtected.class)).isInstanceOf(AssertionError.class);
   }



   /*******************************************************************************
    ** Constructors, fields, inherited methods and protected declarations count.
    *******************************************************************************/
   private void inspect(Class<?> apiClass)
   {
      Set<Type> seen = new HashSet<>();
      check(apiClass.getGenericSuperclass(), seen);
      checkAll(apiClass.getGenericInterfaces(), seen);
      checkAll(apiClass.getTypeParameters(), seen);
      for(var field : apiClass.getDeclaredFields())
      {
         if(visible(field.getModifiers()))
         {
            check(field.getGenericType(), seen);
         }
      }
      for(var constructor : apiClass.getDeclaredConstructors())
      {
         if(visible(constructor.getModifiers()))
         {
            checkAll(constructor.getGenericParameterTypes(), seen);
            checkAll(constructor.getGenericExceptionTypes(), seen);
            checkAll(constructor.getTypeParameters(), seen);
         }
      }
      Set<java.lang.reflect.Method> methods = new HashSet<>(java.util.List.of(apiClass.getDeclaredMethods()));
      methods.addAll(java.util.List.of(apiClass.getMethods()));
      for(var method : methods)
      {
         if(visible(method.getModifiers()))
         {
            check(method.getGenericReturnType(), seen);
            checkAll(method.getGenericParameterTypes(), seen);
            checkAll(method.getGenericExceptionTypes(), seen);
            checkAll(method.getTypeParameters(), seen);
         }
      }
   }



   /*******************************************************************************
    ** Public and protected form the published contract.
    *******************************************************************************/
   private boolean visible(int modifiers)
   {
      return (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers));
   }



   /*******************************************************************************
    ** Walk each type without looping on recursive type bounds.
    *******************************************************************************/
   private void checkAll(Type[] types, Set<Type> seen)
   {
      for(Type type : types)
      {
         check(type, seen);
      }
   }



   /*******************************************************************************
    ** Generic containers cannot hide an implementation or third-party type.
    *******************************************************************************/
   private void check(Type type, Set<Type> seen)
   {
      if(type == null || !seen.add(type))
      {
         return;
      }
      if(type instanceof Class<?> clazz)
      {
         if(clazz.isArray())
         {
            check(clazz.getComponentType(), seen);
         }
         else
         {
            String name = clazz.getName();
            String module = clazz.getModule().getName();
            boolean jdk = module != null && (module.startsWith("java.") || module.startsWith("jdk."));
            assertThat(clazz.isPrimitive() || jdk
               || name.startsWith("com.kingsrook.qqq.backend.core.") || name.startsWith("com.kingsrook.qbits.email.api."))
               .as("Published signature type %s", name).isTrue();
         }
      }
      else if(type instanceof ParameterizedType parameterized)
      {
         check(parameterized.getRawType(), seen);
         check(parameterized.getOwnerType(), seen);
         checkAll(parameterized.getActualTypeArguments(), seen);
      }
      else if(type instanceof TypeVariable<?> variable)
      {
         checkAll(variable.getBounds(), seen);
      }
      else if(type instanceof WildcardType wildcard)
      {
         checkAll(wildcard.getUpperBounds(), seen);
         checkAll(wildcard.getLowerBounds(), seen);
      }
      else if(type instanceof GenericArrayType array)
      {
         check(array.getGenericComponentType(), seen);
      }
      else
      {
         throw (new AssertionError("Unhandled signature type: " + type));
      }
   }



   /*******************************************************************************
    ** Deliberately invalid fixture, outside production class discovery.
    *******************************************************************************/
   private static class LeakingGeneric
   {
      /*******************************************************************************
       ** A wildcard and array must not disguise an internal request type.
       *******************************************************************************/
      public java.util.List<? extends com.kingsrook.qbits.email.outbox.EmailRequest[]> leak()
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** Protected fields are also part of the API contract.
    *******************************************************************************/
   private static class LeakingProtected
   {
      protected com.kingsrook.qbits.email.EmailQBitConfig config;
   }
}
