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

package com.kingsrook.qbits.email.permissions;


import java.util.List;


/*******************************************************************************
 ** Email permissions: view, send, manage and admin, plus the derived app access.
 ** Administrators imply all permissions; managers may also send and view.
 *******************************************************************************/
public final class EmailPermissions
{
   public static final String SEND = "email.send";
   public static final String VIEW       = "email.view";
   public static final String MANAGE     = "email.manage";
   public static final String ADMIN      = "email.admin";
   public static final String HAS_ACCESS = "email.hasAccess";

   public static final List<String> ALL = List.of(VIEW, SEND, MANAGE, ADMIN, HAS_ACCESS);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailPermissions()
   {
   }
}
