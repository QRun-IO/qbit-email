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


import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** Small parsed template language: values, fallback, if and each, without code.
 *******************************************************************************/
public class EmailTemplateEngine
{
   /*******************************************************************************
    ** Rendering context. Markdown uses entities for all punctuation so values
    ** cannot inject Markdown links, images or block syntax.
    *******************************************************************************/
   public enum Context
   {
      HTML, TEXT, MARKDOWN
   }

   /*******************************************************************************
    ** A parsed literal, expression or block.
    *******************************************************************************/
   private record Node(String kind, String value, String fallback, int line, List<Node> children)
   {
   }

   /*******************************************************************************
    ** Validated immutable parse result.
    *******************************************************************************/
   public static class Parsed
   {
      private final List<Node> nodes;

      /*******************************************************************************
       ** Store parsed nodes.
       *******************************************************************************/
      private Parsed(List<Node> nodes)
      {
         this.nodes = nodes;
      }

      /*******************************************************************************
       ** Render the already validated syntax tree.
       *******************************************************************************/
      public String render(Map<String, ?> values, Context context)
      {
         return (renderNodes(nodes, values, null, -1, context));
      }
   }

   /*******************************************************************************
    ** Parse once, checking balanced blocks and every variable, with line numbers.
    *******************************************************************************/
   public Parsed parse(String source, VariableSchema schema)
   {
      source = source == null ? "" : source;
      List<Node> root = new ArrayList<>();
      Deque<Node> stack = new ArrayDeque<>();
      int position = 0;
      while(position < source.length())
      {
         List<Node> current = stack.isEmpty() ? root : stack.peek().children();
         int start = source.indexOf("{{", position);
         if(start < 0)
         {
            current.add(new Node("literal", source.substring(position), null, 0, List.of()));
            break;
         }
         current.add(new Node("literal", source.substring(position, start), null, 0, List.of()));
         int line = 1 + (int) source.substring(0, start).chars().filter(c -> c == '\n').count();
         int end = source.indexOf("}}", start + 2);
         if(end < 0)
         {
            throw (error(line, "Unclosed expression"));
         }
         String expression = source.substring(start + 2, end).trim();
         if(expression.startsWith("/"))
         {
            if(stack.isEmpty() || !stack.peek().kind().equals(expression.substring(1)))
            {
               throw (error(line, "Block nesting error"));
            }
            stack.pop();
         }
         else
         {
            String kind = expression.startsWith("#if ") ? "if" : expression.startsWith("#each ") ? "each" : "value";
            String binding = kind.equals("value") ? expression : expression.substring(kind.length() + 2).trim();
            String[] parts = binding.split("\\|", 2);
            String name = parts[0].trim();
            boolean inEach = stack.stream().anyMatch(n -> n.kind().equals("each"));
            boolean local = name.equals("this") || name.startsWith("this.") || name.equals("@index");
            if(!name.matches("(?:[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*|@index)")
               || (local ? !inEach : !schema.knows(name)) || !kind.equals("value") && parts.length > 1)
            {
               throw (error(line, "Unknown or unsupported variable " + name));
            }
            Node node = new Node(kind, name, parts.length == 2 ? parts[1] : "", line, new ArrayList<>());
            current.add(node);
            if(!kind.equals("value"))
            {
               if(stack.size() >= 32)
               {
                  throw (error(line, "Blocks exceed 32 levels"));
               }
               stack.push(node);
            }
         }
         position = end + 2;
      }
      if(!stack.isEmpty())
      {
         throw (error(stack.peek().line(), "Unclosed " + stack.peek().kind() + " block"));
      }
      return (new Parsed(root));
   }

   /*******************************************************************************
    ** Render syntax nodes; loop scope is restored on every recursive return.
    *******************************************************************************/
   private static String renderNodes(List<Node> nodes, Map<String, ?> values, Object item, int index, Context context)
   {
      StringBuilder out = new StringBuilder();
      for(Node node : nodes)
      {
         Object value = node.value().equals("this") ? item : node.value().startsWith("this.") ? lookup(item, node.value().substring(5))
            : node.value().equals("@index") ? index : lookup(values, node.value());
         switch(node.kind())
         {
            case "literal" -> out.append(node.value());
            case "value" -> out.append(encode(value == null || value.toString().isEmpty() ? node.fallback() : value.toString(), context));
            case "if" ->
            {
               if(truthy(value))
               {
                  out.append(renderNodes(node.children(), values, item, index, context));
               }
            }
            case "each" ->
            {
               if(value instanceof List<?> list)
               {
                  for(int i = 0; i < list.size(); i++)
                  {
                     out.append(renderNodes(node.children(), values, list.get(i), i, context));
                  }
               }
            }
            default -> throw (new IllegalStateException("Invalid template node"));
         }
      }
      return (out.toString());
   }

   /*******************************************************************************
    ** Dotted paths traverse maps only; no reflection, methods or arbitrary code.
    *******************************************************************************/
   public static Object lookup(Object values, String path)
   {
      if(values instanceof Map<?, ?> map)
      {
         if(map.containsKey(path))
         {
            return (map.get(path));
         }
         int dot = path.indexOf('.');
         return (dot < 0 ? map.get(path) : lookup(map.get(path.substring(0, dot)), path.substring(dot + 1)));
      }
      return (null);
   }

   /*******************************************************************************
    ** Explicit language truthiness.
    *******************************************************************************/
   public static boolean truthy(Object value)
   {
      if(value == null || Boolean.FALSE.equals(value) || value instanceof CharSequence s && s.isEmpty()
         || value instanceof Collection<?> c && c.isEmpty() || value instanceof Map<?, ?> m && m.isEmpty())
      {
         return (false);
      }
      return (!(value instanceof Number n) || new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) != 0);
   }

   /*******************************************************************************
    ** Escape values exactly once for the consuming grammar.
    *******************************************************************************/
   public static String encode(String value, Context context)
   {
      if(context == Context.TEXT)
      {
         return (value);
      }
      if(context == Context.MARKDOWN)
      {
         StringBuilder encoded = new StringBuilder();
         value.codePoints().forEach(c -> encoded.append(Character.isLetterOrDigit(c) || c == ' ' ? new String(Character.toChars(c)) : "&#" + c + ";"));
         return (encoded.toString());
      }
      return (escape(value));
   }

   /*******************************************************************************
    ** Attribute and text escaping, including both quote types.
    *******************************************************************************/
   public static String escape(Object value)
   {
      return (value == null ? "" : value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
         .replace("\"", "&quot;").replace("'", "&#39;"));
   }

   /*******************************************************************************
    ** Stable author-facing parse diagnostic.
    *******************************************************************************/
   private static IllegalArgumentException error(int line, String message)
   {
      return (new IllegalArgumentException("Line " + line + ": " + message));
   }
}
