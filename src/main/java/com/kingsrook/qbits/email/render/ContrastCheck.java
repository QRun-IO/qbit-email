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

package com.kingsrook.qbits.email.render;


import java.util.Locale;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** WCAG relative luminance and AA contrast for both email color schemes.
 *******************************************************************************/
public final class ContrastCheck
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ContrastCheck()
   {
   }

   /*******************************************************************************
    ** Check every actual foreground/background pair in the branded shell.
    *******************************************************************************/
   public static void validate(QRecord brand)
   {
      check("Text on background", color(brand, "textColor"), color(brand, "backgroundColor"), "light");
      check("Text on background", color(brand, "darkTextColor"), color(brand, "darkBackgroundColor"), "dark");
      check("Button text on primary", color(brand, "buttonTextColor"), color(brand, "primaryColor"), "light");
      check("Button text on primary", color(brand, "darkButtonTextColor"), color(brand, "darkPrimaryColor"), "dark");
   }

   /*******************************************************************************
    ** Defaults are neutral and accessible; reject anything except hex colors.
    *******************************************************************************/
   public static String color(QRecord brand, String field)
   {
      String value = brand.getValueString(field);
      if(value == null || value.isBlank())
      {
         value = switch(field)
         {
            case "backgroundColor", "buttonTextColor", "darkTextColor" -> "#ffffff";
            case "primaryColor" -> "#2459a6";
            case "darkPrimaryColor" -> color(brand, "primaryColor");
            case "darkButtonTextColor" -> ratio(color(brand, "buttonTextColor"), color(brand, "darkPrimaryColor")) >= 4.5
               ? color(brand, "buttonTextColor") : ratio("#000000", color(brand, "darkPrimaryColor")) >= 4.5 ? "#000000" : "#ffffff";
            default -> "#171717";
         };
      }
      if(!value.matches("#[0-9a-fA-F]{6}"))
      {
         throw (new IllegalArgumentException("Brand colors must use six-digit hex values"));
      }
      return (value);
   }

   /*******************************************************************************
    ** Contrast is independent of which color is lighter.
    *******************************************************************************/
   public static double ratio(String first, String second)
   {
      double a = luminance(first);
      double b = luminance(second);
      return ((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05));
   }

   /*******************************************************************************
    ** sRGB linearization and weighted luminance.
    *******************************************************************************/
   private static double luminance(String color)
   {
      double[] weights = {0.2126, 0.7152, 0.0722};
      double result = 0;
      for(int i = 0; i < 3; i++)
      {
         double channel = Integer.parseInt(color.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
         result += weights[i] * (channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4));
      }
      return (result);
   }

   /*******************************************************************************
    ** Author-facing refusal explains the failing scheme and threshold.
    *******************************************************************************/
   private static void check(String label, String foreground, String background, String scheme)
   {
      double ratio = ratio(foreground, background);
      if(ratio < 4.5)
      {
         throw (new IllegalArgumentException(String.format(Locale.ROOT, "%s is %.1f:1 in %s mode; it needs 4.5:1", label, ratio, scheme)));
      }
   }
}
