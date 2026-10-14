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


import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;


/*******************************************************************************
 ** HTML to readable text using the JDK parser (including entity decoding).
 *******************************************************************************/
public final class HtmlToText
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private HtmlToText()
   {
   }

   /*******************************************************************************
    ** Keep links, headings, lists and table rows; omit scripts and styles.
    *******************************************************************************/
   public static String convert(String html)
   {
      StringBuilder text = new StringBuilder();
      try
      {
         new ParserDelegator().parse(new StringReader(html == null ? "" : html), new HTMLEditorKit.ParserCallback()
         {
            private int hidden;
            private String href;

            /*******************************************************************************
             ** Start readable block boundaries and remember link destinations.
             *******************************************************************************/
            @Override
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position)
            {
               if(tag == HTML.Tag.STYLE || tag == HTML.Tag.SCRIPT || tag == HTML.Tag.HEAD)
               {
                  hidden++;
               }
               if(tag == HTML.Tag.A)
               {
                  href = (String) attributes.getAttribute(HTML.Attribute.HREF);
               }
               if(tag == HTML.Tag.LI)
               {
                  text.append("\n- ");
               }
               if(tag == HTML.Tag.TD || tag == HTML.Tag.TH)
               {
                  text.append(" ");
               }
            }

            /*******************************************************************************
             ** Add link destinations and block separators.
             *******************************************************************************/
            @Override
            public void handleEndTag(HTML.Tag tag, int position)
            {
               if(tag == HTML.Tag.STYLE || tag == HTML.Tag.SCRIPT || tag == HTML.Tag.HEAD)
               {
                  hidden = Math.max(0, hidden - 1);
               }
               if(tag == HTML.Tag.A && href != null)
               {
                  text.append(" (").append(href).append(')');
                  href = null;
               }
               if(tag.isBlock())
               {
                  text.append('\n');
               }
            }

            /*******************************************************************************
             ** Line breaks remain line breaks.
             *******************************************************************************/
            @Override
            public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position)
            {
               if(tag == HTML.Tag.BR)
               {
                  text.append('\n');
               }
            }

            /*******************************************************************************
             ** Parser-decoded text, excluding executable and metadata content.
             *******************************************************************************/
            @Override
            public void handleText(char[] data, int position)
            {
               if(hidden == 0)
               {
                  text.append(data);
               }
            }
         }, true);
      }
      catch(java.io.IOException e)
      {
         throw (new IllegalArgumentException("Could not convert email HTML"));
      }
      return (text.toString().replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip());
   }
}
